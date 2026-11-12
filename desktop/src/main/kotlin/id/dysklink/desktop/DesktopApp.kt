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
    val input: PcInput = PcInput(::paste)

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

    // What this PC serves phones, and a Mac's mouse and keyboard. Everything here is off until it's turned on.

    private val _soundOutOn = MutableStateFlow(settings.bool("soundOut", false))
    /** "Let phones hear this PC": a paired phone can ask for this PC's sound. */
    val soundOutOn: StateFlow<Boolean> = _soundOutOn.asStateFlow()

    private val _screenOutOn = MutableStateFlow(settings.bool("screenOut", false))
    /** "Let phones see this screen": a paired phone can view this PC's screen, and tap on it while control is on. */
    val screenOutOn: StateFlow<Boolean> = _screenOutOn.asStateFlow()
    private val robotScreen by lazy { RobotScreenSource(wayland = input.isWayland) }

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

    /** Null where Robot doesn't reach other apps (Wayland, or no display). */
    private val remoteTarget: SharedInputTarget? by lazy {
        if (!input.isAvailable) return@lazy null
        val robot = runCatching { java.awt.Robot().apply { autoDelay = 0 } }.getOrNull() ?: return@lazy null
        SharedInputTarget(
            RobotInjector(robot),
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

    // The notch tools on this PC: Todo, water, Pomodoro, and the note, also on the phone as a remote (`pc-notch`).

    val notch = PcNotch(Platform.dataDir)
    val tools: StateFlow<PcToolsState> = notch.tools
    private val _trayStatus = MutableStateFlow<String?>(null)
    /** The Pomodoro's countdown for the tray while it's started, such as "Focus 1/4: 24:59". */
    val trayStatus: StateFlow<String?> = _trayStatus.asStateFlow()

    /** Runs a change on the tools; when anything changed, phones get the new state. */
    fun tools(change: PcNotch.() -> Boolean): Boolean = notch.change().also { if (it) hub.notchChanged() }

    private fun startTools() {
        scope.launch {
            while (true) {
                val tick = notch.tick()
                if (tick.changed) hub.notchChanged()
                tick.finished?.let { finished ->
                    val (title, message) = PcNotch.finishedText(finished, tick.next, notch.tools.value.pomodoro.settings)
                    _popups.tryEmit(Popup(title, message))
                }
                if (tick.remindWater) {
                    val water = notch.tools.value.water
                    val today = water.totalToday(System.currentTimeMillis(), java.time.ZoneId.systemDefault())
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

    // Updates of this app, from GitHub releases. Nothing installs by itself.

    val updater = Updater(settings, scope)

    /** A scan from a phone, saved to Documents/DyskLink/Scans: shown, and a PDF opened. */
    private fun scanSaved(file: File, from: String) {
        _popups.tryEmit(Popup("Scan from $from saved", "${file.name} is in ${file.parent}."))
        if (file.extension.equals("pdf", ignoreCase = true)) Platform.open(file)
    }

    val hub: ComputerHub = ComputerHub(
        Platform.dataDir, scope,
        localHello = { paired -> Hello(Platform.deviceName, Platform.protocolName, Platform.model, PhoneLinkProtocol.VERSION, paired, Platform.version, PhoneLinkProtocol.PC_CAPS) },
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

            override fun sms(items: List<SmsItem>, from: String) {
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
            override fun clipboardImage(png: ByteArray, from: String) { if (_clipboardOn.value) systemClipboard.setImage(png) }
            override fun phoneSoundChunk(from: String, pcm: ByteArray) = speaker.play(pcm)
            override fun phoneSoundEnded(from: String) {
                speaker.stop()
                _soundFrom.value = null
            }

            override fun soundRefusal(from: String): String? = when {
                !_soundOutOn.value -> "Turn on \"Let phones hear this PC\" in DyskLink on the PC first."
                else -> null
            }
            override fun soundCapture(): PcSoundCapture? = PcSound.capture()

            override fun screenRefusal(from: String): String? = when {
                // Said first: turning the switch on wouldn't help.
                input.isWayland -> PcScreen.WAYLAND_REFUSAL
                !_screenOutOn.value -> PcScreen.TURNED_OFF
                else -> null
            }
            override fun screenSource(): PcScreenSource = robotScreen
            override fun screenPointer(kind: String, x: Int, y: Int, from: String) {
                if (_controlOn.value) input.pointerAt(kind, x, y)
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

            override fun remoteAllowed(from: String): Boolean = _remoteOn.value && _computersOn.value && remoteTarget != null
            override fun remote(type: String, event: RemoteEvent?, from: String) {
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
            override fun remoteGone(from: String) { remoteTarget?.gone(from) }

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
        startTools()
        updater.start()
    }

    fun stop() {
        remoteTarget?.release(notify = true)
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
        if (!on) remoteTarget?.release(notify = true)
        hub.setComputersEnabled(on)
    }

    fun setSoundOutOn(on: Boolean) {
        _soundOutOn.value = on
        settings.set("soundOut", on)
        if (!on) hub.stopSoundOut()
    }

    fun stopSoundOut() = hub.stopSoundOut()

    fun setScreenOutOn(on: Boolean) {
        _screenOutOn.value = on
        settings.set("screenOut", on)
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
    }

    fun setControlOn(on: Boolean) {
        _controlOn.value = on
        settings.set("control", on)
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
