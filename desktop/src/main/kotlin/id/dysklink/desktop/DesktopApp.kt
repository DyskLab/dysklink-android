package id.dysklink.desktop

import id.phonelink.android.core.CallEvent
import id.phonelink.android.core.ComputerHub
import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.DeviceInfo
import id.phonelink.android.core.InputEvent
import id.phonelink.android.core.Hello
import id.phonelink.android.core.HubEvents
import id.phonelink.android.core.NotificationItem
import id.phonelink.android.core.PhoneLinkProtocol
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A system notification to show from the tray. */
data class Popup(val title: String, val message: String)

/** Everything the window and tray show: the hub, plus the clipboard, ringing, and settings on this PC. */
class DesktopApp(scope: CoroutineScope) {
    private val settings = Settings(File(Platform.dataDir, "settings.properties"))
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
    val input: PcInput = PcInput(::paste)

    private fun paste(text: String) {
        systemClipboard.set(text)
        input.pasteNow()
    }
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

    val hub: ComputerHub = ComputerHub(
        Platform.dataDir, scope,
        localHello = { paired -> Hello(Platform.deviceName, Platform.protocolName, Platform.model, PhoneLinkProtocol.VERSION, paired, Platform.version) },
        events = object : HubEvents {
            override fun clipboard(text: String, from: String): Boolean = _clipboardOn.value && systemClipboard.set(text)

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
                if (!_notifyOn.value) return
                val title = listOfNotNull(item.app, item.title).distinct().joinToString(": ")
                _popups.tryEmit(Popup(title, item.text.orEmpty()))
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
            override fun clipboardImage(png: ByteArray, from: String) { if (_clipboardOn.value) systemClipboard.setImage(png) }
            override fun phoneSoundChunk(from: String, pcm: ByteArray) = speaker.play(pcm)
            override fun phoneSoundEnded(from: String) {
                speaker.stop()
                _soundFrom.value = null
            }
        },
        fileSinks = DownloadSinks { Platform.downloadsDir },
        port = Platform.port,
        localInfo = { DeviceInfo(osVersion = Platform.model, model = Platform.model, inputTrusted = _controlOn.value && input.isAvailable) },
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
        } catch (error: java.net.BindException) {
            _problem.value = "Port ${Platform.port} is in use. Is DyskLink already running? Close it and start again."
            CoreLog.w("DyskLink", "Port in use", error)
        }
        systemClipboard.enabled = _clipboardOn.value
        systemClipboard.start()
    }

    fun stop() {
        advertiser.stop()
        systemClipboard.stop()
        ringer.stop()
        speaker.stop()
        media.stop()
        hub.shutdown()
    }

    fun setClipboardOn(on: Boolean) {
        _clipboardOn.value = on
        systemClipboard.enabled = on
        settings.set("clipboard", on)
    }

    fun setComputersOn(on: Boolean) {
        _computersOn.value = on
        settings.set("computers", on)
        hub.setComputersEnabled(on)
    }

    fun setNotifyOn(on: Boolean) {
        _notifyOn.value = on
        settings.set("notifications", on)
    }

    fun setControlOn(on: Boolean) {
        _controlOn.value = on
        settings.set("control", on)
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
