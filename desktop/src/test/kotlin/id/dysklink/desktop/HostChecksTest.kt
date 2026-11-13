package id.dysklink.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostChecksTest {
    private val allTools = setOf("playerctl", "pactl", "parec", "pw-record", "xdotool")

    private fun linux(
        env: Map<String, String> = mapOf("XDG_SESSION_TYPE" to "x11"),
        tools: Set<String> = allTools,
        osRelease: String = "ID=ubuntu\nID_LIKE=debian\n",
        notifications: Boolean? = true,
        robot: Boolean = true,
        x11Remap: Boolean = true,
        wayland: WaylandHost? = null,
    ) = HostChecks.hints(HostEnv(windows = false, linux = true, env = env::get, onPath = { it in tools }, osRelease = osRelease, notificationService = notifications, robot = robot, x11Remap = x11Remap, wayland = wayland))

    private val gnome = mapOf("XDG_SESSION_TYPE" to "wayland", "XDG_CURRENT_DESKTOP" to "ubuntu:GNOME")
    private val ready = WaylandHost(route = InputRoute.PORTAL, inputRemembered = true, screenCast = true, gst = GstCheck(true, true, true), screenRemembered = true)

    @Test
    fun distroFamiliesFromOsRelease() {
        assertEquals(Distro.Debian, HostChecks.distro("NAME=\"Ubuntu\"\nID=ubuntu\nID_LIKE=debian\n"))
        assertEquals(Distro.Debian, HostChecks.distro("ID=linuxmint\nID_LIKE=\"ubuntu debian\"\n"))
        assertEquals(Distro.Fedora, HostChecks.distro("ID=fedora\n"))
        assertEquals(Distro.Fedora, HostChecks.distro("ID=\"rocky\"\nID_LIKE=\"rhel centos fedora\"\n"))
        assertEquals(Distro.Arch, HostChecks.distro("ID=manjaro\nID_LIKE=arch\n"))
        assertEquals(Distro.Arch, HostChecks.distro("ID=endeavouros\n"))
        assertEquals(Distro.Suse, HostChecks.distro("ID=\"opensuse-tumbleweed\"\nID_LIKE=\"opensuse suse\"\n"))
        assertEquals(Distro.Other, HostChecks.distro("ID=nixos\n"))
        assertEquals(Distro.Other, HostChecks.distro(""))
    }

    @Test
    fun installCommandsByDistro() {
        assertEquals("sudo apt install pulseaudio-utils", HostChecks.installCommand(Distro.Debian, listOf(HostChecks.pulseTools(Distro.Debian))))
        assertEquals("sudo dnf install pulseaudio-utils", HostChecks.installCommand(Distro.Fedora, listOf(HostChecks.pulseTools(Distro.Fedora))))
        assertEquals("sudo pacman -S libpulse", HostChecks.installCommand(Distro.Arch, listOf(HostChecks.pulseTools(Distro.Arch))))
        assertEquals("sudo zypper install xdotool", HostChecks.installCommand(Distro.Suse, listOf("xdotool")))
        assertNull(HostChecks.installCommand(Distro.Other, listOf("xdotool")))
        assertNull(HostChecks.installCommand(Distro.Debian, emptyList()))
    }

    @Test
    fun waylandWithEverythingSetUpShowsNothingToFix() {
        val hints = linux(env = gnome, wayland = ready)
        assertTrue(hints.control.isEmpty() && hints.remote.isEmpty() && hints.screenOut.isEmpty() && hints.overview.isEmpty())
        assertEquals(ObsCapture.PipeWire, hints.obs)
        // Still being checked: nothing yet either.
        assertTrue(linux(env = gnome, wayland = WaylandHost()).control.isEmpty())
    }

    @Test
    fun portalStatesForInput() {
        fun control(w: WaylandHost) = linux(env = gnome, wayland = w).control.single()
        val first = control(ready.copy(inputRemembered = false))
        assertEquals("This PC asks once", first.title)
        assertEquals(HostChecks.ACTION_PORTAL_INPUT, first.action?.id)
        assertEquals(HintTone.Info, first.tone)
        val waiting = control(ready.copy(input = PortalStatus(PortalPhase.Waiting)))
        assertEquals("Waiting for you to allow remote control on this PC", waiting.title)
        assertEquals("Ask again", waiting.action?.label)
        val denied = control(ready.copy(input = PortalStatus(PortalPhase.Denied)))
        assertEquals("Remote control isn't allowed on this PC", denied.title)
        assertEquals(HintTone.Warn, denied.tone)
        assertEquals(HostChecks.ACTION_PORTAL_INPUT, denied.action?.id)
        val failed = control(ready.copy(input = PortalStatus(PortalPhase.Failed, "No answer from the system dialog.")))
        assertTrue(failed.text.startsWith("No answer from the system dialog."))
        assertEquals(HintTone.Ok, control(ready.copy(input = PortalStatus(PortalPhase.Allowed))).tone)
        // The same under a Mac's mouse and keyboard.
        assertEquals(denied, linux(env = gnome, wayland = ready.copy(input = PortalStatus(PortalPhase.Denied))).remote.single())
        // None of it calls for an X11 session any more.
        assertTrue(listOf(first, waiting, denied, failed).none { "Xorg" in it.text })
    }

    @Test
    fun noPortalUsesUinputOrShowsTheUdevRule() {
        val uinput = linux(env = gnome, wayland = ready.copy(route = InputRoute.UINPUT)).control.single()
        assertTrue("/dev/uinput" in uinput.text && uinput.command == null)
        val sway = mapOf("XDG_SESSION_TYPE" to "wayland", "XDG_CURRENT_DESKTOP" to "sway")
        val none = linux(env = sway, osRelease = "ID=arch\n", wayland = ready.copy(route = InputRoute.NONE))
        val hint = none.control.single()
        assertEquals("Let DyskLink make a virtual keyboard and mouse", hint.title)
        assertEquals(
            "echo 'KERNEL==\"uinput\", GROUP=\"input\", MODE=\"0660\", OPTIONS+=\"static_node=uinput\"' | sudo tee /etc/udev/rules.d/60-dysklink-uinput.rules" +
                " && sudo modprobe uinput && sudo udevadm control --reload-rules && sudo udevadm trigger --sysname-match=uinput",
            hint.command,
        )
        assertEquals(listOf("sudo usermod -aG input \$USER"), hint.moreCommands)
        assertTrue("log out and back in" in hint.text)
        // X11 only as the last resort, in the desktop's words.
        assertTrue("last resort" in hint.text && "your desktop's X11 or Xorg session" in hint.text)
        assertTrue("virtual keyboard and mouse" in none.overview.single().text)
        assertEquals(hint, none.remote.single())
    }

    @Test
    fun screenNeedsThePortalAndGstreamer() {
        val noCast = linux(env = gnome, wayland = ready.copy(screenCast = false))
        assertEquals("sudo apt install xdg-desktop-portal xdg-desktop-portal-gnome", noCast.screenOut.single().command)
        assertTrue("screen sharing portal" in noCast.overview.single().text)
        val kde = linux(env = mapOf("XDG_SESSION_TYPE" to "wayland", "XDG_CURRENT_DESKTOP" to "KDE"), osRelease = "ID=fedora\n", wayland = ready.copy(screenCast = false))
        assertEquals("sudo dnf install xdg-desktop-portal xdg-desktop-portal-kde", kde.screenOut.single().command)
        assertTrue("Plasma (X11)" in kde.screenOut.single().text)
        assertEquals("xdg-desktop-portal-wlr", HostChecks.portalPackage("sway"))
        assertEquals("xdg-desktop-portal-hyprland", HostChecks.portalPackage("Hyprland"))

        fun gst(distro: String, check: GstCheck = GstCheck()) = linux(env = gnome, osRelease = distro, wayland = ready.copy(gst = check)).screenOut.single()
        assertEquals("sudo apt install gstreamer1.0-tools gstreamer1.0-pipewire gstreamer1.0-plugins-good", gst("ID=ubuntu\n").command)
        assertEquals("sudo dnf install gstreamer1 gstreamer1-plugins-good pipewire-gstreamer", gst("ID=fedora\n").command)
        assertEquals("sudo pacman -S gstreamer gst-plugin-pipewire gst-plugins-good", gst("ID=arch\n").command)
        assertEquals("sudo zypper install gstreamer-utils gstreamer-plugin-pipewire gstreamer-plugins-good", gst("ID=opensuse-tumbleweed\n").command)
        assertTrue("pipewiresrc" in gst("ID=ubuntu\n", GstCheck(launch = true)).text)
        val other = gst("ID=nixos\n")
        assertNull(other.command)
        assertTrue("gst-plugins-good" in other.text)
        assertTrue("GStreamer" in linux(env = gnome, wayland = ready.copy(gst = GstCheck())).overview.single().text)
    }

    @Test
    fun portalStatesForTheScreen() {
        fun screen(w: WaylandHost) = linux(env = gnome, wayland = w).screenOut
        assertTrue(screen(ready).isEmpty())
        assertEquals("This PC asks once", screen(ready.copy(screenRemembered = false)).single().title)
        assertEquals("Waiting for you to choose the screen to share", screen(ready.copy(screen = PortalStatus(PortalPhase.Waiting))).single().title)
        val denied = screen(ready.copy(screen = PortalStatus(PortalPhase.Denied))).single()
        assertEquals("Sharing this screen wasn't allowed", denied.title)
        assertEquals(HostChecks.ACTION_PORTAL_SCREEN, denied.action?.id)
        assertTrue(screen(ready.copy(screen = PortalStatus(PortalPhase.Allowed))).isEmpty())
    }

    @Test
    fun waylandWordingPerDesktop() {
        val kde = linux(env = mapOf("WAYLAND_DISPLAY" to "wayland-0", "XDG_CURRENT_DESKTOP" to "KDE"), wayland = ready.copy(route = InputRoute.NONE))
        assertTrue("Plasma (X11)" in kde.control.first().text)
        assertTrue("session menu" in kde.overview.single().text)
        val sway = linux(env = mapOf("XDG_SESSION_TYPE" to "wayland", "XDG_CURRENT_DESKTOP" to "sway"), wayland = ready.copy(route = InputRoute.NONE))
        assertTrue("your desktop's X11 or Xorg session" in sway.control.first().text)
        assertEquals(LoginDesktop.Gnome, HostChecks.loginDesktop("ubuntu:GNOME"))
        assertEquals(LoginDesktop.Gnome, HostChecks.loginDesktop("pop:GNOME"))
        assertEquals(LoginDesktop.Kde, HostChecks.loginDesktop("KDE"))
        assertEquals(LoginDesktop.Other, HostChecks.loginDesktop(null))
    }

    @Test
    fun nothingShowsOnACompleteX11Pc() {
        val hints = linux()
        assertEquals(HostHints(obs = ObsCapture.Xcomposite), hints)
    }

    @Test
    fun missingSoundToolsGiveTheDistroCommand() {
        val debian = linux(tools = allTools - "parec" - "pw-record").soundOut.single()
        assertEquals("sudo apt install pulseaudio-utils", debian.command)
        assertTrue("pipewire-bin" in debian.text)
        assertEquals("sudo pacman -S libpulse", linux(tools = setOf("playerctl", "pactl"), osRelease = "ID=arch\n").soundOut.single().command)
        // One of the two is enough.
        assertTrue(linux(tools = allTools - "parec").soundOut.isEmpty())
        assertTrue(linux(tools = allTools - "pw-record").soundOut.isEmpty())
        assertTrue("sudo dnf install pulseaudio-utils" in HostChecks.soundMissing(Distro.Fedora))
        assertTrue("Install pulseaudio-utils or pipewire" in HostChecks.soundMissing(Distro.Other))
    }

    @Test
    fun mediaRemoteSaysWhatStopsWorking() {
        val noPlayer = linux(tools = allTools - "playerctl", osRelease = "ID=fedora\n").control.single()
        assertEquals("The media remote needs playerctl", noPlayer.title)
        assertEquals("sudo dnf install playerctl", noPlayer.command)
        assertTrue("media keys" in noPlayer.text) // xdotool is there
        val neither = linux(tools = setOf("parec")).control.single()
        assertEquals("sudo apt install playerctl pulseaudio-utils", neither.command)
        assertTrue("Without them, play, pause, skip and volume" in neither.text && "do nothing" in neither.text)
    }

    @Test
    fun typingHintOnlyWhenTextCanOnlyBePasted() {
        assertTrue(linux(tools = allTools - "xdotool", x11Remap = true).control.isEmpty())
        val typing = linux(tools = allTools - "xdotool", x11Remap = false).control.single()
        assertEquals("sudo apt install xdotool", typing.command)
        // Wayland types through the portal or pastes; xdotool is no help there.
        assertTrue(linux(env = mapOf("XDG_SESSION_TYPE" to "wayland"), tools = allTools - "xdotool", x11Remap = false).control.none { it.command == "sudo apt install xdotool" })
    }

    @Test
    fun noRobotOnX11() {
        val hints = linux(robot = false)
        assertTrue(hints.control.any { "XTEST" in it.text })
        assertTrue(hints.remote.any { "XTEST" in it.text })
    }

    @Test
    fun notificationServiceOnlyWhenKnownMissing() {
        assertTrue(linux(notifications = null).popups.isEmpty())
        assertTrue(linux(notifications = true).popups.isEmpty())
        assertEquals("sudo apt install dunst", linux(notifications = false).popups.single().command)
    }

    @Test
    fun linuxFirewallByTool() {
        assertTrue(linux().connectLater.isEmpty())
        val ufw = linux(tools = allTools + "ufw").connectLater.single()
        assertTrue(ufw.command!!.startsWith("sudo ufw allow ") && "5353/udp" in ufw.command!!)
        val firewalld = linux(tools = allTools + "firewall-cmd").connectLater.single()
        assertTrue("--add-service=mdns" in firewalld.command!!)
        assertTrue(linux(tools = allTools + "ufw").connectNow.isEmpty())
    }

    @Test
    fun windowsNetworkAndFirewall() {
        val public = HostChecks.hints(HostEnv(windows = true, linux = false, publicNetwork = true))
        assertEquals("ms-settings:network-status", public.connectNow.single().link?.url)
        assertEquals("windowsdefender://network", public.connectLater.single().link?.url)
        assertEquals(ObsCapture.Windows, public.obs)
        assertTrue(public.control.isEmpty() && public.soundOut.isEmpty() && public.overview.isEmpty())
        assertTrue(HostChecks.hints(HostEnv(windows = true, linux = false, publicNetwork = false)).connectNow.isEmpty())
        assertTrue(HostChecks.hints(HostEnv(windows = true, linux = false, publicNetwork = null)).connectNow.isEmpty())
    }

    @Test
    fun publicNetworkFromPowerShell() {
        assertEquals(true, HostChecks.anyPublic("Private\r\nPublic\r\n"))
        assertEquals(false, HostChecks.anyPublic("Private\r\nDomainAuthenticated\r\n"))
        assertNull(HostChecks.anyPublic("\r\n"))
    }

    @Test
    fun obsWordingNamesTheWindow() {
        val title = id.dysklink.desktop.ui.cameraWindowTitle("Pixel 8")
        assertEquals("Pixel 8 camera - DyskLink", title)
        assertTrue("Windows 10 (1903 and up)" in HostChecks.obsHint(ObsCapture.Windows, title).text)
        assertTrue("Window Capture (Xcomposite)" in HostChecks.obsHint(ObsCapture.Xcomposite, title).text)
        assertTrue("PipeWire" in HostChecks.obsHint(ObsCapture.PipeWire, title).text)
        // The window, or with Use as webcam, DyskLink Camera as a Video Capture Device.
        assertTrue("Use as webcam" in HostChecks.obsHint(ObsCapture.Windows, title).text && "'$title'" in HostChecks.obsHint(ObsCapture.Windows, title).text)
    }

    @Test
    fun phoneFeatureHints() {
        val control = PhoneHints.featureOff("control", "Taps and typing", "Pixel 8", "DESKTOP-1")
        assertTrue("choose DESKTOP-1" in control.text && "Control from Mac" in control.text && "Accessibility" in control.text)
        assertTrue("All files access" in PhoneHints.featureOff("files", "Phone files", "Pixel 8", "PC").text)
        assertNotNull(PhoneHints.refusalHelp("Viewing this screen is turned off on the phone.", "Pixel 8"))
        assertTrue(PhoneHints.refusalHelp("The camera couldn't open (error 3)", "Pixel 8")!!.contains("Permissions, Camera"))
        assertNull(PhoneHints.refusalHelp("Open DyskLink on the phone and start the camera there.", "Pixel 8"))
        assertFalse(PhoneHints.refusalHelp("Pixel 8 didn't allow it.", "Pixel 8").isNullOrEmpty())
    }
}
