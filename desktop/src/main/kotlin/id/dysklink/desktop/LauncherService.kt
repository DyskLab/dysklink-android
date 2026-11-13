package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

data class LauncherView(
    val enabled: Boolean = false,
    val combo: String = LauncherService.DEFAULT_COMBO.label,
    val hotkey: HotkeyStatus = HotkeyStatus(HotkeyBackend.Command),
    val hints: List<HostHint> = emptyList(),
    val apps: Int = 0,
    val files: Int = 0,
    /** Why the last custom shortcut wasn't taken. */
    val comboProblem: String? = null,
)

/** The Launcher as the window and Tools page see it. */
interface LauncherModel {
    val view: StateFlow<LauncherView>
    /** Everything but files: answers, actions, apps, settings, clipboard, emoji, web. */
    fun results(query: String): LauncherResults
    /** Files and folders for [scope]; may take a moment. */
    suspend fun files(scope: LauncherSearch.Scope, limit: Int): List<LauncherItem>
    fun run(item: LauncherItem, modifier: LauncherModifier)
    fun setEnabled(on: Boolean) {}
    fun setCombo(text: String) {}
    fun retryHotkey() {}
    fun open() {}
}

/** Settings pages of this PC the Launcher finds, with extra words in English and Indonesian. */
object LauncherSettingsPages {
    private data class Page(val title: String, val words: String, val windows: String?, val gnome: String?, val kde: String?)

    private val pages = listOf(
        Page("Display", "layar monitor resolution resolusi brightness kecerahan night light scale", "ms-settings:display", "display", "kcm_kscreen"),
        Page("Sound", "suara audio volume speaker microphone mikrofon output input", "ms-settings:sound", "sound", "kcm_pulseaudio"),
        Page("Bluetooth", "headphones mouse keyboard devices perangkat", "ms-settings:bluetooth", "bluetooth", "kcm_bluetooth"),
        Page("Wi-Fi and network", "wifi wireless internet jaringan ethernet vpn proxy", "ms-settings:network-wifi", "wifi", "kcm_networkmanagement"),
        Page("Notifications", "notifikasi pemberitahuan do not disturb jangan ganggu focus", "ms-settings:notifications", "notifications", "kcm_notifications"),
        Page("Power and battery", "baterai daya sleep tidur hemat energy", "ms-settings:powersleep", "power", "kcm_powerdevilprofilesconfig"),
        Page("Apps", "aplikasi uninstall hapus installed programs", "ms-settings:appsfeatures", "applications", null),
        Page("Startup apps", "startup login masuk autostart", "ms-settings:startupapps", null, "kcm_autostart"),
        Page("Keyboard", "papan ketik shortcuts pintasan layout input", "ms-settings:typing", "keyboard", "kcm_keyboard"),
        Page("Date and time", "tanggal jam waktu timezone zona", "ms-settings:dateandtime", "datetime", "kcm_clock"),
        Page("Language and region", "bahasa wilayah region format", "ms-settings:regionlanguage", "region", "kcm_regionandlang"),
        Page("Updates", "pembaruan update upgrade windows update", "ms-settings:windowsupdate", null, null),
        Page("Storage", "penyimpanan disk ruang space", "ms-settings:storagesense", null, null),
        Page("Privacy", "privasi keamanan permissions izin", "ms-settings:privacy", "privacy", null),
        Page("Personalization", "tampilan dark mode gelap terang wallpaper tema theme", "ms-settings:personalization", "background", "kcm_lookandfeel"),
    )

    fun forThisPc(windows: Boolean, desktop: LoginDesktop, onPath: (String) -> Boolean): List<LauncherItem> = pages.mapNotNull { p ->
        val argv = when {
            windows -> p.windows?.let { listOf("explorer.exe", it) }
            desktop == LoginDesktop.Gnome && onPath("gnome-control-center") -> p.gnome?.let { listOf("gnome-control-center", it) }
            desktop == LoginDesktop.Kde && onPath("systemsettings") -> p.kde?.let { listOf("systemsettings", it) }
            else -> null
        } ?: return@mapNotNull null
        LauncherItem("setting:${p.title}", LauncherKind.Setting, p.title, if (windows) "Windows Settings" else "System Settings", LauncherTarget.Command(argv), p.words)
    }
}

/**
 * The Launcher on this PC: its shortcut (Alt+Space unless changed), the apps and files it finds, and what each row
 * does. [actions] lists DyskLink's own actions now and [runAction] does one; [typeText] types into the app in front.
 */
class LauncherService(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val dataDir: File,
    private val portal: () -> PortalClient?,
    private val actions: () -> List<LauncherItem>,
    private val runAction: (String) -> Unit,
    private val clipSearch: (String) -> List<LauncherItem>,
    private val copyClip: (String) -> Boolean,
    private val canType: () -> Boolean,
    private val typeText: (String) -> Unit,
    private val pressPaste: () -> Unit,
    private val copy: (String) -> Unit,
) : LauncherModel {
    private val desktop = Typing.desktop()
    private val home = System.getProperty("user.home").orEmpty()
    private var combo = HotkeyCombo.parse(settings.string("launcher.hotkey").orEmpty()) ?: DEFAULT_COMBO
    private var hotkey: GlobalHotkey? = null
    private val _view = MutableStateFlow(LauncherView(enabled = settings.bool("launcher", false), combo = combo.label, hotkey = HotkeyStatus(Hotkeys.choose(desktop, false))))
    override val view: StateFlow<LauncherView> = _view.asStateFlow()
    private val _openRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The window should open: from the shortcut, the tray, or `dysklink --launcher`. */
    val openRequests: SharedFlow<Unit> = _openRequests.asSharedFlow()
    @Volatile private var previousWindow: com.sun.jna.Pointer? = null
    @Volatile private var apps: List<LauncherApp> = emptyList()
    @Volatile private var startApps: List<Pair<String, String>> = emptyList()
    @Volatile private var startAppsAt = 0L
    private val index = FileIndex({ listOf(File(home)) })
    private val locate: String? by lazy { if (Platform.isLinux) listOf("plocate", "locate").firstOrNull { Typing.onPath(it) } else null }
    private val frecencyFile = File(dataDir, "launcher-frecency.json")
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var frecency = loadFrecency()
    private val settingPages by lazy { LauncherSettingsPages.forThisPc(Platform.isWindows, HostChecks.loginDesktop(System.getenv("XDG_CURRENT_DESKTOP")), Typing::onPath) }
    private val distro by lazy { HostChecks.distro(runCatching { File("/etc/os-release").readText() }.getOrDefault("")) }

    private fun loadFrecency(): Frecency = runCatching {
        Frecency(json.decodeFromString(MapSerializer(String.serializer(), LauncherUse.serializer()), frecencyFile.readText()))
    }.getOrDefault(Frecency())

    fun start() {
        publish { it }
        if (_view.value.enabled) { startHotkey(); warmUp() }
    }

    fun stop() {
        hotkey?.close()
        hotkey = null
    }

    private fun publish(change: (LauncherView) -> LauncherView) {
        val next = change(_view.value)
        _view.value = next.copy(
            combo = combo.label,
            hints = if (!next.enabled) emptyList() else HostChecks.launcherHints(
                next.hotkey, combo, Hotkeys.command(flag = "--launcher"), Platform.isWindows, System.getenv("XDG_CURRENT_DESKTOP"), canType(), distro,
            ),
        )
    }

    /** Apps and files are gathered in the background, so the first open is quick. */
    private fun warmUp() {
        scope.launch(Dispatchers.IO) {
            refreshApps()
            if (index.isStale) index.refresh()
            publish { it.copy(files = index.files.size) }
        }
    }

    private fun refreshApps() {
        apps = runCatching {
            if (Platform.isWindows) {
                if (System.currentTimeMillis() - startAppsAt > 10 * 60_000L) {
                    StartMenuApps.powershell(StartMenuApps.START_APPS_SCRIPT)?.let { startApps = StartMenuApps.parseStartApps(it); startAppsAt = System.currentTimeMillis() }
                }
                StartMenuApps.merge(StartMenuApps.shortcuts(StartMenuApps.startMenus(System::getenv)), startApps)
            } else {
                DesktopEntries.scan(
                    DesktopEntries.dataDirs(System::getenv, home), DesktopEntries.languages(System::getenv),
                    System.getenv("XDG_CURRENT_DESKTOP").orEmpty().split(':').filter { it.isNotBlank() }, Typing::onPath,
                )
            }
        }.onFailure { CoreLog.w("DyskLink", "Couldn't list apps", it) }.getOrDefault(apps)
        publish { it.copy(apps = apps.size) }
    }

    private fun startHotkey() {
        scope.launch(Dispatchers.IO) {
            val portalShortcuts = desktop == Desktop.WAYLAND && portal()?.offer(PortalBits.GLOBAL_SHORTCUTS)?.version != null
            val open = { open() }
            val key: GlobalHotkey = when (Hotkeys.choose(desktop, portalShortcuts)) {
                HotkeyBackend.Windows -> WindowsHotkey(combo, open)
                HotkeyBackend.X11 -> X11Hotkey(combo, open)
                HotkeyBackend.Portal -> PortalHotkey(portal, combo, open, id = "launcher", description = "Open the DyskLink Launcher")
                HotkeyBackend.Command -> CommandHotkey()
            }
            synchronized(this@LauncherService) {
                if (!_view.value.enabled) return@launch
                hotkey?.close()
                hotkey = key
            }
            key.status.onEach { status -> publish { it.copy(hotkey = status) } }.launchIn(scope)
            runCatching { key.start() }.onFailure { CoreLog.w("DyskLink", "The Launcher shortcut failed", it) }
        }
    }

    /** Opens the window, remembering the app in front so emoji and copies go back there (Windows). */
    override fun open() {
        if (!_view.value.enabled) return
        previousWindow = WinClipboard.foreground()
        _openRequests.tryEmit(Unit)
        scope.launch(Dispatchers.IO) {
            refreshApps()
            if (index.isStale) { index.refresh(); publish { it.copy(files = index.files.size) } }
        }
    }

    override fun setEnabled(on: Boolean) {
        settings.set("launcher", on)
        publish { it.copy(enabled = on) }
        synchronized(this) { hotkey?.close(); hotkey = null }
        if (on) { startHotkey(); warmUp() }
    }

    override fun setCombo(text: String) {
        val parsed = HotkeyCombo.parse(text)
        if (parsed == null) {
            publish { it.copy(comboProblem = "That isn't a shortcut. Use Ctrl, Alt or Super with a letter, a digit, or Space, such as Ctrl+Alt+K.") }
            return
        }
        combo = parsed
        settings.set("launcher.hotkey", parsed.label)
        publish { it.copy(comboProblem = null) }
        if (_view.value.enabled) retryHotkey()
    }

    override fun retryHotkey() {
        synchronized(this) { hotkey?.close(); hotkey = null }
        startHotkey()
    }

    private val tone: Int get() = settings.int("launcher.emojiTone")?.coerceIn(0, 5) ?: 0
    private val recentEmoji: List<String> get() = settings.string("launcher.emojiRecent").orEmpty().split(' ').filter { it.isNotEmpty() }

    override fun results(query: String): LauncherResults {
        val candidates = actions() + apps.map(::appItem) + settingPages
        return LauncherQuery.results(
            query, candidates, frecency, System.currentTimeMillis(), clips = clipSearch,
            emoji = { terms ->
                val (items, used) = LauncherEmoji.items(terms, tone, recentEmoji, canType())
                if (used != tone) settings.set("launcher.emojiTone", used.toString())
                items
            },
        )
    }

    override suspend fun files(scope: LauncherSearch.Scope, limit: Int): List<LauncherItem> = withContext(Dispatchers.IO) {
        val own = index.search(scope.terms, limit, scope.foldersOnly)
        val located = locate?.let { tool ->
            Locate.run(tool, scope.terms, limit)?.let { out ->
                Locate.parse(out, home, scope.terms, limit, scope.foldersOnly) { p -> File(p).let { f -> if (f.exists()) f.isDirectory else null } }
            }
        }.orEmpty()
        LauncherQuery.fileItems((own + located).distinctBy { it.path }.let { FileIndex.rank(it, scope.terms, limit, scope.foldersOnly) }, home)
    }

    private fun appItem(app: LauncherApp) = LauncherItem(app.id, LauncherKind.App, app.name, app.detail, LauncherTarget.App(app), app.keywords)

    override fun run(item: LauncherItem, modifier: LauncherModifier) {
        frecency = frecency.used(item.id, System.currentTimeMillis())
        val effect = LauncherRouter.route(item.target, modifier, canType())
        scope.launch(Dispatchers.IO) {
            runCatching { frecencyFile.writeText(json.encodeToString(MapSerializer(String.serializer(), LauncherUse.serializer()), frecency.uses)) }
            (item.target as? LauncherTarget.Emoji)?.let { e ->
                settings.set("launcher.emojiRecent", (listOf(e.plain) + recentEmoji.filter { it != e.plain }).take(24).joinToString(" "))
            }
            runCatching { perform(effect) }.onFailure { CoreLog.w("DyskLink", "Launcher couldn't do $effect", it) }
        }
    }

    /** The window has closed by now; the app that was in front gets the keyboard back before anything is typed. */
    private suspend fun backToPrevious() {
        delay(150)
        previousWindow?.let(WinClipboard::activate)
        delay(80)
    }

    private suspend fun perform(effect: LauncherEffect) {
        when (effect) {
            is LauncherEffect.Launch -> launch(effect.app)
            is LauncherEffect.Open -> Platform.open(File(effect.path))
            is LauncherEffect.Reveal -> reveal(effect.path)
            is LauncherEffect.Browse -> Platform.browse(effect.url)
            is LauncherEffect.Copy -> copy(effect.text)
            is LauncherEffect.Type -> { backToPrevious(); typeText(effect.text) }
            is LauncherEffect.Run -> runAction(effect.actionId)
            is LauncherEffect.CopyClip -> copyClip(effect.id)
            is LauncherEffect.PasteClip -> if (copyClip(effect.id)) { backToPrevious(); pressPaste() }
            is LauncherEffect.Exec -> start(effect.argv, null)
        }
    }

    private fun start(argv: List<String>, dir: String?) {
        ProcessBuilder(argv).apply { dir?.let { directory(File(it)) } }
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }

    private fun launch(app: LauncherApp) {
        when (val how = app.launch) {
            is AppLaunch.Shortcut -> start(listOf("explorer.exe", how.path), null)
            is AppLaunch.AppsFolder -> start(listOf("explorer.exe", "shell:AppsFolder\\${how.appId}"), null)
            is AppLaunch.DesktopFile -> when {
                how.terminal && Typing.onPath("gio") -> start(listOf("gio", "launch", how.path), null)
                how.terminal -> start(listOf("x-terminal-emulator", "-e") + how.exec, how.workDir)
                else -> start(how.exec, how.workDir?.takeIf { File(it).isDirectory })
            }
        }
    }

    /** Shows a file selected in its folder: Explorer on Windows, the file manager over D-Bus on Linux, else the folder. */
    private fun reveal(path: String) {
        val file = File(path)
        if (Platform.isWindows) { start(listOf("explorer.exe", "/select,${file.path}"), null); return }
        val shown = Typing.onPath("dbus-send") && runCatching {
            val p = ProcessBuilder(
                "dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.FileManager1", "--type=method_call",
                "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems", "array:string:${file.toURI()}", "string:",
            ).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
        if (!shown) Platform.open(file.parentFile ?: file)
    }

    companion object {
        val DEFAULT_COMBO = HotkeyCombo(ctrl = false, shift = false, alt = true, meta = false, key = "Space")
        val CHOICES = listOf(DEFAULT_COMBO, HotkeyCombo(ctrl = true, shift = false, alt = false, meta = false, key = "Space"))
    }
}
