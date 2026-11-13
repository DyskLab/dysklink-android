package id.dysklink.desktop

import java.io.File

/**
 * What this PC needs from outside DyskLink, told where it matters: a short hint with what to do, a command to copy,
 * or a button. Each hint is only made while its condition holds, so it goes away once it's fixed; the window checks
 * again whenever it gets the focus back.
 */
data class HostHint(
    val title: String,
    val text: String,
    /** A command to type in a terminal, shown with a Copy button. */
    val command: String? = null,
    val link: HintLink? = null,
    /** A button that does something in DyskLink, such as installing the Windows camera driver. */
    val action: HintAction? = null,
    /** More commands to run after [command], each with its own Copy button. */
    val moreCommands: List<String> = emptyList(),
    /** A problem to fix, something to know, or a state that's fine. */
    val tone: HintTone = HintTone.Warn,
)

enum class HintTone { Warn, Info, Ok }

/** A button on a hint that opens a page or a system settings screen. */
data class HintLink(val label: String, val url: String)

/** A button on a hint that DyskLink handles itself, by [id]. */
data class HintAction(val label: String, val id: String)

/** Every hint by the place it shows. Empty lists where nothing is missing. */
data class HostHints(
    /** Under "Phones can control this PC", in Settings and on the Control tab. */
    val control: List<HostHint> = emptyList(),
    /** Under "Let phones see this screen". */
    val screenOut: List<HostHint> = emptyList(),
    /** Under "Let paired computers use their mouse and keyboard here". */
    val remote: List<HostHint> = emptyList(),
    /** Under "Let phones hear this PC", in Settings and on the Sound tab. */
    val soundOut: List<HostHint> = emptyList(),
    /** Under "Show phone notifications": popups can't show. */
    val popups: List<HostHint> = emptyList(),
    /** Once on a phone's Overview: everything Wayland stops, in one card. */
    val overview: List<HostHint> = emptyList(),
    /** On the waiting screen at once, such as a Public network on Windows. */
    val connectNow: List<HostHint> = emptyList(),
    /** On the waiting screen when no phone has come for a while: the firewall. */
    val connectLater: List<HostHint> = emptyList(),
    /** How OBS captures the camera window here; [HostChecks.obsHint] words it for the window's title. */
    val obs: ObsCapture = ObsCapture.Xcomposite,
)

/** The OBS source that captures a window on this PC. */
enum class ObsCapture { Windows, Xcomposite, PipeWire }

/** Linux distributions by the tool that installs packages. */
enum class Distro(val install: String?) {
    Debian("sudo apt install"),
    Fedora("sudo dnf install"),
    Arch("sudo pacman -S"),
    Suse("sudo zypper install"),
    Other(null),
}

/** The desktop, for how to pick another session on its login screen. */
enum class LoginDesktop { Gnome, Kde, Other }

/** Everything the checks look at, so tests can fake a PC. */
data class HostEnv(
    val windows: Boolean,
    val linux: Boolean,
    val env: (String) -> String? = { null },
    /** Whether a program is on the PATH. */
    val onPath: (String) -> Boolean = { false },
    val osRelease: String = "",
    /** False when no notification service runs on the session bus; null when unknown or not Linux. */
    val notificationService: Boolean? = null,
    /** True when Windows calls the current network Public; null when unknown. */
    val publicNetwork: Boolean? = null,
    /** False when java.awt.Robot couldn't be made (no display, no XTEST). */
    val robot: Boolean = true,
    /** Whether text can be typed through spare X key codes, without xdotool. */
    val x11Remap: Boolean = true,
    /** How the Wayland ways in stand; null on X11 and Windows. */
    val wayland: WaylandHost? = null,
    val port: Int = id.phonelink.android.core.PhoneLinkProtocol.PORT,
)

/** The Wayland portal, /dev/uinput and GStreamer as they stand now, for the hints. */
data class WaylandHost(
    /** Null while it's still being checked. */
    val route: InputRoute? = null,
    val input: PortalStatus = PortalStatus(),
    /** The desktop already allowed remote control once and keeps it (a restore token). */
    val inputRemembered: Boolean = false,
    /** Whether the portal has ScreenCast; null while unknown. */
    val screenCast: Boolean? = null,
    val gst: GstCheck = GstCheck(),
    val screen: PortalStatus = PortalStatus(),
    val screenRemembered: Boolean = false,
)

object HostChecks {
    const val ACTION_PORTAL_INPUT = "portal-input"
    const val ACTION_PORTAL_SCREEN = "portal-screen"

    /** The udev rule that lets the input group make virtual devices, as a command that writes it and applies it now. */
    const val UINPUT_RULE = "echo 'KERNEL==\"uinput\", GROUP=\"input\", MODE=\"0660\", OPTIONS+=\"static_node=uinput\"' | " +
        "sudo tee /etc/udev/rules.d/60-dysklink-uinput.rules && sudo modprobe uinput && sudo udevadm control --reload-rules && " +
        "sudo udevadm trigger --sysname-match=uinput"
    const val UINPUT_GROUP = "sudo usermod -aG input \$USER"

    /** The packages with gst-launch-1.0, pipewiresrc, and jpegenc with multipartmux. */
    fun gstPackages(distro: Distro): List<String> = when (distro) {
        Distro.Debian -> listOf("gstreamer1.0-tools", "gstreamer1.0-pipewire", "gstreamer1.0-plugins-good")
        Distro.Fedora -> listOf("gstreamer1", "gstreamer1-plugins-good", "pipewire-gstreamer")
        Distro.Arch -> listOf("gstreamer", "gst-plugin-pipewire", "gst-plugins-good")
        Distro.Suse -> listOf("gstreamer-utils", "gstreamer-plugin-pipewire", "gstreamer-plugins-good")
        Distro.Other -> emptyList()
    }

    /** The portal part for the desktop in [currentDesktop], which does the dialogs and the sharing. */
    fun portalPackage(currentDesktop: String?): String {
        val parts = currentDesktop.orEmpty().split(':').map { it.trim().lowercase() }
        return when {
            parts.any { "gnome" in it || it == "ubuntu" || it == "pop" } -> "xdg-desktop-portal-gnome"
            parts.any { it == "kde" || "plasma" in it } -> "xdg-desktop-portal-kde"
            parts.any { it == "hyprland" } -> "xdg-desktop-portal-hyprland"
            else -> "xdg-desktop-portal-wlr"
        }
    }

    /** What's said where input is set up on Wayland: the portal's state, uinput, or how to set uinput up. */
    fun waylandInput(w: WaylandHost, how: String): List<HostHint> {
        val features = "the touchpad, keyboard, presentation remote, voice and scan typing, Deck shortcuts, and a Mac's mouse and keyboard"
        val retry = HintAction("Ask again", ACTION_PORTAL_INPUT)
        return when (w.route) {
            null, InputRoute.ROBOT -> emptyList()
            InputRoute.PORTAL -> when (w.input.phase) {
                PortalPhase.Waiting -> listOf(
                    HostHint(
                        "Waiting for you to allow remote control on this PC",
                        "A system window asks whether DyskLink may use the pointer and keyboard. Choose Allow or Share, and " +
                            "'Remember' if it's offered, so it's only asked once. It may be behind other windows.",
                        action = retry, tone = HintTone.Info,
                    ),
                )
                PortalPhase.Denied -> listOf(
                    HostHint(
                        "Remote control isn't allowed on this PC",
                        "It was turned down in the system window, so $features do nothing here. Ask again and choose Allow.",
                        action = retry,
                    ),
                )
                PortalPhase.Failed -> listOf(
                    HostHint(
                        "Remote control couldn't start",
                        (w.input.detail?.let { "$it " } ?: "") + "DyskLink asks again the next time a phone sends input, or now:",
                        action = retry,
                    ),
                )
                PortalPhase.Allowed -> listOf(
                    HostHint(
                        "Remote control is allowed",
                        "This PC uses Wayland and lets DyskLink use the pointer and keyboard. You can stop it from the desktop's top bar or menu.",
                        tone = HintTone.Ok,
                    ),
                )
                PortalPhase.Idle -> if (w.inputRemembered) emptyList() else listOf(
                    HostHint(
                        "This PC asks once",
                        "This PC uses Wayland: the first time a phone uses the touchpad or keyboard here, a system window asks you to " +
                            "allow remote control. Choose Allow on this PC.",
                        action = HintAction("Ask now", ACTION_PORTAL_INPUT), tone = HintTone.Info,
                    ),
                )
            }
            InputRoute.UINPUT -> listOf(
                HostHint(
                    "DyskLink uses its own keyboard and mouse",
                    "This desktop has no remote desktop portal, so DyskLink made a virtual keyboard and mouse (/dev/uinput). " +
                        "Text from the phone is pasted, and keys follow US key positions.",
                    tone = HintTone.Info,
                ),
            )
            InputRoute.NONE -> listOf(
                HostHint(
                    "Let DyskLink make a virtual keyboard and mouse",
                    "This PC uses Wayland without a remote desktop portal, and /dev/uinput isn't writable for you, so $features " +
                        "don't work. Run these once, then log out and back in. Members of the input group can read every keyboard, " +
                        "so only do this on your own PC. Or, as a last resort: ${how.replaceFirstChar { it.lowercase() }}",
                    UINPUT_RULE, moreCommands = listOf(UINPUT_GROUP),
                ),
            )
        }
    }

    /** What's said under "Let phones see this screen" on Wayland. */
    fun waylandScreen(w: WaylandHost, distro: Distro, currentDesktop: String?, how: String): List<HostHint> {
        if (w.screenCast == false) {
            val portal = portalPackage(currentDesktop)
            return listOf(
                HostHint(
                    "Showing this screen needs the screen sharing portal",
                    "This PC uses Wayland, where apps capture the screen through xdg-desktop-portal and your desktop's part of it " +
                        "($portal). Install them and log in again. If your desktop has no portal, as a last resort, ${how.replaceFirstChar { it.lowercase() }}",
                    installCommand(distro, listOf("xdg-desktop-portal", portal)),
                ),
            )
        }
        if (!w.gst.ready) {
            val packages = gstPackages(distro)
            val missing = if (!w.gst.launch) "GStreamer's gst-launch-1.0" else "GStreamer's PipeWire plugin (pipewiresrc)"
            return listOf(
                HostHint(
                    "Showing this screen needs GStreamer",
                    "On Wayland, DyskLink turns the shared screen into pictures with $missing, which isn't installed." +
                        if (packages.isEmpty()) " Install gst-launch-1.0, the GStreamer PipeWire plugin, and gst-plugins-good." else "",
                    installCommand(distro, packages),
                ),
            )
        }
        if (w.screenCast == null) return emptyList()
        return when (w.screen.phase) {
            PortalPhase.Waiting -> listOf(
                HostHint(
                    "Waiting for you to choose the screen to share",
                    "A system window on this PC asks which screen a phone may see. Pick it and choose Share, and 'Remember' if it's offered.",
                    tone = HintTone.Info,
                ),
            )
            PortalPhase.Denied -> listOf(
                HostHint(
                    "Sharing this screen wasn't allowed",
                    "It was turned down in the system window, and the phone was told. It's asked again the next time a phone opens this screen.",
                    action = HintAction("OK", ACTION_PORTAL_SCREEN),
                ),
            )
            PortalPhase.Failed -> listOf(
                HostHint(
                    "This screen couldn't be shared",
                    (w.screen.detail?.let { "$it " } ?: "") + "It's tried again the next time a phone opens this screen.",
                    action = HintAction("OK", ACTION_PORTAL_SCREEN),
                ),
            )
            PortalPhase.Idle -> if (w.screenRemembered) emptyList() else listOf(
                HostHint(
                    "This PC asks once",
                    "This PC uses Wayland: the first time a phone views this screen, a system window asks which screen to share. Choose it and Share on this PC.",
                    tone = HintTone.Info,
                ),
            )
            PortalPhase.Allowed -> emptyList()
        }
    }

    fun distro(osRelease: String): Distro {
        val ids = osRelease.lines().mapNotNull { line ->
            val key = line.substringBefore('=', "").trim()
            if (key == "ID" || key == "ID_LIKE") line.substringAfter('=').trim().trim('"', '\'').lowercase() else null
        }.flatMap { it.split(' ', ',') }.filter { it.isNotEmpty() }
        return when {
            ids.any { it in setOf("debian", "ubuntu", "linuxmint", "pop", "elementary", "raspbian", "kali", "zorin", "neon") } -> Distro.Debian
            ids.any { it in setOf("fedora", "rhel", "centos", "rocky", "almalinux", "ol", "amzn", "nobara") } -> Distro.Fedora
            ids.any { it in setOf("arch", "manjaro", "endeavouros", "garuda", "artix", "cachyos") } -> Distro.Arch
            ids.any { it == "suse" || it.startsWith("opensuse") || it == "sles" } -> Distro.Suse
            else -> Distro.Other
        }
    }

    fun loginDesktop(currentDesktop: String?): LoginDesktop {
        val parts = currentDesktop.orEmpty().split(':').map { it.trim().lowercase() }
        return when {
            parts.any { "gnome" in it || it == "ubuntu" || it == "pop" } -> LoginDesktop.Gnome
            parts.any { it == "kde" || "plasma" in it } -> LoginDesktop.Kde
            else -> LoginDesktop.Other
        }
    }

    fun isWayland(env: (String) -> String?): Boolean =
        Typing.desktop(isWindows = false, isMac = false, env = env) == Desktop.WAYLAND

    /** How to get an X11 session back, in the words of the login screen in front of the user. */
    fun x11How(desktop: LoginDesktop): String = when (desktop) {
        LoginDesktop.Gnome -> "Log out, click your name on the login screen, then the gear icon at the bottom right, and choose " +
            "'Ubuntu on Xorg' or 'GNOME on Xorg'. If there's no gear or no Xorg entry, this GNOME has no X11 session and these stay off."
        LoginDesktop.Kde -> "Log out and choose 'Plasma (X11)' in the session menu at the bottom left of the login screen."
        LoginDesktop.Other -> "Log out and choose your desktop's X11 or Xorg session on the login screen."
    }

    /** Where the session choice is, for the one card on the Overview. */
    fun x11Where(desktop: LoginDesktop): String? = when (desktop) {
        LoginDesktop.Gnome -> "On GNOME it's under the gear icon at the bottom right, after you click your name."
        LoginDesktop.Kde -> "On KDE Plasma it's the session menu at the bottom left: choose 'Plasma (X11)'."
        LoginDesktop.Other -> null
    }

    /** The command that installs [packages] on [distro], or null where the package tool isn't known. */
    fun installCommand(distro: Distro, packages: List<String>): String? =
        distro.install?.takeIf { packages.isNotEmpty() }?.let { "$it ${packages.distinct().joinToString(" ")}" }

    /** The package with `pactl` and `parec`. */
    fun pulseTools(distro: Distro): String = if (distro == Distro.Arch) "libpulse" else "pulseaudio-utils"

    /** What the phone is told when this PC's sound can't be recorded because neither tool is installed. */
    fun soundMissing(distro: Distro): String =
        "This PC needs parec or pw-record to send its sound." +
            (installCommand(distro, listOf(pulseTools(distro)))?.let { " Install it on the PC: $it" } ?: " Install pulseaudio-utils or pipewire on the PC.")

    /** Where to add a keyboard shortcut that runs a command, on this desktop. */
    fun shortcutWhere(desktop: LoginDesktop): String = when (desktop) {
        LoginDesktop.Gnome -> "In Settings, Keyboard, View and Customize Shortcuts, Custom Shortcuts, add one"
        LoginDesktop.Kde -> "In System Settings, Keyboard, Shortcuts, choose Add New, then Command or Script, and add one"
        LoginDesktop.Other -> "In your desktop's keyboard settings (on Sway or Hyprland, a bind line in its config), add a shortcut"
    }

    /**
     * What Clipboard History needs from outside DyskLink, under its switch on the Tools page: wl-clipboard on
     * Wayland, a desktop shortcut where nothing can grab one, and a way to press Ctrl+V for Enter to paste.
     */
    fun clipboardHints(
        mode: ClipWatchMode,
        hotkey: HotkeyStatus,
        combo: String,
        command: String,
        canPaste: Boolean,
        distro: Distro = Distro.Other,
        currentDesktop: String? = null,
    ): List<HostHint> = buildList {
        when (mode) {
            ClipWatchMode.PollNoWlPaste -> add(
                HostHint(
                    "Install wl-clipboard for the clipboard history",
                    "This PC uses Wayland. Without wl-paste, DyskLink only sees copies the desktop passes to older X11 apps, " +
                        "looked at twice a second, and can't tell which copies a password manager marked as secret.",
                    installCommand(distro, listOf("wl-clipboard")),
                ),
            )
            ClipWatchMode.PollWlUnsupported -> add(
                HostHint(
                    "This desktop doesn't let apps watch the clipboard",
                    "wl-paste can't watch here (GNOME has no data-control support), so DyskLink looks at the clipboard twice " +
                        "a second through XWayland. Most copies are seen; a copy replaced within half a second may be missed.",
                    tone = HintTone.Info,
                ),
            )
            else -> Unit
        }
        val bind = "${shortcutWhere(loginDesktop(currentDesktop))} that runs this command, with the keys you like, such as $combo:"
        when (hotkey.backend) {
            HotkeyBackend.Command -> add(
                HostHint(
                    "Add a keyboard shortcut for the clipboard history",
                    "This desktop doesn't let apps take a global shortcut. $bind",
                    command, tone = HintTone.Info,
                ),
            )
            HotkeyBackend.Portal -> when (hotkey.phase) {
                HotkeyPhase.Waiting -> add(
                    HostHint(
                        "Waiting for you to allow the shortcut",
                        "A system window asks whether DyskLink may use $combo. It may be behind other windows.", tone = HintTone.Info,
                    ),
                )
                HotkeyPhase.Taken, HotkeyPhase.Failed -> add(
                    HostHint(
                        "The shortcut wasn't set up",
                        (hotkey.detail?.let { "$it " } ?: "") + "Ask again, or ${bind.replaceFirstChar { it.lowercase() }}",
                        command, action = HintAction("Ask again", ACTION_CLIPBOARD_HOTKEY),
                    ),
                )
                else -> Unit
            }
            HotkeyBackend.Windows, HotkeyBackend.X11 -> if (hotkey.phase == HotkeyPhase.Taken || hotkey.phase == HotkeyPhase.Failed) add(
                HostHint(
                    "$combo couldn't be used",
                    (hotkey.detail?.let { "$it " } ?: "") + "Open the history from the tray menu, or close the other app and try again.",
                    action = HintAction("Try again", ACTION_CLIPBOARD_HOTKEY),
                ),
            )
        }
        if (!canPaste) add(
            HostHint(
                "Enter copies, but can't paste by itself",
                "DyskLink can't press keys in other apps on this PC (see Phones can control this PC in Settings), so choosing " +
                    "an item puts it on the clipboard and you press Ctrl+V.",
                tone = HintTone.Info,
            ),
        )
    }

    const val ACTION_CLIPBOARD_HOTKEY = "clipboard-hotkey"
    const val ACTION_LAUNCHER_HOTKEY = "launcher-hotkey"
    const val ACTION_TIDY_HOTKEY = "tidy-hotkey"

    /**
     * What the Launcher needs from outside DyskLink, under its switch on the Tools page: a desktop shortcut where no
     * app may take one, the keys being free (Alt+Space is the window menu on Windows and GNOME, KRunner on KDE), and a
     * way to type emoji into other apps.
     */
    fun launcherHints(
        hotkey: HotkeyStatus,
        combo: HotkeyCombo,
        command: String,
        windows: Boolean,
        currentDesktop: String?,
        canType: Boolean,
        distro: Distro = Distro.Other,
    ): List<HostHint> = buildList {
        val desktop = loginDesktop(currentDesktop)
        val bind = "${shortcutWhere(desktop)} that runs this command, with the keys you like, such as ${combo.label}:"
        val altSpace = combo.alt && !combo.ctrl && !combo.shift && !combo.meta && combo.key == "Space"
        when (hotkey.backend) {
            HotkeyBackend.Command -> add(HostHint("Add a keyboard shortcut for the Launcher", "This desktop doesn't let apps take a global shortcut. $bind", command, tone = HintTone.Info))
            HotkeyBackend.Portal -> when (hotkey.phase) {
                HotkeyPhase.Waiting -> add(HostHint("Waiting for you to allow the shortcut", "A system window asks whether DyskLink may use ${combo.label}. It may be behind other windows.", tone = HintTone.Info))
                HotkeyPhase.Taken, HotkeyPhase.Failed -> add(
                    HostHint(
                        "The Launcher's shortcut wasn't set up", (hotkey.detail?.let { "$it " } ?: "") + "Ask again, or ${bind.replaceFirstChar { it.lowercase() }}",
                        command, action = HintAction("Ask again", ACTION_LAUNCHER_HOTKEY),
                    ),
                )
                else -> Unit
            }
            HotkeyBackend.Windows, HotkeyBackend.X11 -> if (hotkey.phase == HotkeyPhase.Taken || hotkey.phase == HotkeyPhase.Failed) add(
                HostHint(
                    "${combo.label} couldn't be used", (hotkey.detail?.let { "$it " } ?: "") + "Choose another shortcut below, or close the other app and try again.",
                    action = HintAction("Try again", ACTION_LAUNCHER_HOTKEY),
                ),
            )
        }
        if (windows && altSpace && hotkey.phase == HotkeyPhase.Ready) add(
            HostHint(
                "Alt+Space now opens the Launcher",
                "Windows used it for a window's menu (Restore, Move, Size, Close); that menu is still on a right-click of the title bar. Choose Ctrl+Space to keep Alt+Space as it was.",
                tone = HintTone.Info,
            ),
        )
        if (!windows && hotkey.backend == HotkeyBackend.X11 && altSpace) when (desktop) {
            LoginDesktop.Gnome -> add(
                HostHint(
                    "GNOME uses Alt+Space for the window menu",
                    "If Alt+Space still opens that menu, the desktop keeps the key. Free it with this command, or choose Ctrl+Space:",
                    "gsettings set org.gnome.desktop.wm.keybindings activate-window-menu \"[]\"", tone = HintTone.Info,
                ),
            )
            LoginDesktop.Kde -> add(
                HostHint(
                    "KDE uses Alt+Space for KRunner",
                    "If KRunner opens instead, remove Alt+Space from it in System Settings, Keyboard, Shortcuts, KRunner, or choose Ctrl+Space.",
                    tone = HintTone.Info,
                ),
            )
            LoginDesktop.Other -> Unit
        }
        if (!canType) add(
            HostHint(
                "Enter copies emoji instead of typing them",
                "DyskLink can't press keys in other apps on this PC (see Phones can control this PC in Settings), so an emoji goes on the clipboard and you press Ctrl+V.",
                tone = HintTone.Info,
            ),
        )
    }

    /**
     * What Window tidy needs: on Wayland it can't work at all (the desktop's own tiling keys are listed instead); on
     * X11 a window manager that moves windows for other apps; shortcuts no other app or the desktop already holds.
     */
    fun windowTidyHints(
        windows: Boolean,
        wayland: Boolean,
        currentDesktop: String?,
        problem: String?,
        hotkey: HotkeyStatus,
        mods: TidyMods,
    ): List<HostHint> = buildList {
        val desktop = loginDesktop(currentDesktop)
        if (wayland) {
            add(
                HostHint(
                    "Wayland doesn't let apps move other apps' windows",
                    when (desktop) {
                        LoginDesktop.Gnome -> "GNOME has its own keys: Super+Left and Super+Right for halves, Super+Up to fill the screen, Super+Down to restore, " +
                            "and Super+Shift+Left or Right to move a window to the other monitor. Dragging a window to a screen edge snaps it too. For thirds and saved " +
                            "layouts, log in to the X11 session (GNOME on Xorg) and turn this on there."
                        LoginDesktop.Kde -> "KDE Plasma has its own keys: Meta+Left, Right, Up and Down for halves (one after the other for quarters, such as " +
                            "Meta+Up then Meta+Left), Meta+PgUp to fill the screen, and Meta+T for custom tiles. To bring windows back to a place each time, use Window " +
                            "Rules in System Settings, Window Management. For DyskLink's thirds and saved layouts, log in to the Plasma (X11) session."
                        LoginDesktop.Other -> "Your compositor arranges windows itself: on Sway and Hyprland, with the keys in its config. For DyskLink's halves, " +
                            "thirds and saved layouts, log in to an X11 session."
                    },
                    tone = HintTone.Info,
                ),
            )
            return@buildList
        }
        problem?.let { add(HostHint("Windows can't be moved here", "$it Try another window manager, or a desktop such as GNOME, KDE Plasma, Xfce or Cinnamon.")) }
        if (hotkey.taken.isNotEmpty() || hotkey.phase == HotkeyPhase.Taken || hotkey.phase == HotkeyPhase.Failed) add(
            HostHint(
                if (hotkey.taken.size == 1) "${hotkey.taken[0]} couldn't be used" else "Some shortcuts couldn't be used",
                (hotkey.detail?.let { "$it " } ?: "") + "Choose ${if (mods == TidyMods.CtrlAlt) "Ctrl+Alt+Shift" else "Ctrl+Alt"} below, or close the other app and try again.",
                action = HintAction("Try again", ACTION_TIDY_HOTKEY),
            ),
        )
        if (windows && mods == TidyMods.CtrlAlt) add(
            HostHint(
                "On keyboards with AltGr, Ctrl+Alt types letters",
                "German, Polish and other layouts type characters such as € with Ctrl+Alt+E. If one of those stops working, choose Ctrl+Alt+Shift. " +
                    "Windows' own Win+arrow keys keep working beside these.",
                tone = HintTone.Info,
            ),
        )
        if (!windows && mods == TidyMods.CtrlAlt && desktop != LoginDesktop.Other) add(
            HostHint(
                "The desktop may keep some of these keys",
                (if (desktop == LoginDesktop.Gnome) "GNOME switches workspaces with Ctrl+Alt+Left and Right" else "KDE uses some Ctrl+Alt keys") +
                    ", and X can't tell DyskLink when a key is held by another app. If a shortcut does nothing, choose Ctrl+Alt+Shift.",
                tone = HintTone.Info,
            ),
        )
    }


    fun hints(host: HostEnv): HostHints {
        if (!host.linux && !host.windows) return HostHints()
        if (host.windows) return windowsHints(host)
        val distro = distro(host.osRelease)
        val wayland = isWayland(host.env)
        val desktop = loginDesktop(host.env("XDG_CURRENT_DESKTOP"))
        val how = x11How(desktop)
        fun install(vararg packages: String) = installCommand(distro, packages.toList())

        val control = mutableListOf<HostHint>()
        val remote = mutableListOf<HostHint>()
        val screenOut = mutableListOf<HostHint>()
        val overview = mutableListOf<HostHint>()
        if (wayland) {
            val w = host.wayland ?: WaylandHost()
            val input = waylandInput(w, how)
            control += input
            remote += input
            screenOut += waylandScreen(w, distro, host.env("XDG_CURRENT_DESKTOP"), how)
            val missing = listOfNotNull(
                "the touchpad, keyboard and remotes (a virtual keyboard and mouse)".takeIf { w.route == InputRoute.NONE },
                "showing this screen (the screen sharing portal)".takeIf { w.screenCast == false },
                "showing this screen (GStreamer)".takeIf { w.screenCast != false && !w.gst.ready },
            )
            if (missing.isNotEmpty()) {
                overview += HostHint(
                    "This PC uses Wayland",
                    "Some things need a one-time setup on this PC: ${missing.joinToString(" and ")}. Settings shows the commands." +
                        " As a last resort, an X11 session has everything." + (x11Where(desktop)?.let { " $it" } ?: ""),
                )
            }
        } else if (!host.robot) {
            val display = HostHint(
                "DyskLink can't move the pointer here",
                "Moving the pointer and typing need the X11 XTEST extension, which this session doesn't offer. " +
                    "Log in to a regular desktop session on this PC.",
            )
            control += display
            remote += display
        }

        // The media remote: playerctl for play, pause and skipping; pactl for the volume; xdotool presses media keys instead.
        val playerctl = host.onPath("playerctl")
        val pactl = host.onPath("pactl")
        val xdotool = !wayland && host.onPath("xdotool")
        if (!playerctl || !pactl) {
            val lost = when {
                !playerctl && !pactl -> "play, pause, skip and volume"
                !playerctl -> "play, pause and skip"
                else -> "the volume buttons"
            }
            val it = if (!playerctl && !pactl) "them" else "it"
            val missing = listOfNotNull("playerctl".takeIf { !playerctl }, "pactl".takeIf { !pactl }).joinToString(" and ")
            control += HostHint(
                "The media remote needs $missing",
                if (xdotool) "Without $it, $lost from the phone press this PC's media keys, which not every player answers."
                else "Without $it, $lost from the phone do nothing here.",
                install(*listOfNotNull("playerctl".takeIf { !playerctl }, pulseTools(distro).takeIf { !pactl }).toTypedArray()),
            )
        }
        // Typing: xdotool, else spare key codes through Xlib; with neither, text is pasted.
        if (!wayland && host.robot && !host.onPath("xdotool") && !host.x11Remap) {
            control += HostHint("Typing needs xdotool", "Text from the phone is pasted here, which some apps don't take.", install("xdotool"))
        }

        val soundOut = mutableListOf<HostHint>()
        if (!host.onPath("parec") && !host.onPath("pw-record")) {
            soundOut += HostHint(
                "Phones can't hear this PC yet",
                "Recording what plays here needs parec or pw-record, and neither is installed." +
                    if (distro == Distro.Debian) " pipewire-bin, which has pw-record, works too." else "",
                install(pulseTools(distro)),
            )
        }

        val popups = mutableListOf<HostHint>()
        if (host.notificationService == false) {
            popups += HostHint(
                "No notification service on this desktop",
                "Popups from DyskLink, such as phone notifications, calls, and a phone viewing this screen, can't show. " +
                    "Install a notification daemon such as dunst and start it with your session.",
                install("dunst"),
            )
        }

        val firewall = when {
            host.onPath("ufw") -> HostHint(
                "Phone can't find this PC?",
                "If the ufw firewall is on, it blocks phones. Allow DyskLink's port and discovery:",
                "sudo ufw allow ${host.port}/tcp && sudo ufw allow 5353/udp",
            )
            host.onPath("firewall-cmd") -> HostHint(
                "Phone can't find this PC?",
                "If firewalld is on, it blocks phones. Allow DyskLink's port and discovery:",
                "sudo firewall-cmd --permanent --add-port=${host.port}/tcp --add-service=mdns && sudo firewall-cmd --reload",
            )
            else -> null
        }

        return HostHints(
            control = control, screenOut = screenOut, remote = remote, soundOut = soundOut, popups = popups, overview = overview,
            connectLater = listOfNotNull(firewall), obs = if (wayland) ObsCapture.PipeWire else ObsCapture.Xcomposite,
        )
    }

    private fun windowsHints(host: HostEnv): HostHints {
        val public = if (host.publicNetwork == true) listOf(
            HostHint(
                "This network is set to Public",
                "Windows blocks phones from reaching DyskLink on Public networks. Make it Private: Settings, Network & internet, " +
                    "your Wi-Fi or Ethernet, then Network profile type: Private.",
                link = HintLink("Open network settings", "ms-settings:network-status"),
            ),
        ) else emptyList()
        val firewall = HostHint(
            "Phone can't find this PC?",
            "Windows Firewall may be blocking DyskLink. In Windows Security, Firewall & network protection, Allow an app through " +
                "firewall: tick Private for DyskLink. Then try again on the phone.",
            link = HintLink("Open firewall settings", "windowsdefender://network"),
        )
        return HostHints(connectNow = public, connectLater = listOf(firewall), obs = ObsCapture.Windows)
    }

    /** The OBS source's name, for the line in the camera window. */
    fun obsSource(capture: ObsCapture): String = when (capture) {
        ObsCapture.Windows -> "Window Capture"
        ObsCapture.PipeWire -> "Screen Capture (PipeWire)"
        ObsCapture.Xcomposite -> "Window Capture (Xcomposite)"
    }

    /** OBS captures the camera window; it isn't a webcam. */
    fun obsHint(capture: ObsCapture, window: String): HostHint {
        val source = when (capture) {
            ObsCapture.Windows -> "Sources, +, Window Capture, pick '$window' and set Capture method to 'Windows 10 (1903 and up)'."
            ObsCapture.PipeWire -> "Sources, +, Screen Capture (PipeWire), then choose the '$window' window in the picker."
            ObsCapture.Xcomposite -> "Sources, +, Window Capture (Xcomposite), and pick '$window'."
        }
        val device = if (capture == ObsCapture.Windows) "Video Capture Device" else "Video Capture Device (V4L2)"
        return HostHint(
            "Use it in OBS",
            "In OBS: $source Clean view in the camera window leaves only the picture. " +
                "Or turn on Use as webcam: then OBS can add '${Loopback.LABEL}' as a $device instead, and video call apps pick it directly.",
        )
    }

    // The phone camera as a webcam: v4l2loopback on Linux, softcam on Windows.

    const val ACTION_WEBCAM_INSTALL = "webcam-install"
    const val ACTION_WEBCAM_REMOVE = "webcam-remove"

    /** The v4l2loopback package of [distro]; null where it isn't known. */
    fun loopbackPackage(distro: Distro): String? = when (distro) {
        Distro.Debian, Distro.Arch -> "v4l2loopback-dkms"
        Distro.Fedora -> "v4l2loopback"
        Distro.Suse -> "v4l2loopback-kmp-default"
        Distro.Other -> null
    }

    fun loopbackInstall(distro: Distro): HostHint {
        val what = "Use as webcam makes '${Loopback.LABEL}' with v4l2loopback, a kernel module."
        val text = when (distro) {
            Distro.Debian -> "$what Install it:"
            Distro.Fedora -> "$what On Fedora it comes from RPM Fusion: enable RPM Fusion Free (rpmfusion.org) first, then install it:"
            Distro.Arch -> "$what Install it, with the headers of your kernel (such as linux-headers):"
            Distro.Suse -> "$what Install it:"
            Distro.Other -> "$what Install it with your package manager: v4l2loopback-dkms on Debian, Ubuntu and Arch " +
                "(sudo apt install v4l2loopback-dkms, sudo pacman -S v4l2loopback-dkms), v4l2loopback from RPM Fusion on Fedora (sudo dnf install v4l2loopback)."
        }
        return HostHint("Install v4l2loopback", text, installCommand(distro, listOfNotNull(loopbackPackage(distro))))
    }

    val loopbackLoad = HostHint(
        "Load the DyskLink Camera device",
        "This makes /dev/video10 called '${Loopback.LABEL}' until the next restart. exclusive_caps=1 lets Chrome, Zoom and Teams see it.",
        Loopback.MODPROBE,
    )

    val loopbackPersist = HostHint(
        "Keep it after a restart",
        "Writes /etc/modules-load.d/dysklink.conf (${Loopback.MODULES_LOAD}) and /etc/modprobe.d/dysklink.conf " +
            "(${Loopback.MODPROBE_OPTIONS}), so the device is there at every start.",
        Loopback.PERSIST,
    )

    /** Secure Boot only loads signed modules; DKMS signs with its own key, which must be enrolled once. */
    fun secureBootHint(distro: Distro) = HostHint(
        "Secure Boot is on",
        "The kernel only loads signed modules. If modprobe says 'Key was rejected by service', enroll the key the module was " +
            "signed with: run this, choose a one-time password, restart, pick Enroll MOK on the blue screen and type the password.",
        if (distro == Distro.Fedora) "sudo mokutil --import /etc/pki/akmods/certs/public_key.der"
        else "sudo mokutil --import \$(ls /var/lib/shim-signed/mok/MOK.der /var/lib/dkms/mok.pub 2>/dev/null | head -n1)",
    )

    /** Where the webcam goes on Linux, or what to do first. */
    fun linuxWebcam(probe: LoopbackProbe, distro: Distro): WebcamPlan {
        val device = Loopback.pick(probe.devices)
        if (device != null) {
            return WebcamPlan(device.node, listOf(loopbackPersist).takeIf { probe.persistent == false }.orEmpty(), label = device.name.ifBlank { device.node })
        }
        val hints = mutableListOf<HostHint>()
        when {
            probe.devices.isNotEmpty() -> hints += HostHint(
                "DyskLink can't write to ${probe.devices.joinToString(", ") { it.node }}",
                "Video devices belong to the video group. Add yourself to it, then log out and in again.",
                "sudo usermod -aG video \$USER",
            )
            probe.loaded -> hints += HostHint(
                "v4l2loopback has no device",
                "It's loaded without a camera. Load it again with one for DyskLink:",
                "sudo modprobe -r v4l2loopback && ${Loopback.MODPROBE}",
            )
            else -> {
                if (probe.installed != true) hints += loopbackInstall(distro)
                hints += loopbackLoad
                if (probe.secureBoot == true) hints += secureBootHint(distro)
            }
        }
        if (probe.persistent != true && probe.devices.isEmpty()) hints += loopbackPersist
        return WebcamPlan(null, hints)
    }

    private val webcamRemove = HostHint(
        "DyskLink Camera is installed",
        "Uninstalling DyskLink doesn't remove it from Windows. To take it away, click Remove before you uninstall; " +
            "Windows asks for administrator permission.",
        action = HintAction("Remove DyskLink Camera", ACTION_WEBCAM_REMOVE),
    )

    /** Where the webcam goes on Windows ([dll] is softcam.dll), or what to do first; [registered] is its path in the registry. */
    fun windowsWebcam(dll: String?, registered: String?): WebcamPlan {
        if (dll == null) {
            return WebcamPlan(
                null,
                listOf(
                    HostHint(
                        "DyskLink Camera isn't in this copy",
                        "It comes with the DyskLink installer for Windows. Install DyskLink from the downloads page, then try again.",
                        link = HintLink("Open downloads", DesktopUpdates.PAGE_URL),
                    ),
                ),
            )
        }
        val install = "Video apps such as Zoom, Teams, Meet in the browser and OBS (Video Capture Device) then list " +
            "'${Softcam.NAME}'. Windows asks for administrator permission once. Restart a video app that was already open."
        return when {
            registered == null -> WebcamPlan(
                null, listOf(HostHint("Install DyskLink Camera", install, action = HintAction("Install DyskLink Camera", ACTION_WEBCAM_INSTALL))),
            )
            !Softcam.samePath(registered, dll) -> WebcamPlan(
                null,
                listOf(
                    HostHint(
                        "DyskLink Camera points to another copy",
                        "It was installed from $registered. Install it again from this copy of DyskLink. $install",
                        action = HintAction("Install again", ACTION_WEBCAM_INSTALL),
                    ),
                ),
                settings = listOf(webcamRemove),
            )
            else -> WebcamPlan(dll, settings = listOf(webcamRemove), label = Softcam.NAME)
        }
    }

    /** Another app holds the device. */
    fun webcamBusy(windows: Boolean, device: String, problem: String): HostHint = if (windows) {
        HostHint("DyskLink Camera is busy", "Another app is already sending to it, such as a second DyskLink. Close it, and DyskLink tries again.")
    } else {
        HostHint(
            "$device is busy",
            "Another app is sending to it, such as OBS with Start Virtual Camera. Stop it there, or make a second device so both work:",
            "sudo modprobe -r v4l2loopback && sudo modprobe v4l2loopback devices=2 video_nr=10,11 " +
                "card_label=\"${Loopback.LABEL}\",\"OBS Virtual Camera\" exclusive_caps=1,1",
        )
    }

    /** From `Get-NetConnectionProfile`'s NetworkCategory lines: true when any connected network is Public. */
    fun anyPublic(output: String): Boolean? {
        val values = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (values.isEmpty()) return null
        return values.any { it.equals("Public", ignoreCase = true) }
    }

    /** This PC as the checks see it. Reads the PATH, /etc/os-release and the environment; cheap enough for every focus. */
    fun current(notificationService: Boolean?, publicNetwork: Boolean?, robot: Boolean, x11Remap: Boolean, wayland: WaylandHost? = null): HostEnv {
        val path = System.getenv("PATH").orEmpty()
        // Firewall tools live in sbin, which isn't on every user's PATH.
        val withSbin = "$path${File.pathSeparator}/usr/sbin${File.pathSeparator}/sbin"
        return HostEnv(
            windows = Platform.isWindows,
            linux = !Platform.isWindows,
            env = System::getenv,
            onPath = { name -> Typing.onPath(name, if (name == "ufw" || name == "firewall-cmd") withSbin else path) },
            osRelease = if (Platform.isWindows) "" else runCatching { File("/etc/os-release").readText() }.getOrDefault(""),
            notificationService = notificationService,
            publicNetwork = publicNetwork,
            robot = robot,
            x11Remap = x11Remap,
            wayland = wayland,
            port = Platform.port,
        )
    }
}
