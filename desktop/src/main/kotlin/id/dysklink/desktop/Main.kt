package id.dysklink.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import id.dysklink.desktop.ui.CameraWindowContent
import id.dysklink.desktop.ui.cameraWindowTitle
import id.dysklink.desktop.ui.DeskModel
import id.dysklink.desktop.ui.ScreenWindowContent
import id.dysklink.desktop.ui.DyskTheme
import id.dysklink.desktop.ui.GamePanelWidth
import id.dysklink.desktop.ui.MainScreen
import id.dysklink.desktop.ui.SystemTheme
import id.dysklink.desktop.ui.ThemeMode
import id.dysklink.desktop.ui.UiState
import id.phonelink.android.core.ControlEvent
import id.phonelink.android.core.HubPhone
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

private const val MIN_WIDTH = 860
private const val MIN_HEIGHT = 600

@OptIn(FlowPreview::class)
fun main(args: Array<String>) {
    // `dysklink --clipboard`, `--launcher` or `--window left-half`, from a desktop shortcut: the running DyskLink does
    // it, and this start ends.
    val command = SingleInstance.commandFor(args)
    if (command != null && SingleInstance.send(Platform.dataDir, command)) return
    val wantsPicker = command == SingleInstance.CLIPBOARD
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val app = DesktopApp(scope)
    app.start()
    // Clicking the StatusNotifierItem icon arrives on the tray's thread; the window picks it up from here.
    val trayClicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    // Linux shows a StatusNotifierItem when the desktop has a watcher for them, else the old XEmbed tray if there is one.
    // Some desktops, such as GNOME without the AppIndicator extension, have neither. There a hidden window could never
    // be opened again, so --hidden is ignored and closing the window quits.
    val trayHost = TrayHosts.choose(
        isLinux = Platform.isLinux,
        statusNotifier = { LinuxTray.start(LinuxTray.sessionAddress(), onActivate = { trayClicks.tryEmit(Unit) }) },
        awtSupported = { java.awt.SystemTray.isSupported() },
    )
    val hasTray = trayHost != null
    // Started at login with --hidden, it waits in the tray.
    val startHidden = "--hidden" in args && hasTray
    val model = AppModel(app, hasTray, TrayHints.pick(Platform.isLinux, hasTray, System.getenv("XDG_CURRENT_DESKTOP")))
    application {
        val icon = remember { resourcePainter("/icon.png") }
        val trayIcon = remember { resourcePainter("/tray.png") }
        var visible by remember { mutableStateOf(!startHidden) }
        val ui = remember { UiState() }
        val trayState = rememberTrayState()
        val phones by app.hub.phones.collectAsState()
        val pairing by app.hub.pairing.collectAsState()
        val closeToTray by app.closeToTray.collectAsState()
        val windowState = rememberWindowState(placement = WindowPlacement.Floating, position = savedPosition(app.settings), size = savedSize(app.settings))
        val quit = { trayHost?.close(); app.stop(); exitApplication() }
        var raise by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { trayClicks.collect { visible = true; raise++ } }
        LaunchedEffect(Unit) { app.showRequests.collect { visible = true; raise++ } }
        // The clipboard picker, in a small window of its own over the app being typed in.
        var pickerOpen by remember { mutableStateOf(false) }
        var pickerRound by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            if (wantsPicker) app.clipHistory.requestPicker()
            app.clipHistory.openPicker.collect { pickerOpen = true; pickerRound++ }
        }
        // The Launcher, the same way.
        var launcherOpen by remember { mutableStateOf(false) }
        var launcherRound by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            if (command == SingleInstance.LAUNCHER) app.launcher.open()
            app.launcher.openRequests.collect { launcherOpen = true; launcherRound++ }
        }
        LaunchedEffect(Unit) {
            app.uiRequests.collect { request ->
                when {
                    request == "open" -> Unit
                    request == "settings" -> ui.page = id.dysklink.desktop.ui.Page.Settings
                    request == "tools" -> { ui.page = id.dysklink.desktop.ui.Page.Tools; ui.toolsView = id.dysklink.desktop.ui.ToolsView.Tools }
                    request == "pccare" -> { ui.page = id.dysklink.desktop.ui.Page.Tools; ui.toolsView = id.dysklink.desktop.ui.ToolsView.Care }
                    request.startsWith("send:") -> {
                        val id = request.removePrefix("send:")
                        model.sendFiles(id, pickFiles(null, phones.firstOrNull { it.id == id }?.name))
                        return@collect
                    }
                }
                visible = true; raise++
            }
        }
        val mode by app.themeMode.collectAsState()
        var systemLight by remember { mutableStateOf<Boolean?>(null) }
        // The system theme can change while DyskLink runs, so it's read again now and then.
        LaunchedEffect(mode) {
            while (mode == ThemeMode.System) {
                systemLight = withContext(Dispatchers.IO) { SystemTheme.prefersLight() }
                delay(30_000)
            }
        }
        val dark = when (mode) {
            ThemeMode.Dark -> true
            ThemeMode.Light -> false
            ThemeMode.System -> systemLight != true
        }
        // The phone's screen and camera in their own windows. A window closes when the phone goes, and closing it stops that.
        val screens by app.hub.screens.collectAsState()
        val cameras by app.hub.cameras.collectAsState()
        LaunchedEffect(screens.keys) { ui.screenWindow?.let { if (it !in screens) ui.screenWindow = null } }
        LaunchedEffect(cameras.keys) { ui.cameraWindow?.let { if (it !in cameras) ui.cameraWindow = null } }
        // The webcam turns and mirrors as the camera window does.
        LaunchedEffect(ui.camera.rotation, ui.camera.mirror) { model.setWebcamView(ui.camera.rotation, ui.camera.mirror) }
        ui.screenWindow?.let { id ->
            val name = phones.firstOrNull { it.id == id }?.name ?: "Phone"
            val screenState = rememberWindowState(size = DpSize(460.dp, 900.dp))
            // Game keys add their panel beside the picture; the window grows by its width, so the picture keeps its size.
            val gameOn = ui.gameKeys(id, model).enabled
            var widened by remember(id) { mutableStateOf(false) }
            LaunchedEffect(gameOn) {
                if (gameOn == widened) return@LaunchedEffect
                val extra = GamePanelWidth + 9.dp
                screenState.size = DpSize(screenState.size.width + if (gameOn) extra else -extra, screenState.size.height)
                widened = gameOn
            }
            Window(
                onCloseRequest = { ui.screenWindow = null; ui.stopScreen(model, id) },
                title = "$name screen - DyskLink", icon = icon,
                state = screenState,
            ) {
                DyskTheme(dark) { ScreenWindowContent(model, ui, id) }
            }
        }
        ui.cameraWindow?.let { id ->
            val name = phones.firstOrNull { it.id == id }?.name ?: "Phone"
            // The title stays the same while it runs, so OBS keeps finding the window.
            Window(
                onCloseRequest = { ui.cameraWindow = null; model.stopCamera(id) },
                title = cameraWindowTitle(name), icon = icon, alwaysOnTop = ui.camera.onTop,
                state = rememberWindowState(size = DpSize(960.dp, 640.dp)),
            ) {
                DyskTheme(dark) { CameraWindowContent(model, ui, id, name) }
            }
        }

        LaunchedEffect(Unit) {
            app.popups.collect {
                // Without a tray, Linux shows it over D-Bus; the AWT balloon only exists with an AWT tray.
                if (trayHost?.notify(it.title, it.message) != true && !(trayHost == null && app.notifyDesktop(it.title, it.message))) {
                    trayState.sendNotification(Notification(it.title, it.message, Notification.Type.None))
                }
            }
        }
        // Hiding the window ends a screen shown in it; one in its own window goes on.
        LaunchedEffect(visible) {
            if (!visible) app.hub.screens.value.keys.filter { it != ui.screenWindow }.forEach { ui.stopScreen(model, it) }
        }
        // The code has to be seen, so the window comes forward when a phone starts pairing.
        LaunchedEffect(pairing?.phoneId) { if (pairing != null) visible = true }
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.placement, windowState.position, windowState.size) }.debounce(500).collect { saveBounds(app.settings, windowState) }
        }

        if (pickerOpen) {
            val pickerState = remember(pickerRound) {
                WindowState(position = WindowPosition(Alignment.Center), size = DpSize(540.dp, 460.dp))
            }
            Window(
                onCloseRequest = { pickerOpen = false },
                state = pickerState, title = "Clipboard history - DyskLink", icon = icon,
                undecorated = true, alwaysOnTop = true, resizable = false,
            ) {
                LaunchedEffect(pickerRound) {
                    window.toFront()
                    window.requestFocus()
                    // Clicking another window closes it, once it had the focus.
                    window.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
                        override fun windowLostFocus(e: java.awt.event.WindowEvent?) { pickerOpen = false }
                    })
                }
                DyskTheme(dark) { id.dysklink.desktop.ui.ClipPickerContent(model, onClose = { pickerOpen = false }) }
            }
        }

        if (launcherOpen) {
            val launcherState = remember(launcherRound) {
                WindowState(position = WindowPosition(Alignment.Center), size = DpSize(660.dp, 470.dp))
            }
            Window(
                onCloseRequest = { launcherOpen = false },
                state = launcherState, title = "Launcher - DyskLink", icon = icon,
                undecorated = true, alwaysOnTop = true, resizable = false,
            ) {
                LaunchedEffect(launcherRound) {
                    window.toFront()
                    window.requestFocus()
                    window.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
                        override fun windowLostFocus(e: java.awt.event.WindowEvent?) { launcherOpen = false }
                    })
                }
                DyskTheme(dark) {
                    androidx.compose.runtime.key(launcherRound) { id.dysklink.desktop.ui.LauncherContent(app.launcher, onClose = { launcherOpen = false }) }
                }
            }
        }

        val trayStatus by app.trayStatus.collectAsState()
        val batteryView by app.battery.view.collectAsState()
        val clipView by app.clipHistory.view.collectAsState()
        val tools by app.tools.collectAsState()
        val trayTooltip = (trayStatus?.let { "DyskLink: $it" }
            ?: phones.firstOrNull { it.isPaired && it.isConnected }?.let { "DyskLink: ${it.name} connected" } ?: "DyskLink") +
            (batteryView?.let { "\nBattery ${it.reading.trayText}" } ?: "")
        val trayConfig by app.trayMenu.collectAsState()
        val tidyView by app.windowTidy.view.collectAsState()
        val launcherView by app.launcher.view.collectAsState()
        val trayEntries = TrayMenu.build(
            trayConfig,
            TrayMenuState(
                phones, trayStatus, tools.pomodoro.state.isRunning, tools.pomodoro.state.isStarted,
                battery = batteryView?.reading?.trayText, clipboardHistory = clipView.settings.enabled, pcCare = true,
                launcher = launcherView.enabled,
                layouts = if (tidyView.enabled && tidyView.supported) Layouts.forMonitors(tidyView.layouts, tidyView.monitorKey).map { it.id to it.name } else emptyList(),
            ),
            TrayMenuActions(
                open = { visible = true },
                openTools = { ui.page = id.dysklink.desktop.ui.Page.Tools; ui.toolsView = id.dysklink.desktop.ui.ToolsView.Tools; visible = true },
                openDevice = { id -> ui.openDevice(id); visible = true },
                togglePomodoro = model::togglePomodoro, drink = model::drink,
                sendFiles = { device -> model.sendFiles(device.id, pickFiles(null, device.name)) },
                sendClipboard = { app.sendClipboardNow() },
                clipboardHistory = { app.clipHistory.requestPicker() },
                pcCare = { ui.page = id.dysklink.desktop.ui.Page.Tools; ui.toolsView = id.dysklink.desktop.ui.ToolsView.Care; visible = true },
                launcher = { app.launcher.open() },
                restoreLayout = { id -> app.windowTidy.act(TidyAction.Restore(id)) },
                quit = quit,
            ),
        )
        if (trayHost === AwtTrayHost) Tray(
            icon = trayIcon,
            state = trayState,
            // While the Pomodoro is started its countdown shows here, as in the Mac's menu bar.
            tooltip = trayTooltip,
            onAction = { visible = true },
            menu = { trayItems(trayEntries) },
        )
        // The StatusNotifierItem gets the same menu; it only sends it over D-Bus when something in it changed.
        SideEffect { trayHost?.update(trayEntries, trayTooltip) }

        Window(
            onCloseRequest = { if (hasTray && closeToTray) visible = false else quit() },
            visible = visible,
            title = "DyskLink",
            icon = icon,
            state = windowState,
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
                // A tool installed or a setting changed while the window was away: the hints check again.
                window.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                        app.recheckHost()
                        // Back from an uninstaller or Settings: PC care shows what's really there now.
                        model.pcCare?.onFocus()
                    }
                })
            }
            // A click on the StatusNotifierItem icon also brings an open window to the front.
            LaunchedEffect(raise) { if (raise > 0) { window.toFront(); window.requestFocus() } }
            DyskTheme(dark) {
                MainScreen(model, ui, pickFiles = { pickFiles(window, phones.firstOrNull { it.id == ui.selectedId }?.name) })
            }
        }
    }
}

@Composable
private fun MenuScope.trayItems(entries: List<TrayEntry>) {
    entries.forEach { entry ->
        when (entry) {
            is TrayEntry.Item -> Item(entry.label, enabled = entry.enabled, onClick = entry.onClick)
            is TrayEntry.Submenu -> Menu(entry.label) { trayItems(entry.children) }
            TrayEntry.Separator -> Separator()
        }
    }
}

private fun resourcePainter(path: String) = BitmapPainter(DesktopApp::class.java.getResourceAsStream(path)!!.use(::loadImageBitmap))

private fun pickFiles(owner: Frame?, deviceName: String?): List<File> {
    val dialog = FileDialog(owner, deviceName?.let { "Send to $it" } ?: "Send files", FileDialog.LOAD).apply { isMultipleMode = true; isVisible = true }
    return dialog.files.orEmpty().toList()
}

/** Where the window was last time, if that place is still on a screen. */
private fun savedPosition(settings: Settings): WindowPosition {
    val x = settings.int("window.x") ?: return WindowPosition(Alignment.Center)
    val y = settings.int("window.y") ?: return WindowPosition(Alignment.Center)
    val onScreen = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { it.defaultConfiguration.bounds.intersects(Rectangle(x, y, 200, 100)) }
    }.getOrDefault(false)
    return if (onScreen) WindowPosition(x.dp, y.dp) else WindowPosition(Alignment.Center)
}

private fun savedSize(settings: Settings): DpSize {
    val width = settings.int("window.width")?.coerceAtLeast(MIN_WIDTH) ?: 1040
    val height = settings.int("window.height")?.coerceAtLeast(MIN_HEIGHT) ?: 700
    return DpSize(width.dp, height.dp)
}

private fun saveBounds(settings: Settings, state: WindowState) {
    // A maximized or full-screen window keeps the size it had before.
    if (state.placement != WindowPlacement.Floating) return
    val position = state.position
    if (position.isSpecified) {
        settings.set("window.x", position.x.value.toInt().toString())
        settings.set("window.y", position.y.value.toInt().toString())
    }
    settings.set("window.width", state.size.width.value.toInt().toString())
    settings.set("window.height", state.size.height.value.toInt().toString())
}

/** The window's view of [DesktopApp]. */
private class AppModel(private val app: DesktopApp, override val hasTray: Boolean, override val trayHint: TrayHint?) : DeskModel {
    override fun openTrayHelp(url: String) = Platform.browse(url)
    override val phones = app.hub.phones
    override val pairing = app.hub.pairing
    override val notifications = app.hub.notifications
    override val ringing = app.hub.ringing
    override val transfers = app.hub.fileTransfers.transfers
    override val problem = app.problem
    override val ringingHere = app.ringingHere
    override val soundFrom = app.soundFrom
    override val clipboardOn = app.clipboardOn
    override val notifyOn = app.notifyOn
    override val controlOn = app.controlOn
    override val computersOn = app.computersOn
    override val closeToTray = app.closeToTray
    override val themeMode = app.themeMode
    override val screens = app.hub.screens
    override val cameras = app.hub.cameras
    override val galleries = app.hub.galleries
    override val sms = app.hub.sms
    override val usb = app.usb.state
    override fun setUsbOn(on: Boolean) = app.usb.setEnabled(on)
    override fun downloadAdb() = app.usb.downloadAdb()

    override val deviceName get() = Platform.deviceName
    override val systemName get() = Platform.model
    override val version get() = Platform.version
    override val downloadsPath: String get() = Platform.downloadsDir.path
    override val addresses get() = app.addresses
    override val autostartAvailable get() = Autostart.isAvailable

    override val host = app.host
    override fun recheckHost() = app.recheckHost()
    override fun openLink(url: String) = Platform.browse(url)
    override fun hintAction(id: String) { app.hintAction(id) }
    override val webcam = app.webcam.state.takeIf { app.webcam.available }
    override fun setWebcamOn(on: Boolean) { app.setWebcamOn(on) }
    override fun setWebcamView(rotation: Int, mirror: Boolean) = app.webcam.setView(rotation, mirror)
    override fun autostartOn() = Autostart.isOn()
    override fun setAutostart(on: Boolean) = Autostart.set(on)

    override fun setClipboardOn(on: Boolean) = app.setClipboardOn(on)
    override fun setNotifyOn(on: Boolean) = app.setNotifyOn(on)
    override fun setControlOn(on: Boolean) = app.setControlOn(on)
    override fun setComputersOn(on: Boolean) = app.setComputersOn(on)
    override fun setCloseToTray(on: Boolean) = app.setCloseToTray(on)
    override fun setThemeMode(mode: ThemeMode) = app.setThemeMode(mode)

    override fun sendFiles(deviceId: String, files: List<File>) = files.filter { it.isFile }.forEach { app.hub.sendFile(deviceId, it) }
    override fun sendClipboard(deviceId: String) = app.sendClipboardNow(deviceId)
    override fun ring(deviceId: String, on: Boolean) = app.hub.ring(deviceId, on)
    override fun dismissNotification(deviceId: String, key: String) = app.hub.dismissNotification(deviceId, key)
    override fun unpair(deviceId: String) {
        app.forget(deviceId)
        app.hub.unpair(deviceId)
    }
    override fun requestPairing(deviceId: String) = app.hub.requestPairing(deviceId)
    override fun submitCode(code: String) = app.hub.submitCode(code)
    override fun rejectPairing() = app.hub.rejectPairing()
    override fun stopRinging() = app.stopRinging()
    override fun stopPhoneSound() = app.stopPhoneSound()
    override fun openDownloads() = Platform.open(Platform.downloadsDir.apply { mkdirs() })
    override fun open(file: File) = Platform.open(file)

    override fun requestScreen(deviceId: String) = app.hub.requestScreen(deviceId)
    override fun stopScreen(deviceId: String) = app.hub.stopScreen(deviceId)
    override fun control(deviceId: String, event: ControlEvent) = app.hub.control(deviceId, event)
    override val gameKeyStore = GameKeyStore(Platform.dataDir)
    override val gamePointerLock: Boolean get() = app.input.route == InputRoute.ROBOT
    override val gameKeysDebounce: Boolean = Platform.isLinux
    override fun requestCamera(deviceId: String) = app.hub.requestCamera(deviceId)
    override fun stopCamera(deviceId: String) = app.hub.stopCamera(deviceId)
    override fun cameraControl(deviceId: String, action: String) = app.hub.cameraControl(deviceId, action)
    override fun loadGallery(deviceId: String, fresh: Boolean) = app.hub.loadGallery(deviceId, fresh)
    override fun downloadGallery(deviceId: String, ids: List<Long>) = app.hub.downloadGallery(deviceId, ids)
    override fun loadSms(deviceId: String) = app.hub.loadSms(deviceId)
    override fun sendSms(deviceId: String, address: String, body: String) = app.hub.sendSms(deviceId, address, body) != null

    override val soundOutOn = app.soundOutOn
    override val soundTo = app.hub.soundTo
    override val commands = app.commandStore.commands
    override val commandPhones = app.commandPhones
    override val terminalPhones = app.terminalPhones
    override val terminals = app.hub.terminals.open
    override val remoteOn = app.remoteOn
    override val commandAsCtrl = app.commandAsCtrl
    override val usedBy = app.usedBy
    override fun setSoundOutOn(on: Boolean) = app.setSoundOutOn(on)
    override fun stopSoundOut() = app.stopSoundOut()
    override val screenOutOn = app.screenOutOn
    override val screenTo = app.hub.screenTo
    override fun setScreenOutOn(on: Boolean) = app.setScreenOutOn(on)
    override fun stopScreenOut() = app.stopScreenOut()
    override fun addCommand(name: String, command: String) = app.addCommand(name, command)
    override fun removeCommand(id: String) = app.removeCommand(id)
    override fun setCommandsAllowed(deviceId: String, on: Boolean) = app.setCommandsAllowed(deviceId, on)
    override fun setTerminalAllowed(deviceId: String, on: Boolean) = app.setTerminalAllowed(deviceId, on)
    override fun endTerminals(deviceId: String) = app.endTerminals(deviceId)
    override fun setRemoteOn(on: Boolean) = app.setRemoteOn(on)
    override fun setCommandAsCtrl(on: Boolean) = app.setCommandAsCtrl(on)
    override fun stopRemote() = app.stopRemote()

    override val contacts = app.hub.contacts
    override val folders = app.hub.folders
    override fun loadContacts(deviceId: String) = app.hub.loadContacts(deviceId)
    override fun openPhoneFolder(deviceId: String, path: String, hidden: Boolean?) = app.hub.openPhoneFolder(deviceId, path, hidden)
    override fun downloadPhoneFiles(deviceId: String, paths: List<String>) = app.hub.downloadPhoneFiles(deviceId, paths)
    override fun copyText(text: String) {
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
    }

    override val tools = app.tools
    override fun addTodo(text: String) = app.tools { addTodo(text) }
    override fun toggleTodo(id: String) { app.tools { toggleTodo(id) } }
    override fun deleteTodo(id: String) { app.tools { deleteTodo(id) } }
    override fun renameTodo(id: String, text: String) { app.tools { renameTodo(id, text) } }
    override fun clearDoneTodos() { app.tools { clearDone() } }
    override fun drink() { app.tools { drink() } }
    override fun undoDrink() { app.tools { undoDrink() } }
    override fun setWater(settings: id.phonelink.android.core.WaterSettings) { app.tools { setWater(settings) } }
    override fun togglePomodoro() { app.tools { togglePomodoro() } }
    override fun resetPomodoro() { app.tools { resetPomodoro() } }
    override fun skipPomodoro() { app.tools { skipPomodoro() } }
    override fun setPomodoro(settings: id.phonelink.android.core.PomodoroSettings) { app.tools { setPomodoro(settings) } }

    override val clipHistory = app.clipHistory.view
    override fun clipAction(action: ClipAction) = app.clipHistory.act(action)
    override fun clipPaste(id: String) = app.clipHistory.paste(id)
    override fun openClipboardPicker() = app.clipHistory.requestPicker()
    override fun clipImageFile(entry: ClipEntry): File? = app.clipHistory.store.imageFile(entry)
    override fun pickDittoFile(): File? {
        val dialog = FileDialog(null as Frame?, "Choose Ditto.db", FileDialog.LOAD).apply { file = "Ditto.db"; isVisible = true }
        return dialog.files.orEmpty().firstOrNull()
    }
    override val battery = app.battery.view
    override val pcCare: PcCareModel? by lazy {
        PcCareModel(RealCareSystem(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default), Platform.dataDir)
    }

    override val launcher: LauncherModel = app.launcher
    override val windowTidy = app.windowTidy.view
    override fun tidyAction(action: TidyAction) = app.windowTidy.act(action)
    override val trayMenu = app.trayMenu
    override fun setTrayMenu(config: TrayMenuConfig) = app.setTrayMenu(config)

    override val update = app.updater.state
    override fun checkForUpdates() = app.updater.checkNow()
    override fun downloadUpdate() = app.updater.download()
    override fun skipUpdate() = app.updater.skip()
}
