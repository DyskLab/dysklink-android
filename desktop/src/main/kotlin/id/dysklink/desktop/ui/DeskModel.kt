package id.dysklink.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.phonelink.android.core.ControlEvent
import id.phonelink.android.core.HubPairing
import id.phonelink.android.core.HubPhone
import id.phonelink.android.core.NotificationItem
import id.dysklink.desktop.GameKeyStore
import id.dysklink.desktop.GameKeysController
import id.dysklink.desktop.HostHints
import id.dysklink.desktop.TrayHint
import id.dysklink.desktop.UpdateState
import id.dysklink.desktop.UsbState
import id.dysklink.desktop.WebcamState
import id.phonelink.android.core.PcToolsState
import id.phonelink.android.core.PhoneContacts
import id.phonelink.android.core.PhoneFolder
import id.phonelink.android.core.PomodoroSettings
import id.phonelink.android.core.WaterSettings
import id.phonelink.android.core.PhoneGallery
import id.phonelink.android.core.PhoneSms
import id.phonelink.android.core.PhoneVideo
import id.phonelink.android.core.SavedCommand
import id.phonelink.android.core.Transfer
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the window shows and can do. The app implements it over DesktopApp; snapshot tests use a fake,
 * so every screen can be drawn without a network.
 */
interface DeskModel {
    val phones: StateFlow<List<HubPhone>>
    val pairing: StateFlow<HubPairing?>
    val notifications: StateFlow<Map<String, List<NotificationItem>>>
    val ringing: StateFlow<Set<String>>
    val transfers: StateFlow<List<Transfer>>
    /** Why phones can't reach this PC, such as the port being taken. */
    val problem: StateFlow<String?>
    val ringingHere: StateFlow<Boolean>
    val soundFrom: StateFlow<String?>
    val clipboardOn: StateFlow<Boolean>
    val notifyOn: StateFlow<Boolean>
    val controlOn: StateFlow<Boolean>
    val computersOn: StateFlow<Boolean>
    val closeToTray: StateFlow<Boolean>
    val themeMode: StateFlow<ThemeMode>
    /** Phone screens and cameras asked for or showing here, the gallery and SMS read so far: by phone. */
    val screens: StateFlow<Map<String, PhoneVideo>>
    val cameras: StateFlow<Map<String, PhoneVideo>>
    val galleries: StateFlow<Map<String, PhoneGallery>>
    val sms: StateFlow<Map<String, PhoneSms>>

    val deviceName: String
    val systemName: String
    val version: String
    val downloadsPath: String
    val addresses: List<String>
    val hasTray: Boolean
    /** On Linux with no tray at all: what Settings says about it. */
    val trayHint: TrayHint? get() = null
    fun openTrayHelp(url: String) {}
    val autostartAvailable: Boolean

    /**
     * What this PC needs from outside DyskLink, such as an X11 session, a missing tool or the firewall: hints for
     * the places each feature is set up. Checked again on [recheckHost].
     */
    val host: StateFlow<HostHints> get() = NO_HINTS
    /** On window focus, and when a feature is turned on or tried again. */
    fun recheckHost() {}
    /** A hint's button: a web page or a system settings screen. */
    fun openLink(url: String) {}
    /** A hint's button that DyskLink handles, such as installing the Windows camera driver. */
    fun hintAction(id: String) {}

    /** "Use as webcam": the phone camera as DyskLink Camera in video apps. Null where this PC can't have one. */
    val webcam: StateFlow<WebcamState>? get() = null
    fun setWebcamOn(on: Boolean) {}
    /** The camera window's turn and mirror, which the webcam takes too. */
    fun setWebcamView(rotation: Int, mirror: Boolean) {}
    fun autostartOn(): Boolean
    /** Returns false if it couldn't be changed. */
    fun setAutostart(on: Boolean): Boolean

    fun setClipboardOn(on: Boolean)
    fun setNotifyOn(on: Boolean)
    fun setControlOn(on: Boolean)
    fun setComputersOn(on: Boolean)
    fun setCloseToTray(on: Boolean)
    fun setThemeMode(mode: ThemeMode)

    fun sendFiles(deviceId: String, files: List<File>)
    /** Sends this PC's clipboard now. False when there's no text on it. */
    fun sendClipboard(deviceId: String): Boolean
    fun ring(deviceId: String, on: Boolean)
    fun dismissNotification(deviceId: String, key: String)
    fun unpair(deviceId: String)
    fun requestPairing(deviceId: String)
    fun submitCode(code: String)
    fun rejectPairing()
    fun stopRinging()
    fun stopPhoneSound()
    fun openDownloads()
    fun open(file: File)

    fun requestScreen(deviceId: String)
    fun stopScreen(deviceId: String)
    /** A tap, swipe, key, or text on the phone's screen while it shows here. */
    fun control(deviceId: String, event: ControlEvent)
    fun requestCamera(deviceId: String)
    fun stopCamera(deviceId: String)
    /** `switch` or `torch`. */
    fun cameraControl(deviceId: String, action: String)
    fun loadGallery(deviceId: String, fresh: Boolean)
    fun downloadGallery(deviceId: String, ids: List<Long>)
    fun loadSms(deviceId: String)
    /** False when the number or text can't be sent. */
    fun sendSms(deviceId: String, address: String, body: String): Boolean

    // What this PC serves phones: its sound, commands, a terminal; and a Mac's mouse and keyboard here.

    /** "Let phones hear this PC". */
    val soundOutOn: StateFlow<Boolean>
    /** The phone hearing this PC now. */
    val soundTo: StateFlow<String?>
    val commands: StateFlow<List<SavedCommand>>
    /** Phones allowed to run commands, and to open a terminal. */
    val commandPhones: StateFlow<Set<String>>
    val terminalPhones: StateFlow<Set<String>>
    /** Terminals open now, by phone. */
    val terminals: StateFlow<Map<String, Int>>
    /** "Let paired computers use their mouse and keyboard here", and Command working as Ctrl. */
    val remoteOn: StateFlow<Boolean>
    val commandAsCtrl: StateFlow<Boolean>
    /** The Mac using this PC's pointer and keys now. */
    val usedBy: StateFlow<String?>

    fun setSoundOutOn(on: Boolean)
    fun stopSoundOut()

    /** "Let phones see this screen", and the phone viewing it now. */
    val screenOutOn: StateFlow<Boolean>
    val screenTo: StateFlow<String?>
    fun setScreenOutOn(on: Boolean)
    fun stopScreenOut()
    /** The phone as a second display: what this PC can do and who it is now. Null where it isn't offered (snapshots set it). */
    val secondDisplay: StateFlow<id.dysklink.desktop.SecondDisplayView>? get() = null
    /** Windows: the switch for the experimental Virtual Display Driver path. */
    fun setExtendExperimentOn(on: Boolean) {}
    /** False when the name or command line is empty or too long. */
    fun addCommand(name: String, command: String): Boolean
    fun removeCommand(id: String)
    fun setCommandsAllowed(deviceId: String, on: Boolean)
    fun setTerminalAllowed(deviceId: String, on: Boolean)
    fun endTerminals(deviceId: String)
    fun setRemoteOn(on: Boolean)
    fun setCommandAsCtrl(on: Boolean)

    /** This PC's own mouse and keyboard on a Mac: the settings and whether it's there now. Null where it isn't offered. */
    val sourceInput: StateFlow<id.dysklink.desktop.SourceView>? get() = null
    fun setSourceOn(on: Boolean) {}
    fun setSourceMac(deviceId: String) {}
    fun setSourceEdge(edge: id.dysklink.desktop.ScreenEdge) {}
    fun setCtrlAsCommand(on: Boolean) {}
    /** Brings this PC's pointer back from the Mac at once. */
    fun stopSource() {}
    fun stopRemote()

    // The phone's contacts and files.

    val contacts: StateFlow<Map<String, PhoneContacts>>
    val folders: StateFlow<Map<String, PhoneFolder>>
    fun loadContacts(deviceId: String)
    /** Opens a folder of the phone's storage, "" for the top; [hidden] changes whether hidden files show. */
    fun openPhoneFolder(deviceId: String, path: String, hidden: Boolean? = null)
    /** Files of the phone's storage, by path; each arrives in the downloads folder. */
    fun downloadPhoneFiles(deviceId: String, paths: List<String>)
    /** Puts text, such as a phone number, on this PC's clipboard. */
    fun copyText(text: String)

    // Clipboard History and Battery on the Tools page. Null where this PC doesn't have them (snapshots set them).

    val clipHistory: StateFlow<id.dysklink.desktop.ClipHistoryView>? get() = null
    fun clipAction(action: id.dysklink.desktop.ClipAction) {}
    /** The picker's Enter: on the clipboard, then pasted into the app in front. */
    fun clipPaste(id: String) {}
    fun openClipboardPicker() {}
    /** An image entry's PNG. */
    fun clipImageFile(entry: id.dysklink.desktop.ClipEntry): File? = null
    /** A file dialog for Ditto.db, when it isn't where Ditto keeps it. */
    fun pickDittoFile(): File? = null
    /** Null on a PC without a battery, and the section is hidden. */
    val battery: StateFlow<id.dysklink.desktop.BatteryView?>? get() = null
    val isWindows: Boolean get() = id.dysklink.desktop.Platform.isWindows
    /** PC care on the Tools page: cleaner, duplicates, uninstall, startup. Null where it isn't offered. */
    val pcCare: id.dysklink.desktop.PcCareModel? get() = null

    /** The Launcher, Window tidy and the tray menu's parts. Null where this PC doesn't have them. */
    val launcher: id.dysklink.desktop.LauncherModel? get() = null
    val windowTidy: StateFlow<id.dysklink.desktop.WindowTidyView>? get() = null
    fun tidyAction(action: id.dysklink.desktop.TidyAction) {}
    val trayMenu: StateFlow<id.dysklink.desktop.TrayMenuConfig>? get() = null
    fun setTrayMenu(config: id.dysklink.desktop.TrayMenuConfig) {}

    // The notch tools on this PC: Todo, water, and Pomodoro.

    val tools: StateFlow<PcToolsState>
    /** False when the title is empty or the list is full. */
    fun addTodo(text: String): Boolean
    fun toggleTodo(id: String)
    fun deleteTodo(id: String)
    fun renameTodo(id: String, text: String)
    fun clearDoneTodos()
    fun drink()
    fun undoDrink()
    fun setWater(settings: WaterSettings)
    fun togglePomodoro()
    fun resetPomodoro()
    fun skipPomodoro()
    fun setPomodoro(settings: PomodoroSettings)

    // Phones over a USB cable, through adb.

    val usb: StateFlow<UsbState>
    /** "Connect phones over USB". Off takes the reverse ports away and stops checking. */
    fun setUsbOn(on: Boolean)
    /** Fetches Google's platform tools into DyskLink's folder. */
    fun downloadAdb()

    // Game keys on the phone's screen.

    /** Where game profiles are kept; in memory unless the app gives its data folder. */
    val gameKeyStore: GameKeyStore get() = MEMORY_GAME_KEYS
    /**
     * Whether looking with the mouse can hold the pointer (Robot moves it back to the middle: Windows and X11). Under
     * Wayland it can't, and the right mouse button is held and dragged to look instead.
     */
    val gamePointerLock: Boolean get() = false
    /** X11 repeats held keys as a release and a press, which game keys must not take as letting go. */
    val gameKeysDebounce: Boolean get() = false

    // Updates of this app.

    val update: StateFlow<UpdateState>
    fun checkForUpdates()
    /** Opens the installer for this system in the browser. Nothing installs by itself. */
    fun downloadUpdate()
    fun skipUpdate()
}

private val MEMORY_GAME_KEYS = GameKeyStore(null)
private val NO_HINTS: StateFlow<HostHints> = kotlinx.coroutines.flow.MutableStateFlow(HostHints())

/** How the camera window shows the picture. Kept here so the window and the tab agree. */
class CameraView {
    var mirror by mutableStateOf(false)
    /** Turned by hand, on top of the phone's own rotation. */
    var rotation by mutableStateOf(0)
    var onTop by mutableStateOf(false)
    /** Only the picture, for window capture in OBS and the like. */
    var clean by mutableStateOf(false)
}

/** The camera window's title. It stays the same while the camera runs, so OBS keeps finding the window. */
fun cameraWindowTitle(phoneName: String) = "$phoneName camera - DyskLink"

enum class Page { Device, Tools, History, Settings }

enum class ToolsView { Tools, Desk, Care }

/** What the window is showing. Kept outside the window so the tray can open a device. */
class UiState(page: Page = Page.Device, selectedId: String? = null, tab: String = "overview") {
    var page by mutableStateOf(page)
    var selectedId by mutableStateOf(selectedId)
    var tab by mutableStateOf(tab)
    /** The phone whose screen shows in its own window, and whose camera does. */
    var screenWindow by mutableStateOf<String?>(null)
    var cameraWindow by mutableStateOf<String?>(null)
    /** Turned by hand, for the screen in the tab and its window. */
    var screenRotation by mutableStateOf(0)
    val camera = CameraView()
    /** The Tools page shows the notch tools, or PC care. */
    var toolsView by mutableStateOf(ToolsView.Tools)
    /** A number to start an SMS to, from the contacts: the Messages tab opens a new message with it. */
    var smsTo by mutableStateOf<String?>(null)

    private val games = mutableMapOf<String, GameKeysController>()

    /** The game keys of a phone's screen, one for the tab and the pop-out window alike. */
    fun gameKeys(deviceId: String, model: DeskModel): GameKeysController =
        games.getOrPut(deviceId) { GameKeysController(deviceId, model.gameKeyStore) }.also { game ->
            game.send = { model.control(deviceId, it) }
            game.debounceReleases = model.gameKeysDebounce
        }

    /** Stops the phone's screen here, lifting any game fingers first while the phone still takes them. */
    fun stopScreen(model: DeskModel, deviceId: String) {
        games[deviceId]?.release()
        model.stopScreen(deviceId)
    }

    fun openDevice(id: String) {
        if (id != selectedId) tab = "overview"
        selectedId = id
        page = Page.Device
    }
}
