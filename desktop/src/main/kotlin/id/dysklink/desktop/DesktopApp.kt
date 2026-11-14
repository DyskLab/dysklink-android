package id.dysklink.desktop

import id.dysklink.desktop.ui.ThemeMode
import id.phonelink.android.core.CallEvent
import id.phonelink.android.core.ComputerHub
import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.DeviceInfo
import id.phonelink.android.core.InputEvent
import id.phonelink.android.core.Hello
import id.phonelink.android.core.HubEvents
import id.phonelink.android.core.NotificationItem
import id.phonelink.android.core.PhoneLinkProtocol
import id.phonelink.android.core.SmsItem
import id.phonelink.android.core.CommandResult
import id.phonelink.android.core.Envelope
import id.phonelink.android.core.MessageType
import id.phonelink.android.core.PcScreen
import id.phonelink.android.core.PcScreenSource
import id.phonelink.android.core.PcSoundCapture
import id.phonelink.android.core.PtyFactory
import id.phonelink.android.core.RemoteEvent
import id.phonelink.android.core.SavedCommand
import id.phonelink.android.core.NotchAction
import id.phonelink.android.core.NotchState
import id.phonelink.android.core.PcNotch
import id.phonelink.android.core.PcToolsState
import id.phonelink.android.core.PomodoroPhase
import id.phonelink.android.core.PomodoroState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A system notification to show from the tray. */
data class Popup(val title: String, val message: String)

/** Everything the window and tray show: the hub, plus the clipboard, ringing, and settings on this PC. */
class DesktopApp(private val scope: CoroutineScope) {
    /** Also holds the window's size and place and the theme, which Main keeps. */
    val settings = Settings(File(Platform.dataDir, "settings.properties"))
    // The banner goes away when the sound stops by itself after 30 seconds.
    private val ringer = Ringer { _ringingHere.value = false }
    private val logFile = File(Platform.dataDir, "dysklink.log")

    private val _clipboardOn = MutableStateFlow(settings.bool("clipboard", true))
    val clipboardOn: StateFlow<Boolean> = _clipboardOn.asStateFlow()
    private val _notifyOn = MutableStateFlow(settings.bool("notifications", true))
    /** Phone notifications also pop up on this PC. */
    val notifyOn: StateFlow<Boolean> = _notifyOn.asStateFlow()
    private val _ringingHere = MutableStateFlow(false)
    val ringingHere: StateFlow<Boolean> = _ringingHere.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    /** Why phones can't reach this PC, such as the port being taken. */
    val problem: StateFlow<String?> = _problem.asStateFlow()
    private val _controlOn = MutableStateFlow(settings.bool("control", true))
    /** Paired phones may use the touchpad, keyboard, and media remote on this PC. */
    val controlOn: StateFlow<Boolean> = _controlOn.asStateFlow()
    private val _soundFrom = MutableStateFlow<String?>(null)
    /** The phone whose sound plays on this PC now. */
    val soundFrom: StateFlow<String?> = _soundFrom.asStateFlow()
    private val speaker = PhoneSpeaker()
    private val media = MediaKeys()
    /** What the desktop portal allowed, kept so the user is asked once (Wayland). */
    private val portalTokens = PortalTokens(File(Platform.dataDir, "portal.properties"))
    private val portal: PortalClient? by lazy { if (Platform.isLinux) PortalClient.connect(LinuxTray.sessionAddress(), portalTokens) else null }
    /** The pointer and keyboard on Wayland: the RemoteDesktop portal, or /dev/uinput. Null elsewhere. */
    private val wayland: WaylandInput? =
        if (Platform.isLinux && Typing.desktop() == Desktop.WAYLAND) WaylandInput({ portal }, onChange = { waylandChanged() }) else null
    val input: PcInput = PcInput(::paste, wayland)

    private fun waylandChanged() {
        hub.infoChanged()
        recheckHost()
    }

    /** For text that can't be typed: pasted, with the clipboard put back after and nothing synced. */
    private fun paste(text: String): Boolean = systemClipboard.pasteThrough(text) { input.pasteNow() }
    private val _popups = MutableSharedFlow<Popup>(extraBufferCapacity = 16)
    val popups: SharedFlow<Popup> = _popups.asSharedFlow()

    private val _computersOn = MutableStateFlow(settings.bool("computers", false))
    /** Syncs the clipboard and files with Macs and other PCs on the network, as Other computers does on a Mac. */
    val computersOn: StateFlow<Boolean> = _computersOn.asStateFlow()

    private val systemClipboard = Clipboard(
        onCopied = { text -> if (_clipboardOn.value) hub.sendClipboard(text) },
        onImage = { png -> if (_clipboardOn.value) hub.sendClipboardImage(png) },
    )

    private fun phoneName(id: String) = hub.phones.value.firstOrNull { it.id == id }?.name ?: "Your phone"

    /** Clipboard History on the Tools page, with its quick picker. */
    val clipHistory = ClipHistoryService(
        scope, settings, Platform.dataDir, { portal },
        passingThrough = { systemClipboard.passingThrough ?: concealed },
        pressPaste = { input.pasteNow() },
        canPaste = { input.isAvailable },
    )

    /** A one-time code the island just copied: on the clipboard, never in the history or on phones. */
    @Volatile private var concealed: String? = null

    private fun copyToClipboard(text: String) {
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
    }

    private val _uiRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** The window should show a page or ask for files: "open", "settings", "tools", "pccare", "send:<phone>". */
    val uiRequests: SharedFlow<String> = _uiRequests.asSharedFlow()

    /** Window tidy on the Tools page. No backend on Wayland, where it can't work. */
    val windowTidy: WindowTidyService = run {
        val desktop = Typing.desktop()
        val backend: WindowBackend? = when (desktop) {
            Desktop.WINDOWS -> runCatching { WindowsWindowBackend() }.getOrNull()
            Desktop.X11 -> runCatching { X11WindowBackend() }.getOrNull()
            else -> null
        }
        WindowTidyService(scope, settings, Platform.dataDir, desktop, backend) { v ->
            if (!v.enabled && desktop != Desktop.WAYLAND) emptyList()
            else HostChecks.windowTidyHints(Platform.isWindows, desktop == Desktop.WAYLAND, System.getenv("XDG_CURRENT_DESKTOP"), backend?.problem, v.hotkey, v.mods)
        }
    }

    /** The Launcher on the Tools page, with its own shortcut. */
    val launcher = LauncherService(
        scope, settings, Platform.dataDir, { portal },
        actions = ::launcherActions, runAction = ::runLauncherAction,
        clipSearch = { query ->
            if (!clipHistory.store.settings.value.enabled) emptyList()
            else ClipHistoryStore.search(clipHistory.store.entries.value, query).filter { it.kind == ClipKind.Text }.take(3).map { e ->
                LauncherItem("clip:${e.id}", LauncherKind.Clip, e.preview.lineSequence().firstOrNull().orEmpty().take(80), "From the clipboard history", LauncherTarget.Clip(e.id))
            }
        },
        copyClip = { id -> clipHistory.store.entries.value.any { it.id == id }.also { if (it) clipHistory.act(ClipAction.Copy(id)) } },
        canType = { input.isAvailable },
        typeText = { text -> input.handle(InputEvent("text", text = text)) },
        pressPaste = { input.pasteNow() },
        copy = ::copyToClipboard,
    )

    private val _trayMenu = MutableStateFlow(TrayMenuConfig.load(settings))
    /** Which parts the tray menu shows, and in what order. */
    val trayMenu: StateFlow<TrayMenuConfig> = _trayMenu.asStateFlow()

    fun setTrayMenu(config: TrayMenuConfig) {
        _trayMenu.value = config
        config.save(settings)
        energy.setTray(TraySection.EnergyApp in config.shown)
    }

    /** DyskLink's own rows in the Launcher, for what's on now. */
    private fun launcherActions(): List<LauncherItem> = buildList {
        fun action(id: String, title: String, subtitle: String, keywords: String = "") = add(LauncherItem("action:$id", LauncherKind.Action, title, subtitle, LauncherTarget.Action(id), keywords))
        action("open", "Open DyskLink", "Main window", "dysklink window")
        val pomodoro = tools.value.pomodoro.state
        action("pomodoro", if (pomodoro.isRunning) "Pause focus" else if (pomodoro.isStarted) "Resume focus" else "Start focus", "Pomodoro", "pomodoro timer fokus")
        if (clipHistory.store.settings.value.enabled) action("clipboard", "Clipboard history", "Everything you copied", "clipboard riwayat salin paste")
        hub.phones.value.filter { it.isPaired && it.isConnected }.forEach { phone ->
            action("send:${phone.id}", "Send files to ${phone.name}", "Phone", "kirim file send")
            action("ring:${phone.id}", "Ring ${phone.name}", "Find phone", "cari hp bunyi find")
        }
        val tidy = windowTidy.view.value
        if (tidy.enabled && tidy.supported) {
            Layouts.forMonitors(tidy.layouts, tidy.monitorKey).forEach { layout ->
                action("layout:${layout.id}", "Restore ${layout.name}", "Window layout", "layout jendela window")
            }
            action("grid", "Arrange windows in a grid", "Window tidy", "grid tidy rapikan jendela")
        }
        action("water", "Drink a glass of water", "Water", "minum air water")
        action("pccare", "PC care", "Clean up, duplicates, startup apps", "cleaner bersih uninstall startup care")
        action("tools", "DyskLink Tools", "Focus, water, tasks", "tools todo")
        action("settings", "DyskLink Settings", "DyskLink", "settings pengaturan preferences")
        action("lock", "Lock screen", "This PC", "kunci lock layar")
    }

    private fun runLauncherAction(id: String) {
        when {
            id == "pomodoro" -> tools { togglePomodoro() }
            id == "water" -> tools { drink() }
            id == "clipboard" -> clipHistory.requestPicker()
            id == "grid" -> windowTidy.run(WindowTidy.GRID)
            id == "lock" -> runCatching {
                if (Platform.isWindows) ProcessBuilder("rundll32.exe", "user32.dll,LockWorkStation").start()
                else ProcessBuilder("loginctl", "lock-session").start()
            }
            id.startsWith("ring:") -> hub.ring(id.removePrefix("ring:"), true)
            id.startsWith("layout:") -> windowTidy.restore(id.removePrefix("layout:"))
            else -> _uiRequests.tryEmit(id)
        }
    }

    /** The laptop's battery; its view stays null on a PC without one. */
    val battery = BatteryMonitor(scope, Platform.dataDir)
    /** Apps using significant energy, in the Battery section and the tray. */
    val energy = EnergyMonitor(scope, battery.view)

    /** `dysklink --clipboard` from a desktop shortcut, and a second start, reach this one through it. */
    private val instance = SingleInstance(Platform.dataDir)
    private val _showRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Another start of DyskLink asked for the window. */
    val showRequests: SharedFlow<Unit> = _showRequests.asSharedFlow()

    // What this PC serves phones, and a Mac's mouse and keyboard. Everything here is off until it's turned on.

    private val _soundOutOn = MutableStateFlow(settings.bool("soundOut", false))
    /** "Let phones hear this PC": a paired phone can ask for this PC's sound. */
    val soundOutOn: StateFlow<Boolean> = _soundOutOn.asStateFlow()

    private val _screenOutOn = MutableStateFlow(settings.bool("screenOut", false))
    /** "Let phones see this screen": a paired phone can view this PC's screen, and tap on it while control is on. */
    val screenOutOn: StateFlow<Boolean> = _screenOutOn.asStateFlow()
    private val robotScreen by lazy { RobotScreenSource(wayland = input.isWayland) }
    /** This screen on Wayland: the ScreenCast portal and GStreamer. */
    private val waylandScreen by lazy {
        PortalScreenSource({ portal }, { gstCheck() }, { wayland }, { _controlOn.value }, onStoppedHere = { hub.stopScreenOut(PcScreen.STOPPED_HERE) })
    }
    @Volatile private var gst: GstCheck? = null

    /** GStreamer as found, checked again only while it's missing. */
    private fun gstCheck(): GstCheck = gst?.takeIf { it.ready } ?: GstCheck.probe({ Typing.onPath(it) }, GstCheck::run).also { gst = it }

    // The phone as a second display: a monitor added for it, shown through "Let phones see this screen".

    private val _extendExperiment = MutableStateFlow(settings.bool("extendExperiment", false))
    /** Windows: the experimental Virtual Display Driver path is offered. */
    val extendExperiment: StateFlow<Boolean> = _extendExperiment.asStateFlow()
    /** GNOME's own session bus connection for Mutter's API, apart from the portal's. */
    private val mutterBus: org.freedesktop.dbus.connections.impl.DBusConnection? by lazy {
        val address = LinuxTray.sessionAddress() ?: return@lazy null
        runCatching { org.freedesktop.dbus.connections.impl.DBusConnectionBuilder.forAddress(address).withShared(false).build() }
            .onFailure { CoreLog.w("DyskLink", "No session bus for Mutter", it) }.getOrNull()
    }
    private val gnomeVersion: Int? by lazy {
        if (HostChecks.loginDesktop(System.getenv("XDG_CURRENT_DESKTOP")) != LoginDesktop.Gnome) null
        else MutterBits.shellVersion(GstCheck.run(listOf("gnome-shell", "--version"))?.second)
    }
    private val windowsVdd: WindowsVddBackend? =
        if (Platform.isWindows) WindowsVddBackend({ _extendExperiment.value }, { Vdd.bundled() }, marker = File(Platform.dataDir, "vdd-attached")) else null
    private val extendBackends: List<ExtendBackend> = when {
        Platform.isWindows -> listOfNotNull(windowsVdd)
        Platform.isLinux && input.isWayland -> listOf(
            PortalVirtualBackend({ portal }, { gstCheck() }, { _controlOn.value }),
            MutterVirtualBackend({ mutterBus }, { gstCheck() }, { _controlOn.value }, { gnomeVersion }),
        )
        Platform.isLinux -> listOf(X11OutputBackend({ Typing.onPath(it) }, GstCheck::run) { input.hasRobot })
        else -> emptyList()
    }
    val secondDisplay = SecondDisplayService { extendBackends }
    /** What the Second display card shows. */
    val secondDisplayView: StateFlow<SecondDisplayView> by lazy {
        kotlinx.coroutines.flow.combine(secondDisplay.probes, secondDisplay.checking, hub.extend, _screenOutOn, _extendExperiment) { probes, checking, extend, screenOn, experiment ->
            SecondDisplayView.of(probes, checking, extend, { id -> hub.phones.value.firstOrNull { it.id == id }?.name }, screenOn, Platform.isWindows, experiment)
        }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, SecondDisplayView(experimentShown = Platform.isWindows))
    }

    fun setExtendExperimentOn(on: Boolean) {
        _extendExperiment.value = on
        settings.set("extendExperiment", on)
        recheckHost()
    }

    private fun waylandHost(): WaylandHost? {
        val w = wayland ?: return null
        w.recheck()
        return WaylandHost(
            route = w.route.value,
            input = w.status.value,
            inputRemembered = portalTokens.get(PortalTokens.INPUT) != null,
            screenCast = if (portal == null) false else waylandScreen.screenCast().version != null,
            gst = gstCheck(),
            screen = waylandScreen.status.value,
            screenRemembered = portalTokens.get(PortalTokens.SCREEN) != null || portalTokens.get(PortalTokens.SCREEN_WITH_INPUT) != null,
        )
    }

    val commandStore = CommandStore(File(Platform.dataDir, "commands.json"))
    private fun idSet(key: String) = settings.string(key).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
    private val _commandPhones = MutableStateFlow(idSet("commandPhones"))
    /** Phones allowed to run this PC's commands. */
    val commandPhones: StateFlow<Set<String>> = _commandPhones.asStateFlow()
    private val _terminalPhones = MutableStateFlow(idSet("terminalPhones"))
    /** Phones allowed to open a terminal here. */
    val terminalPhones: StateFlow<Set<String>> = _terminalPhones.asStateFlow()

    private val _remoteOn = MutableStateFlow(settings.bool("remoteInput", false))
    /** "Let paired computers use their mouse and keyboard here". */
    val remoteOn: StateFlow<Boolean> = _remoteOn.asStateFlow()
    private val _commandAsCtrl = MutableStateFlow(settings.bool("remoteCommandAsCtrl", true))
    /** The Mac's Command key works as Ctrl here, so Command-C copies. */
    val commandAsCtrl: StateFlow<Boolean> = _commandAsCtrl.asStateFlow()
    private val _usedBy = MutableStateFlow<String?>(null)
    /** The Mac whose mouse and keyboard are on this PC now. */
    val usedBy: StateFlow<String?> = _usedBy.asStateFlow()

    /** Null where nothing reaches other apps (no display, or Wayland with neither the portal nor /dev/uinput). */
    private val remoteTarget: SharedInputTarget? by lazy {
        val injector = input.injector ?: return@lazy null
        SharedInputTarget(
            injector,
            send = { id, envelope -> hub.sendRemote(id, envelope) },
            commandAsCtrl = { _commandAsCtrl.value },
            onChange = { id ->
                _usedBy.value = id
                if (id != null) _popups.tryEmit(Popup("${phoneName(id)} is using this PC", "Its mouse and keyboard work here. Turn this off in DyskLink, Settings, Computers."))
            },
        )
    }

    /** Whether a Mac's mouse and keyboard can work here at all. */
    val remoteAvailable: Boolean get() = input.isAvailable

    // This PC's own mouse and keyboard on a Mac.

    /** Low-level hooks on Windows, XInput2 and grabs on X11. None on Wayland, which doesn't allow it. */
    private val captureBackend: CaptureBackend? = when {
        Platform.isWindows -> WindowsCapture()
        Platform.isLinux && Typing.desktop() != Desktop.WAYLAND && !GraphicsEnvironment.isHeadless() -> X11Capture()
        else -> null
    }
    private val xinput2: Boolean? by lazy { (captureBackend as? X11Capture)?.available() }
    private val _source = MutableStateFlow(
        SourceView(
            supported = captureBackend != null,
            on = settings.bool("remoteSource", false),
            mac = settings.string("remoteSourceMac"),
            edge = ScreenEdge.of(settings.string("remoteSourceEdge")) ?: ScreenEdge.RIGHT,
            ctrlAsCommand = settings.bool("remoteCtrlAsCommand", true),
        ),
    )
    /** "Use this PC's mouse and keyboard on a Mac", and the Mac it's on now. */
    val source: StateFlow<SourceView> = _source.asStateFlow()

    private val inputSource: SharedInputSource? = captureBackend?.let { backend ->
        SharedInputSource(
            backend,
            send = { id, envelope -> hub.sendRemote(id, envelope) },
            target = ::sourceTarget,
            ctrlAsCommand = { _source.value.ctrlAsCommand },
            onChange = { id -> _source.value = _source.value.copy(usingMac = id) },
        )
    }

    /** The Mac to go to now, or null: off, no Mac chosen, it isn't connected or doesn't allow it, or a Mac is using this PC. */
    private fun sourceTarget(): SourceTarget? {
        val view = _source.value
        if (!view.on || !_computersOn.value || _usedBy.value != null) return null
        val id = view.mac ?: return null
        val mac = hub.phones.value.firstOrNull { it.id == id } ?: return null
        if (mac.platform != "macos" || !mac.isPaired || !mac.isConnected || mac.info?.remoteControl != true) return null
        return SourceTarget(id, view.edge)
    }

    fun setSourceOn(on: Boolean) {
        _source.value = _source.value.copy(on = on)
        settings.set("remoteSource", on)
        // Picks the only Mac when there's one, so turning it on is enough.
        if (on && _source.value.mac == null) {
            hub.phones.value.filter { it.isPaired && it.platform == "macos" }.singleOrNull()?.let { setSourceMac(it.id) }
        }
        if (!on) inputSource?.end(EndReason.TURNED_OFF)
        if (on) recheckHost()
    }

    fun setSourceMac(id: String) {
        if (_source.value.mac != id) inputSource?.end(EndReason.TURNED_OFF)
        _source.value = _source.value.copy(mac = id)
        settings.set("remoteSourceMac", id)
    }

    fun setSourceEdge(edge: ScreenEdge) {
        _source.value = _source.value.copy(edge = edge)
        settings.set("remoteSourceEdge", edge.wire)
    }

    fun setCtrlAsCommand(on: Boolean) {
        _source.value = _source.value.copy(ctrlAsCommand = on)
        settings.set("remoteCtrlAsCommand", on)
    }

    /** Brings the pointer back from the Mac at once. */
    fun stopSource() { inputSource?.end(EndReason.STOPPED) }

    // The notch tools on this PC: Todo, water, Pomodoro, and the note, also on the phone as a remote (`pc-notch`).

    val notch = PcNotch(Platform.dataDir)
    val tools: StateFlow<PcToolsState> = notch.tools
    private val _trayStatus = MutableStateFlow<String?>(null)
    /** The Pomodoro's countdown for the tray while it's started, such as "Focus 1/4: 24:59". */
    val trayStatus: StateFlow<String?> = _trayStatus.asStateFlow()

    /** Runs a change on the tools; when anything changed, phones get the new state. */
    fun tools(change: PcNotch.() -> Boolean): Boolean = notch.change().also { if (it) hub.notchChanged() }

    /** The island at the top of the screen, like the Mac notch. Off until it's turned on in Settings. */
    val island: IslandService by lazy {
        IslandService(
            scope, settings, Platform.dataDir, tools, ::tools, hub.phones, hub.notifications, hub.fileTransfers.transfers,
            clipHistory = clipHistory.view,
            clipAction = { clipHistory.act(it) },
            clipImage = { clipHistory.store.imageFile(it) },
            sendFile = { id, file -> hub.sendFile(id, file) },
            dismissNotification = { id, key -> hub.dismissNotification(id, key) },
            copyConcealed = { code -> concealed = code; systemClipboard.set(code) },
            popup = { title, text -> _popups.tryEmit(Popup(title, text)) },
            pcBattery = battery.view.map { v -> v?.reading?.let { it.percent to (it.pluggedIn == true) } }
                .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null),
        )
    }

    private fun startTools() {
        scope.launch {
            while (true) {
                val tick = notch.tick()
                if (tick.changed) hub.notchChanged()
                tick.finished?.let { finished ->
                    val (title, message) = PcNotch.finishedText(finished, tick.next, notch.tools.value.pomodoro.settings)
                    if (island.isOn) island.pomodoroFinished(finished, tick.next)
                    _popups.tryEmit(Popup(title, message))
                }
                if (tick.remindWater) {
                    val water = notch.tools.value.water
                    val today = water.totalToday(System.currentTimeMillis(), java.time.ZoneId.systemDefault())
                    if (island.isOn) island.waterReminder(water.fraction(System.currentTimeMillis(), java.time.ZoneId.systemDefault()))
                    _popups.tryEmit(Popup("Time to drink water", "$today of ${water.settings.goalMl} ml today."))
                }
                _trayStatus.value = trayText(notch.tools.value)
                delay(1_000)
            }
        }
    }

    private fun trayText(tools: PcToolsState): String? {
        val state = tools.pomodoro.state
        if (!state.isStarted) return null
        val left = PomodoroState.clock(state.remaining(System.currentTimeMillis(), tools.pomodoro.settings))
        return "${state.title(tools.pomodoro.settings)}: $left" + if (state.isRunning) "" else " (paused)"
    }

    // What this PC needs from outside DyskLink, as hints where each feature is set up.

    /** Popups where there's no tray to show them, and whether a notification service runs at all. Linux only. */
    private val notifier: LinuxNotifier? by lazy { if (Platform.isLinux) LinuxNotifier.start(LinuxTray.sessionAddress()) else null }
    private val _host = MutableStateFlow(HostHints())
    val host: StateFlow<HostHints> = _host.asStateFlow()
    @Volatile private var publicNetwork: Boolean? = null
    @Volatile private var networkCheckedAt = 0L

    /** Checks again what's installed and running; on window focus, and when a feature is turned on or tried. */
    fun recheckHost() {
        scope.launch {
            val now = System.currentTimeMillis()
            // PowerShell takes a moment, so the network type is read at most once a minute.
            if (Platform.isWindows && now - networkCheckedAt > 60_000) {
                networkCheckedAt = now
                publicNetwork = windowsPublicNetwork()
            }
            val extend = secondDisplay.recheck()
            _host.value = HostChecks.hints(
                HostChecks.current(
                    notifier?.hasService(), publicNetwork, input.hasRobot, input.canRemap, waylandHost(), extend, windowsVdd?.installed == true,
                    xinput2 = if (_source.value.on) xinput2 else null,
                ),
            )
            webcam.recheck()
        }
    }

    /** The phone camera as this PC's webcam, "DyskLink Camera" in video apps: the "Use as webcam" switch. */
    val webcam: VirtualCamera by lazy {
        VirtualCamera.forThisPc(hub.cameras, settings.bool("webcam", false)) { on -> settings.set("webcam", on) }
    }

    fun setWebcamOn(on: Boolean) = scope.launch(kotlinx.coroutines.Dispatchers.IO) { webcam.setOn(on) }

    /** A hint's button: installing or removing the Windows camera driver. */
    fun hintAction(id: String) = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        if (id == HostChecks.ACTION_WEBCAM_INSTALL || id == HostChecks.ACTION_WEBCAM_REMOVE) webcam.action(id)
        if (id == HostChecks.ACTION_PORTAL_INPUT) wayland?.retry()
        if (id == HostChecks.ACTION_PORTAL_SCREEN) { waylandScreen.retry(); recheckHost() }
        if (id == HostChecks.ACTION_CLIPBOARD_HOTKEY) clipHistory.act(ClipAction.RetryHotkey)
        if (id == HostChecks.ACTION_LAUNCHER_HOTKEY) launcher.retryHotkey()
        if (id == HostChecks.ACTION_TIDY_HOTKEY) windowTidy.act(TidyAction.RetryHotkey)
        if (id == HostChecks.ACTION_VDD_INSTALL || id == HostChecks.ACTION_VDD_REMOVE) vddAction(remove = id == HostChecks.ACTION_VDD_REMOVE)
    }

    /** Installs or removes the Virtual Display Driver, as administrator. */
    private fun vddAction(remove: Boolean) {
        val vdd = windowsVdd ?: return
        val dir = Vdd.bundled()
        if (dir == null) {
            _popups.tryEmit(Popup("The virtual display driver isn't in this copy", "Install DyskLink from the downloads page, then try again."))
            return
        }
        if (remove) hub.stopScreenOut(PcScreen.STOPPED_HERE)
        val ok = VddInstall.run(dir, remove)
        if (ok && !remove) vdd.detachAfterInstall()
        if (!ok) _popups.tryEmit(Popup(if (remove) "The driver wasn't removed" else "The driver wasn't installed", "Windows said no, or it failed. See dysklink.log in DyskLink's data folder."))
        recheckHost()
    }

    /** A desktop notification over D-Bus, for when the tray can't show it. False when there's no way here. */
    fun notifyDesktop(title: String, message: String): Boolean {
        val notifier = notifier ?: return false
        notifier.notify(title, message)
        return true
    }

    private fun windowsPublicNetwork(): Boolean? = runCatching {
        val process = ProcessBuilder("powershell", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "(Get-NetConnectionProfile).NetworkCategory")
            .redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        if (process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) HostChecks.anyPublic(out) else null
    }.getOrNull()

    // Updates of this app, from GitHub releases. Nothing installs by itself.

    val updater = Updater(settings, scope)

    /** A scan from a phone, saved to Documents/DyskLink/Scans: shown, and a PDF opened. */
    private fun scanSaved(file: File, from: String) {
        _popups.tryEmit(Popup("Scan from $from saved", "${file.name} is in ${file.parent}."))
        if (file.extension.equals("pdf", ignoreCase = true)) Platform.open(file)
    }

    val hub: ComputerHub = ComputerHub(
        Platform.dataDir, scope,
        // pc-display only where a monitor can be added, or set up from here: else the phone doesn't offer Second display.
        localHello = { paired ->
            var caps = if (secondDisplay.listed) PhoneLinkProtocol.PC_CAPS + PhoneLinkProtocol.CAP_PC_DISPLAY else PhoneLinkProtocol.PC_CAPS
            // A Mac takes this PC's mouse and keyboard only from a PC that lists it; only where they can be captured.
            if (captureBackend != null) caps = caps + PhoneLinkProtocol.CAP_PC_SHARED_INPUT_SOURCE
            Hello(Platform.deviceName, Platform.protocolName, Platform.model, PhoneLinkProtocol.VERSION, paired, Platform.version, caps)
        },
        events = object : HubEvents {
            override fun clipboard(text: String, from: String): Boolean =
                (_clipboardOn.value && systemClipboard.set(text)).also { if (it) clipHistory.received(ClipCapture.Text(text), phoneName(from)) }

            override fun ring(on: Boolean, from: String) {
                if (on) {
                    ringer.start()
                    _ringingHere.value = true
                    _popups.tryEmit(Popup("${phoneName(from)} is looking for this PC", "Open DyskLink to stop the sound."))
                } else {
                    stopRinging()
                }
            }

            override fun notification(item: NotificationItem, from: String) {
                // A one-time code shows in the island with Copy instead of as a popup.
                if (island.isOn && island.onNotification(item, from)) return
                if (!_notifyOn.value) return
                val title = listOfNotNull(item.app, item.title).distinct().joinToString(": ")
                _popups.tryEmit(Popup(title, item.text.orEmpty()))
            }

            override fun sms(items: List<SmsItem>, from: String) {
                if (island.isOn && island.onSms(items, from)) return
                if (!_notifyOn.value) return
                // A phone that shares its notifications already shows its messaging app's own one here.
                if ("notifications" in hub.phones.value.firstOrNull { it.id == from }?.features.orEmpty()) return
                items.takeLast(3).forEach { _popups.tryEmit(Popup("SMS from ${it.address}", it.body.take(200))) }
            }

            override fun call(call: CallEvent, from: String) {
                if (call.state == "ringing") _popups.tryEmit(Popup("Incoming call on ${phoneName(from)}", call.number ?: "Unknown number"))
                if (call.state == "missed") _popups.tryEmit(Popup("Missed call on ${phoneName(from)}", call.number ?: "Unknown number"))
            }

            override val canControl: Boolean get() = _controlOn.value && input.isAvailable
            override fun input(event: InputEvent, from: String) = input.handle(event)
            override fun media(action: String, from: String) { if (_controlOn.value) media.press(action) }

            override fun phoneSound(from: String, rate: Int, channels: Int) {
                speaker.start(rate, channels)
                _soundFrom.value = from
            }
            override fun clipboardImage(png: ByteArray, from: String) {
                if (_clipboardOn.value && systemClipboard.setImage(png)) clipHistory.received(ClipCapture.Image(png), phoneName(from))
            }
            override fun phoneSoundChunk(from: String, pcm: ByteArray) = speaker.play(pcm)
            override fun phoneSoundEnded(from: String) {
                speaker.stop()
                _soundFrom.value = null
            }

            override fun soundRefusal(from: String): String? = when {
                !_soundOutOn.value -> "Turn on \"Let phones hear this PC\" in DyskLink on the PC first."
                else -> null
            }
            override fun soundCapture(): PcSoundCapture? = PcSound.capture().also { recheckHost() }

            override fun screenRefusal(from: String): String? = when {
                // Said first: turning the switch on wouldn't help. On Wayland, only when a piece is missing.
                input.isWayland -> waylandScreen.refusal().also { if (it != null) recheckHost() } ?: if (!_screenOutOn.value) PcScreen.TURNED_OFF else {
                    // The phone asking is what may share again after a Stop on this PC.
                    waylandScreen.phoneAsked()
                    null
                }
                !_screenOutOn.value -> PcScreen.TURNED_OFF
                else -> null
            }
            override fun screenSource(): PcScreenSource = if (input.isWayland) waylandScreen else robotScreen
            override fun screenPointer(kind: String, x: Int, y: Int, from: String) {
                if (_controlOn.value) input.pointerAt(kind, x, y)
            }
            override fun extendRefusal(from: String): String? = if (!_screenOutOn.value) PcScreen.EXTEND_TURNED_OFF else null
            override fun extraDisplays(): id.phonelink.android.core.PcExtraDisplays = secondDisplay
            override val controlSwitch: Boolean get() = _controlOn.value
            override fun extendStarted(from: String, width: Int, height: Int) {
                val where = if (Platform.isWindows) "Settings, System, Display" else "the display settings"
                _popups.tryEmit(Popup("${phoneName(from)} is now a second display", "${width}×$height. Drag windows onto it; arrange it in $where."))
            }
            override fun screenViewed(from: String) {
                // Always shown, whatever the notification setting, so the screen is never watched unnoticed.
                _popups.tryEmit(Popup("${phoneName(from)} is viewing this screen", "Stop it in DyskLink, or turn off \"Let phones see this screen\"."))
            }

            override fun commands(from: String): List<SavedCommand>? = if (from in _commandPhones.value) commandStore.commands.value else null
            override fun runCommand(command: SavedCommand): CommandResult = PcShell.run(command)
            override fun terminalAllowed(from: String): Boolean = from in _terminalPhones.value
            override val pty: PtyFactory get() = PcShell.pty
            override fun terminalOpened(from: String) {
                // Always shown, whatever the notification setting, so a terminal never opens unnoticed.
                _popups.tryEmit(Popup("Terminal opened from ${phoneName(from)}", "It runs as you. Turn off \"Let ${phoneName(from)} open a terminal\" in DyskLink to stop it."))
            }

            // Not while this PC's own pointer is on a Mac, which would pass input round in a loop.
            override fun remoteAllowed(from: String): Boolean =
                _remoteOn.value && _computersOn.value && remoteTarget != null && inputSource?.crossedTo == null
            override fun heard(from: String) {
                inputSource?.heard(from)
                remoteTarget?.heard(from)
            }

            override fun pinged(from: String) {
                remoteTarget?.pinged(from)
            }
            override fun remote(type: String, event: RemoteEvent?, from: String) {
                // This PC's own pointer coming back from the Mac, or the Mac ending it.
                if (type == MessageType.REMOTE_LEAVE || type == MessageType.REMOTE_EXIT) inputSource?.remote(type, event, from)
                val target = remoteTarget ?: return
                when (type) {
                    MessageType.REMOTE_ENTER -> {
                        val edge = ScreenEdge.of(event?.edge)
                        if (edge == null) hub.sendRemote(from, Envelope(MessageType.REMOTE_EXIT)) else target.enter(from, edge, event?.at ?: 0.5)
                    }
                    MessageType.REMOTE_INPUT -> event?.let { target.handle(it, from) }
                    MessageType.REMOTE_EXIT -> target.gone(from)
                }
            }
            override fun remoteGone(from: String) {
                remoteTarget?.gone(from)
                inputSource?.gone(from)
            }

            override fun notchState(): NotchState = notch.notchState()
            override fun notchAction(action: NotchAction, from: String) {
                if (notch.apply(action)) hub.notchChanged()
            }
        },
        fileSinks = DownloadSinks({ Platform.scansDir }, { file, origin -> scanSaved(file, origin.peerName) }) { Platform.downloadsDir },
        port = Platform.port,
        localInfo = {
            DeviceInfo(
                osVersion = Platform.model, model = Platform.model, inputTrusted = _controlOn.value && input.isAvailable,
                remoteControl = _remoteOn.value && _computersOn.value && input.isAvailable,
            )
        },
    )
    /** Phones over a USB cable, through adb, as on the Mac. On unless turned off. */
    val usb = UsbBridge(
        scope, Platform.port, Platform.dataDir, settings.bool("usb", true),
        onEnabledChange = { on -> settings.set("usb", on) },
        onTurnedOff = { hub.closeUsbConnections() },
    )

    private val advertiser = Advertiser(
        Platform.deviceName, Platform.port, hub.deviceId, Platform.protocolName,
        onComputer = { id, host, port -> hub.computerFound(id, host, port) },
        onComputerGone = { id -> hub.computerLost(id) },
    )

    fun start() {
        CoreLog.sink = { level, tag, message, error ->
            val line = "${LocalDateTime.now()} $level $tag $message${error?.let { " ($it)" } ?: ""}"
            println(line)
            runCatching {
                if (logFile.length() > 1_000_000) logFile.writeText("")
                logFile.appendText(line + "\n")
            }
        }
        CoreLog.i("DyskLink", "DyskLink ${Platform.version} on ${Platform.model}, data in ${Platform.dataDir}")
        try {
            hub.start()
            hub.setComputersEnabled(_computersOn.value)
            advertiser.start()
            usb.start()
        } catch (error: java.net.BindException) {
            _problem.value = "Port ${Platform.port} is in use. Is DyskLink already running? Close it and start again."
            CoreLog.w("DyskLink", "Port in use", error)
        }
        systemClipboard.enabled = _clipboardOn.value
        systemClipboard.start()
        startTools()
        inputSource?.let { source ->
            source.start()
            // However the app ends, the hooks and grabs go and the pointer is let go first.
            Runtime.getRuntime().addShutdownHook(Thread({ runCatching { source.shutdown() } }, "dysk-input-release"))
        }
        clipHistory.start()
        if (island.isOn) island.start() else island.recheckHints()
        windowTidy.start()
        launcher.start()
        battery.start()
        energy.setTray(TraySection.EnergyApp in _trayMenu.value.shown)
        energy.start()
        instance.listen { command ->
            when {
                command == SingleInstance.CLIPBOARD -> clipHistory.requestPicker()
                command == SingleInstance.SHOW -> _showRequests.tryEmit(Unit)
                command == SingleInstance.LAUNCHER -> launcher.open()
                command.startsWith(SingleInstance.WINDOW) -> windowTidy.run(command.removePrefix(SingleInstance.WINDOW))
            }
        }
        updater.start()
        recheckHost()
        if (webcam.state.value.on) setWebcamOn(true)
        windowsVdd?.let { vdd -> scope.launch { runCatching { vdd.tidyUp() } } }
        wayland?.let { w ->
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { w.probe() }
            // The hints follow the portal's dialogs as they're answered.
            scope.launch { waylandScreen.status.collect { recheckHost() } }
        }
    }

    fun stop() {
        remoteTarget?.release(notify = true)
        inputSource?.shutdown()
        advertiser.stop()
        systemClipboard.stop()
        clipHistory.stop()
        island.stop()
        windowTidy.stop()
        launcher.stop()
        battery.stop()
        energy.stop()
        instance.close()
        ringer.stop()
        speaker.stop()
        media.stop()
        webcam.close()
        hub.shutdown()
        notifier?.close()
        wayland?.close()
        if (wayland != null) runCatching { portal?.close() }
    }

    fun setClipboardOn(on: Boolean) {
        _clipboardOn.value = on
        systemClipboard.enabled = on
        settings.set("clipboard", on)
    }

    fun setComputersOn(on: Boolean) {
        _computersOn.value = on
        settings.set("computers", on)
        if (!on) remoteTarget?.release(notify = true)
        hub.setComputersEnabled(on)
    }

    fun setSoundOutOn(on: Boolean) {
        _soundOutOn.value = on
        settings.set("soundOut", on)
        if (on) recheckHost()
        if (!on) hub.stopSoundOut()
    }

    fun stopSoundOut() = hub.stopSoundOut()

    fun setScreenOutOn(on: Boolean) {
        _screenOutOn.value = on
        settings.set("screenOut", on)
        if (on) recheckHost()
        if (!on) hub.stopScreenOut(PcScreen.SWITCHED_OFF)
    }

    fun stopScreenOut() = hub.stopScreenOut()

    fun addCommand(name: String, command: String): Boolean = commandStore.add(name, command).also { if (it) hub.commandsChanged() }

    fun removeCommand(id: String) {
        commandStore.remove(id)
        hub.commandsChanged()
    }

    fun setCommandsAllowed(phoneId: String, on: Boolean) {
        _commandPhones.value = if (on) _commandPhones.value + phoneId else _commandPhones.value - phoneId
        settings.set("commandPhones", _commandPhones.value.joinToString(","))
        hub.commandsChanged()
    }

    fun setTerminalAllowed(phoneId: String, on: Boolean) {
        _terminalPhones.value = if (on) _terminalPhones.value + phoneId else _terminalPhones.value - phoneId
        settings.set("terminalPhones", _terminalPhones.value.joinToString(","))
        // Off ends what's open at once, as on the Mac.
        if (!on) hub.endTerminals(phoneId)
    }

    fun endTerminals(phoneId: String) = hub.endTerminals(phoneId)

    /** An unpaired phone loses what it was allowed. */
    fun forget(phoneId: String) {
        if (phoneId in _commandPhones.value) setCommandsAllowed(phoneId, false)
        if (phoneId in _terminalPhones.value) setTerminalAllowed(phoneId, false)
    }

    fun setRemoteOn(on: Boolean) {
        _remoteOn.value = on
        settings.set("remoteInput", on)
        if (on) recheckHost()
        if (!on) remoteTarget?.release(notify = true)
        hub.infoChanged()
    }

    fun setCommandAsCtrl(on: Boolean) {
        _commandAsCtrl.value = on
        settings.set("remoteCommandAsCtrl", on)
    }

    /** Hands the pointer back to the Mac at once. */
    fun stopRemote() { remoteTarget?.release(notify = true) }

    fun setNotifyOn(on: Boolean) {
        _notifyOn.value = on
        settings.set("notifications", on)
        if (on) recheckHost()
    }

    fun setControlOn(on: Boolean) {
        _controlOn.value = on
        settings.set("control", on)
        if (on) recheckHost()
    }

    private val _closeToTray = MutableStateFlow(settings.bool("closeToTray", true))
    /** Closing the window leaves DyskLink in the tray, where there is one. */
    val closeToTray: StateFlow<Boolean> = _closeToTray.asStateFlow()

    fun setCloseToTray(on: Boolean) {
        _closeToTray.value = on
        settings.set("closeToTray", on)
    }

    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == settings.string("theme") } ?: ThemeMode.System,
    )
    /** Dark, light, or following the system. */
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        settings.set("theme", mode.name)
    }

    fun stopPhoneSound() = hub.stopPhoneSound()

    fun stopRinging() {
        ringer.stop()
        _ringingHere.value = false
    }

    /** Sends what's on this PC's clipboard now, even if automatic sharing is off: to [to], or to every paired device. */
    fun sendClipboardNow(to: String? = null): Boolean {
        val text = runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        }.getOrNull()
        if (text.isNullOrEmpty()) return false
        hub.sendClipboard(text, to)
        return true
    }

    /** Addresses to type on the phone when it can't find this PC by itself. */
    val addresses: List<String> get() = Advertiser.localAddresses().map { "${it.hostAddress}:${Platform.port}" }
}
