package id.dysklink.desktop

import androidx.compose.runtime.LaunchedEffect
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
import id.dysklink.desktop.ui.DeskModel
import id.dysklink.desktop.ui.ScreenWindowContent
import id.dysklink.desktop.ui.DyskTheme
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

private const val MIN_WIDTH = 860
private const val MIN_HEIGHT = 600

@OptIn(FlowPreview::class)
fun main(args: Array<String>) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val app = DesktopApp(scope)
    app.start()
    // Some Linux desktops, such as GNOME without an extension, have no tray. There a hidden window could never be
    // opened again, so --hidden is ignored and closing the window quits.
    val hasTray = runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false)
    // Started at login with --hidden, it waits in the tray.
    val startHidden = "--hidden" in args && hasTray
    val model = AppModel(app, hasTray)
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
        val quit = { app.stop(); exitApplication() }
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
        ui.screenWindow?.let { id ->
            val name = phones.firstOrNull { it.id == id }?.name ?: "Phone"
            Window(
                onCloseRequest = { ui.screenWindow = null; model.stopScreen(id) },
                title = "$name screen - DyskLink", icon = icon,
                state = rememberWindowState(size = DpSize(460.dp, 900.dp)),
            ) {
                DyskTheme(dark) { ScreenWindowContent(model, ui, id) }
            }
        }
        ui.cameraWindow?.let { id ->
            val name = phones.firstOrNull { it.id == id }?.name ?: "Phone"
            // The title stays the same while it runs, so OBS keeps finding the window.
            Window(
                onCloseRequest = { ui.cameraWindow = null; model.stopCamera(id) },
                title = "$name camera - DyskLink", icon = icon, alwaysOnTop = ui.camera.onTop,
                state = rememberWindowState(size = DpSize(960.dp, 640.dp)),
            ) {
                DyskTheme(dark) { CameraWindowContent(model, ui, id, name) }
            }
        }

        LaunchedEffect(Unit) {
            app.popups.collect { trayState.sendNotification(Notification(it.title, it.message, Notification.Type.None)) }
        }
        // Hiding the window ends a screen shown in it; one in its own window goes on.
        LaunchedEffect(visible) {
            if (!visible) app.hub.screens.value.keys.filter { it != ui.screenWindow }.forEach(model::stopScreen)
        }
        // The code has to be seen, so the window comes forward when a phone starts pairing.
        LaunchedEffect(pairing?.phoneId) { if (pairing != null) visible = true }
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.placement, windowState.position, windowState.size) }.debounce(500).collect { saveBounds(app.settings, windowState) }
        }

        val trayStatus by app.trayStatus.collectAsState()
        val tools by app.tools.collectAsState()
        if (hasTray) Tray(
            icon = trayIcon,
            state = trayState,
            // While the Pomodoro is started its countdown shows here, as in the Mac's menu bar.
            tooltip = trayStatus?.let { "DyskLink: $it" }
                ?: phones.firstOrNull { it.isPaired && it.isConnected }?.let { "DyskLink: ${it.name} connected" } ?: "DyskLink",
            onAction = { visible = true },
            menu = {
                Item("Open DyskLink", onClick = { visible = true })
                Separator()
                trayStatus?.let { Item(it, enabled = false, onClick = {}) }
                Item(
                    when {
                        tools.pomodoro.state.isRunning -> "Pause focus"
                        tools.pomodoro.state.isStarted -> "Resume focus"
                        else -> "Start focus"
                    },
                    onClick = { model.togglePomodoro() },
                )
                Item("Drink a glass of water", onClick = { model.drink() })
                Item("Tools…", onClick = { ui.page = id.dysklink.desktop.ui.Page.Tools; visible = true })
                val paired = phones.filter { it.isPaired }
                if (paired.isNotEmpty()) {
                    Separator()
                    paired.forEach { device ->
                        Item(trayLabel(device), onClick = { ui.openDevice(device.id); visible = true })
                    }
                }
                Separator()
                val connected = paired.filter { it.isConnected }
                when (connected.size) {
                    0 -> Item("Send file…", enabled = false, onClick = {})
                    1 -> Item("Send file to ${connected[0].name}…", onClick = { model.sendFiles(connected[0].id, pickFiles(null, connected[0].name)) })
                    else -> Menu("Send file to") {
                        connected.forEach { device -> Item("${device.name}…", onClick = { model.sendFiles(device.id, pickFiles(null, device.name)) }) }
                    }
                }
                Item("Send clipboard", enabled = connected.isNotEmpty(), onClick = { app.sendClipboardNow() })
                Separator()
                Item("Quit DyskLink", onClick = quit)
            },
        )

        Window(
            onCloseRequest = { if (hasTray && closeToTray) visible = false else quit() },
            visible = visible,
            title = "DyskLink",
            icon = icon,
            state = windowState,
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT) }
            DyskTheme(dark) {
                MainScreen(model, ui, pickFiles = { pickFiles(window, phones.firstOrNull { it.id == ui.selectedId }?.name) })
            }
        }
    }
}

private fun resourcePainter(path: String) = BitmapPainter(DesktopApp::class.java.getResourceAsStream(path)!!.use(::loadImageBitmap))

private fun trayLabel(device: HubPhone): String {
    val battery = device.info?.batteryPercent
    return when {
        !device.isConnected -> "${device.name}: offline"
        battery != null -> "${device.name}: $battery%${if (device.info?.isCharging == true) ", charging" else ""}"
        else -> "${device.name}: connected"
    }
}

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
private class AppModel(private val app: DesktopApp, override val hasTray: Boolean) : DeskModel {
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

    override val deviceName get() = Platform.deviceName
    override val systemName get() = Platform.model
    override val version get() = Platform.version
    override val downloadsPath: String get() = Platform.downloadsDir.path
    override val addresses get() = app.addresses
    override val autostartAvailable get() = Autostart.isAvailable

    override fun controlNotes(): List<String> = listOfNotNull(
        "Wayland lets the phone only use the media remote. Log in with an X11 session for the touchpad and keyboard.".takeIf { app.input.isWayland },
        app.input.typingNote,
    )
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
    override fun remoteNotes(): List<String> = listOfNotNull(
        if (app.input.isWayland) "Wayland doesn't let apps move the pointer or type into other apps. Log in with an X11 session to use a Mac's mouse and keyboard here." else null,
        if (!app.input.isWayland && !app.remoteAvailable) "This PC doesn't let DyskLink move the pointer." else null,
    )
    override fun setSoundOutOn(on: Boolean) = app.setSoundOutOn(on)
    override fun stopSoundOut() = app.stopSoundOut()
    override val screenOutOn = app.screenOutOn
    override val screenTo = app.hub.screenTo
    override fun screenNotes(): List<String> = listOfNotNull(
        "Wayland doesn't let apps capture the screen yet. Log in with an X11 session to show this screen on a phone.".takeIf { app.input.isWayland },
    )
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

    override val update = app.updater.state
    override fun checkForUpdates() = app.updater.checkNow()
    override fun downloadUpdate() = app.updater.download()
    override fun skipUpdate() = app.updater.skip()
}
