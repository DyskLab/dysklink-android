package id.dysklink.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.phonelink.android.core.ControlEvent
import id.phonelink.android.core.HubPairing
import id.phonelink.android.core.HubPhone
import id.phonelink.android.core.NotificationItem
import id.dysklink.desktop.UpdateState
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
    val autostartAvailable: Boolean

    /** What limits phone control on this PC, such as Wayland. Only shown while control is on. */
    fun controlNotes(): List<String>
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
    /** Why a Mac's mouse and keyboard can't work here, such as Wayland. */
    fun remoteNotes(): List<String>

    fun setSoundOutOn(on: Boolean)
    fun stopSoundOut()

    /** "Let phones see this screen", and the phone viewing it now. */
    val screenOutOn: StateFlow<Boolean>
    val screenTo: StateFlow<String?>
    /** Why phones can't see this screen here, such as Wayland. */
    fun screenNotes(): List<String>
    fun setScreenOutOn(on: Boolean)
    fun stopScreenOut()
    /** False when the name or command line is empty or too long. */
    fun addCommand(name: String, command: String): Boolean
    fun removeCommand(id: String)
    fun setCommandsAllowed(deviceId: String, on: Boolean)
    fun setTerminalAllowed(deviceId: String, on: Boolean)
    fun endTerminals(deviceId: String)
    fun setRemoteOn(on: Boolean)
    fun setCommandAsCtrl(on: Boolean)
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

    // Updates of this app.

    val update: StateFlow<UpdateState>
    fun checkForUpdates()
    /** Opens the installer for this system in the browser. Nothing installs by itself. */
    fun downloadUpdate()
    fun skipUpdate()
}

/** How the camera window shows the picture. Kept here so the window and the tab agree. */
class CameraView {
    var mirror by mutableStateOf(false)
    /** Turned by hand, on top of the phone's own rotation. */
    var rotation by mutableStateOf(0)
    var onTop by mutableStateOf(false)
    /** Only the picture, for window capture in OBS and the like. */
    var clean by mutableStateOf(false)
}

enum class Page { Device, Tools, History, Settings }

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
    /** A number to start an SMS to, from the contacts: the Messages tab opens a new message with it. */
    var smsTo by mutableStateOf<String?>(null)

    fun openDevice(id: String) {
        if (id != selectedId) tab = "overview"
        selectedId = id
        page = Page.Device
    }
}
