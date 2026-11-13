package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.HubPhone
import id.phonelink.android.core.NotificationItem
import id.phonelink.android.core.PcNotch
import id.phonelink.android.core.PcToolsState
import id.phonelink.android.core.PomodoroPhase
import id.phonelink.android.core.PomodoroState
import id.phonelink.android.core.SmsItem
import id.phonelink.android.core.Transfer
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What shows under or instead of the closed island for a moment. */
data class IslandBanners(val peek: IslandPeek? = null, val notice: PhoneAlert? = null, val code: CodeNotice? = null)

/**
 * The island's data and actions: the music, lyrics, places, shelf, codes, notices, and the phone's battery, on top of
 * the PC's notch tools (Todo, water, Pomodoro) that the Tools page already has. Started with the switch, stopped
 * without it, so nothing reads players or the network while the island is off.
 */
class IslandService(
    private val scope: CoroutineScope,
    private val store: Settings,
    dataDir: File,
    val tools: StateFlow<PcToolsState>,
    private val toolsChange: (PcNotch.() -> Boolean) -> Boolean,
    val phones: StateFlow<List<HubPhone>>,
    notifications: StateFlow<Map<String, List<NotificationItem>>>,
    val transfers: StateFlow<List<Transfer>>,
    /** Clipboard History, when this PC has it. */
    val clipHistory: StateFlow<ClipHistoryView>?,
    private val clipAction: (ClipAction) -> Unit = {},
    private val clipImage: (ClipEntry) -> File? = { null },
    private val sendFile: (phoneId: String, file: File) -> Unit = { _, _ -> },
    private val dismissNotification: (phoneId: String, key: String) -> Unit = { _, _ -> },
    /** Puts a code on the clipboard without it going to the history or to phones. */
    private val copyConcealed: (String) -> Unit = {},
    popup: (String, String) -> Unit = { _, _ -> },
    /** This PC's battery (percent, charging), shown as a ring in the open island's header; null without one. */
    val pcBattery: StateFlow<Pair<Int, Boolean>?>? = null,
    private val musicFactory: () -> MusicBackend? = ::defaultMusic,
    val native: IslandNative = IslandNative.forThisPc(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _settings = MutableStateFlow(IslandSettings.load(store))
    val settings: StateFlow<IslandSettings> = _settings.asStateFlow()

    val lyrics = LyricsService(store)
    val places = IslandPlaces(scope, store, dataDir, notify = popup, clock = clock)

    private var music: MusicBackend? = null
    private val _track = MutableStateFlow<NowPlaying?>(null)
    val track: StateFlow<NowPlaying?> = _track.asStateFlow()
    private val _players = MutableStateFlow<List<PlayerInfo>>(emptyList())
    val players: StateFlow<List<PlayerInfo>> = _players.asStateFlow()
    private val _musicProblem = MutableStateFlow<String?>(null)
    private val _art = MutableStateFlow<IslandArt?>(null)
    /** Art for what's showing now, never a leftover from the song before. */
    val art: StateFlow<IslandArt?> = _art.asStateFlow()

    private val shelfFile = File(dataDir, "island-shelf.json")
    private val _shelf = MutableStateFlow(ShelfStore.load(shelfFile))
    val shelf: StateFlow<List<ShelfItem>> = _shelf.asStateFlow()

    private val _banners = MutableStateFlow(IslandBanners())
    val banners: StateFlow<IslandBanners> = _banners.asStateFlow()
    private var peekJob: Job? = null
    private var noticeJob: Job? = null
    private var codeJob: Job? = null
    private val recentCodes = RecentCodes()
    private val battery = PhoneBatteryAlerts()

    /** Notifications from connected phones that share them, newest first. */
    val alerts: StateFlow<List<PhoneAlert>> = combine(notifications, phones) { all, list ->
        all.flatMap { (id, items) ->
            val name = list.firstOrNull { it.id == id }?.name ?: "Phone"
            items.map { PhoneAlert(id, name, it) }
        }.sortedByDescending { it.item.time }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The phone that gets files and shows its battery: the first paired, connected one. */
    val phone: StateFlow<HubPhone?> = phones.map { list -> list.firstOrNull { it.isPaired && it.isConnected && !it.isComputer } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _hints = MutableStateFlow<List<HostHint>>(emptyList())
    /** What the island needs from outside DyskLink here, for the settings. */
    val hints: StateFlow<List<HostHint>> = _hints.asStateFlow()

    private var jobs = mutableListOf<Job>()

    val isOn: Boolean get() = _settings.value.enabled

    fun hasData(section: IslandSection): Boolean = when (section) {
        IslandSection.Prayer -> places.view.value.location != null
        IslandSection.Weather -> places.view.value.weatherPlace != null
        IslandSection.Alerts -> phones.value.any { it.isConnected && "notifications" in it.features }
        else -> true
    }

    fun pages(settings: IslandSettings = _settings.value) = IslandPages(settings, ::hasData)

    // Settings

    fun update(change: (IslandSettings) -> IslandSettings) {
        val before = _settings.value
        val after = change(before)
        if (after == before) return
        _settings.value = after
        after.save(store)
        if (after.player != before.player) music?.preferred = after.player
        if (after.enabled != before.enabled) if (after.enabled) start() else stop()
        if (!after.phoneBattery) phones.value.forEach { battery.forget(it.id) }
        recheckHints()
    }

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }

    fun setShown(section: IslandSection, shown: Boolean) {
        update { pages(it).withShown(section, shown) }
        if (section == IslandSection.Pomodoro && !shown) toolsChange { resetPomodoro() }
    }

    fun move(page: IslandPage, step: Int) = update { it.copy(order = IslandPages.moved(it.order, page, step)) }

    // Running

    fun start() {
        if (jobs.isNotEmpty()) return
        val backend = musicFactory()
        music = backend
        backend?.preferred = _settings.value.player
        backend?.start(scope)
        if (backend != null) {
            jobs += scope.launch { backend.track.collect { track -> _track.value = track; resolveArt(track) } }
            jobs += scope.launch { backend.players.collect { _players.value = it } }
            jobs += scope.launch { backend.art.collect { resolveArt(_track.value) } }
            jobs += scope.launch { backend.problem.collect { _musicProblem.value = it; recheckHints() } }
        }
        jobs += scope.launch { _track.map { it?.title to it?.artist }.distinctUntilChanged().collect { lyrics.load(_track.value) } }
        jobs += scope.launch { lyrics.isOn.collect { if (it) lyrics.load(_track.value) } }
        jobs += scope.launch { phones.collect(::batteryCheck) }
        jobs += scope.launch {
            while (isActive) {
                places.tick()
                places.refreshWeather()
                delay(30_000)
            }
        }
        recheckHints()
        CoreLog.i("Island", "Island started")
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        music?.stop()
        music = null
        _track.value = null
        _art.value = null
        _banners.value = IslandBanners()
    }

    // Music

    fun media(command: MediaCommand) = music?.command(command)
    fun seek(positionMs: Long) = music?.seek(positionMs)

    private fun resolveArt(track: NowPlaying?) {
        if (track == null) { _art.value = null; return }
        val handed = music?.art?.value
        if (handed != null && (handed.key == track.key || handed.key == "${track.key}|${track.artist}")) {
            _art.value = IslandArt(track.key, handed.bytes)
            return
        }
        if (_art.value?.key == track.key) return
        val url = track.artUrl
        if (url == null) { _art.value = null; return }
        _art.value = null
        scope.launch(Dispatchers.IO) {
            val bytes = ArtLoader.load(url) ?: return@launch
            if (_track.value?.key == track.key) _art.value = IslandArt(track.key, bytes)
        }
    }

    // Shelf

    fun addToShelf(files: List<File>) {
        _shelf.update { ShelfStore.adding(files, it) }
        ShelfStore.save(_shelf.value, shelfFile)
    }

    fun removeFromShelf(item: ShelfItem) {
        _shelf.update { list -> list.filter { it != item } }
        ShelfStore.save(_shelf.value, shelfFile)
    }

    fun clearShelf() {
        _shelf.value = emptyList()
        ShelfStore.save(emptyList(), shelfFile)
    }

    /** Files straight to the phone; folders can't go. False when nothing could be sent. */
    fun send(files: List<File>, phone: HubPhone): Boolean {
        val sendable = ShelfStore.sendable(files)
        if (sendable.isEmpty()) { peek("Only files can be sent, not folders"); return false }
        sendable.forEach { sendFile(phone.id, it) }
        peek(if (sendable.size == 1) "Sending to ${phone.name}" else "Sending ${sendable.size} files to ${phone.name}")
        return true
    }

    fun copyClip(entry: ClipEntry) {
        clipAction(ClipAction.Copy(entry.id))
        peek("Copied")
    }

    fun clipImageFile(entry: ClipEntry): File? = clipImage(entry)

    fun dismiss(alert: PhoneAlert) = dismissNotification(alert.peerId, alert.item.key)

    // Tools

    fun tools(change: PcNotch.() -> Boolean) = toolsChange(change)

    /** A Pomodoro step ended: a short message, as the Mac's notch says it. */
    fun pomodoroFinished(finished: PomodoroPhase, next: PomodoroState?) {
        if (!isOn) return
        peek(when {
            next == null || !next.isRunning -> "Pomodoro finished"
            finished == PomodoroPhase.Focus -> "Time for a break"
            else -> "Back to focus"
        }, ring = 1f, tone = if (finished == PomodoroPhase.Focus) GlanceTone.Mint else GlanceTone.Green)
    }

    fun waterReminder(fraction: Float) {
        if (isOn) peek("Time to drink water", ring = fraction, tone = GlanceTone.Blue)
    }

    // Banners

    fun peek(text: String, ring: Float? = null, tone: GlanceTone = GlanceTone.Green) {
        peekJob?.cancel()
        _banners.update { it.copy(peek = IslandPeek(text, ring, tone, clock())) }
        peekJob = scope.launch { delay(PEEK_MS); _banners.update { it.copy(peek = null) } }
    }

    private fun showNotice(alert: PhoneAlert) {
        noticeJob?.cancel()
        _banners.update { it.copy(peek = null, notice = alert) }
        noticeJob = scope.launch { delay(NOTICE_MS); _banners.update { it.copy(notice = null) } }
    }

    fun hideNotice() {
        noticeJob?.cancel()
        _banners.update { it.copy(notice = null) }
    }

    fun showCode(notice: CodeNotice) {
        hideNotice()
        _banners.update { it.copy(peek = null, code = notice) }
        hideCode(CODE_MS)
    }

    fun copyCode() {
        val current = _banners.value.code ?: return
        copyConcealed(current.code)
        _banners.update { it.copy(code = current.copy(copied = true)) }
        hideCode(1_400)
    }

    fun hideCode(after: Long = 0) {
        codeJob?.cancel()
        if (after <= 0) { _banners.update { it.copy(code = null) }; return }
        codeJob = scope.launch { delay(after); _banners.update { it.copy(code = null) } }
    }

    /**
     * A phone notification: a code in it shows with Copy (true, so no popup repeats the code); otherwise it shows
     * under the island for a few seconds when the Notifications page is on.
     */
    fun onNotification(item: NotificationItem, phoneId: String): Boolean {
        if (!isOn || item.silent == true) return false
        val name = phones.value.firstOrNull { it.id == phoneId }?.name ?: "Phone"
        if (foundCode(listOfNotNull(item.title, item.text).joinToString(" "), item.title ?: item.app, name)) return true
        if (pages().isShown(IslandSection.Alerts)) showNotice(PhoneAlert(phoneId, name, item))
        return false
    }

    /** New SMS: a code in one shows with Copy. True when one did. */
    fun onSms(items: List<SmsItem>, phoneId: String, sender: (String) -> String = { it }): Boolean {
        if (!isOn) return false
        val name = phones.value.firstOrNull { it.id == phoneId }?.name ?: "Phone"
        return items.filter { !it.outgoing }.any { foundCode(it.body, sender(it.address), name) }
    }

    private fun foundCode(text: String, source: String, phone: String): Boolean {
        if (!_settings.value.codes) return false
        val code = OneTimeCode.find(text) ?: return false
        if (recentCodes.isNew(code, clock())) showCode(CodeNotice(code, source, phone))
        return true
    }

    private fun batteryCheck(list: List<HubPhone>) {
        val s = _settings.value
        if (!s.phoneBattery) return
        for (p in list.filter { it.isConnected && !it.isComputer }) {
            val alert = battery.update(p.id, p.info?.batteryPercent, p.info?.isCharging, s.batteryLimit) ?: continue
            val short = if (p.name.length > 12) p.name.take(11) + "…" else p.name
            when (alert) {
                is PhoneBatteryAlerts.Alert.Low -> peek("$short · ${alert.percent}% low", alert.percent / 100f, GlanceTone.Amber)
                PhoneBatteryAlerts.Alert.Full -> peek("$short · fully charged", 1f, GlanceTone.Green)
            }
        }
    }

    // Hints

    fun recheckHints() {
        val s = _settings.value
        if (!s.enabled) { _hints.value = emptyList(); return }
        _hints.value = IslandHints.hints(
            windows = Platform.isWindows,
            linux = Platform.isLinux,
            wayland = Platform.isLinux && Typing.desktop() == Desktop.WAYLAND,
            desktop = System.getenv("XDG_CURRENT_DESKTOP"),
            compositor = if (Platform.isLinux) runCatching { native.hasCompositor() }.getOrNull() else null,
            musicProblem = _musicProblem.value,
            asWindow = s.asWindow,
        )
    }

    companion object {
        const val PEEK_MS = 2_800L
        const val NOTICE_MS = 5_500L
        const val CODE_MS = 30_000L

        fun defaultMusic(): MusicBackend? = when {
            Platform.isWindows -> WindowsMediaSource()
            Platform.isLinux -> MprisSource.forSession()
            else -> null
        }
    }
}

/** What the island needs outside DyskLink, by system. Pure, for tests. */
object IslandHints {
    fun hints(windows: Boolean, linux: Boolean, wayland: Boolean, desktop: String?, compositor: Boolean?, musicProblem: String?, asWindow: Boolean): List<HostHint> = buildList {
        if (wayland && !asWindow) add(
            HostHint(
                "The island runs through XWayland",
                "Wayland doesn't let apps place windows at the top of the screen, so the island uses X11 through XWayland. " +
                    "GNOME and KDE Plasma keep it in place; if your desktop puts it somewhere else or under other windows, " +
                    "turn on \"Show as a small window\" below.",
                tone = HintTone.Info,
            ),
        )
        if (linux && !wayland && compositor == false) add(
            HostHint(
                "No compositor is running",
                "Without a compositing window manager the island's rounded edges can't be see-through, so DyskLink cuts its " +
                    "shape out instead. Turn on your desktop's compositing (Xfce: Window Manager Tweaks, Compositor) for smooth edges.",
                tone = HintTone.Info,
            ),
        )
        if (musicProblem != null) add(
            HostHint(
                "Music can't be read",
                musicProblem + if (windows) " The island needs Windows PowerShell 5.1, which comes with Windows 10 and 11." else "",
            ),
        )
        if (linux && musicProblem == null && desktop?.contains("GNOME", ignoreCase = true) == true) add(
            HostHint(
                "The island sits under GNOME's top bar",
                "GNOME draws its top bar over every window, so the island hangs just below it.",
                tone = HintTone.Info,
            ),
        )
    }
}

/** Album art from a file the player wrote, or from the players' own image servers (as on the Mac). */
object ArtLoader {
    val HOSTS = setOf(
        "i.scdn.co", "mosaic.scdn.co", "image-cdn-ak.spotifycdn.com", "image-cdn-fa.spotifycdn.com", "i.ytimg.com",
        "lh3.googleusercontent.com", "yt3.ggpht.com",
    )
    private const val MAX = 4_000_000

    fun isAllowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        when (uri.scheme) {
            "file" -> true
            "https" -> uri.host in HOSTS
            else -> false
        }
    }.getOrDefault(false)

    fun load(url: String): ByteArray? = runCatching {
        if (!isAllowed(url)) return null
        val uri = URI(url)
        if (uri.scheme == "file") {
            val f = File(uri)
            return if (f.isFile && f.length() in 1..MAX) f.readBytes() else null
        }
        val c = uri.toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        try {
            if (c.responseCode != 200) null else c.inputStream.use { it.readNBytes(MAX + 1) }.takeIf { it.size in 1..MAX }
        } finally { c.disconnect() }
    }.getOrNull()
}
