package id.phonelink.android.core

import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * What one phone (A) can ask another (B) to do as a remote (1.5): offer a link, control the music, share screen time,
 * silence it, and tell where it is. Each has its own switch on B, which asks the first time (see [PhoneRules]).
 */
enum class PeerAsk {
    Links, Media, Usage, Quiet, Locate,
    /** An old phone moves here (1.6). A request with an answer, like the others. */
    Move,
    /** Read SMS here and send them through this phone's SIM (1.6). Has its own messages (see [SmsShare]). */
    Sms,
    /** Keep that phone's photo backup here (1.6). Asked on its first `file.offer` with `kind: "photo"`. */
    Backup,
}

/** What plays on the other phone, from its active media session. [art] is a small base64 JPEG. */
@Serializable
data class NowPlaying(
    val title: String? = null,
    val artist: String? = null,
    /** The app that plays it. */
    val app: String? = null,
    val playing: Boolean = false,
    val art: String? = null,
    /** The music volume, from 0 to [maxVolume]. */
    val volume: Int? = null,
    val maxVolume: Int? = null,
    /** Why the title can't be shown, such as notification access being off. The buttons still work. */
    val note: String? = null,
)

/**
 * `mode`: `silent`, `vibrate`, `normal`, `dnd.on`, or `dnd.off`. On `quiet.set`, optionally [minutes] or
 * [untilMorning] (07:00 on the silenced phone). On `quiet.result`, [until] is when it goes back, in ms since 1970.
 */
@Serializable
data class QuietRequest(
    val mode: String,
    val minutes: Int? = null,
    val untilMorning: Boolean? = null,
    val until: Long? = null,
)

/** A location fix: degrees, accuracy in meters, and when it was taken (ms since 1970). */
@Serializable
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracy: Float? = null,
    val time: Long,
    val provider: String? = null,
)

/**
 * Links one phone may hand another: web pages (http, https), map places (geo, and web map links), and phone numbers
 * (tel, or a bare number). Everything else is refused, such as `intent:`, `file:`, `content:`, `javascript:`, `data:`,
 * and `sms:`. Both phones check: the sender before sending, the receiver again before showing it.
 */
object SafeLink {
    enum class Kind { Web, Map, Phone }

    data class Link(val uri: String, val kind: Kind)

    const val MAX_CHARS = 2_048
    private const val MIN_DIGITS = 3
    private const val MAX_DIGITS = 20
    private val MAP_HOSTS = setOf("maps.google.com", "maps.app.goo.gl", "maps.apple.com", "www.openstreetmap.org", "openstreetmap.org")
    private val GOOGLE_HOSTS = setOf("google.com", "www.google.com", "goo.gl")

    /** The whole of [raw] as a link, or null when it isn't one of the allowed kinds. */
    fun parse(raw: String?): Link? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty() || text.length > MAX_CHARS || text.any { it.isISOControl() }) return null
        val colon = text.indexOf(':')
        val scheme = if (colon > 0 && text[0].isLetter() && text.substring(0, colon).all { it.isLetterOrDigit() || it in "+-." }) {
            text.substring(0, colon).lowercase()
        } else {
            null
        }
        return when (scheme) {
            "http", "https" -> web(scheme, text.substring(colon + 1))
            "geo" -> geo(text.substring(colon + 1))
            "tel" -> phone(text.substring(colon + 1))
            null -> phone(text)
            else -> null
        }
    }

    /** The first allowed link in shared text, such as "Look at this https://...", or the text itself. */
    fun find(text: String?): Link? {
        parse(text)?.let { return it }
        return text.orEmpty().split(Regex("\\s+")).asSequence()
            .map { token -> token.trim { it in "<>()[]{}\"',;!?." } }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) || it.startsWith("geo:", true) }
            .mapNotNull(::parse)
            .firstOrNull()
    }

    private fun web(scheme: String, rest: String): Link? {
        if (rest.any { it.isWhitespace() } || !rest.startsWith("//")) return null
        val uri = runCatching { java.net.URI("$scheme:$rest") }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        // `https://bank.com@evil.example` shows one host and opens another.
        if (uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port !in 1..65_535) return null
        val path = uri.rawPath.orEmpty()
        val map = host in MAP_HOSTS || (host in GOOGLE_HOSTS && path.startsWith("/maps"))
        return Link(uri.toASCIIString(), if (map) Kind.Map else Kind.Web)
    }

    private fun geo(rest: String): Link? {
        if (rest.any { it.isWhitespace() }) return null
        val place = rest.substringBefore('?').substringBefore(';').split(',')
        if (place.size !in 2..3) return null
        val lat = place[0].toDoubleOrNull() ?: return null
        val lon = place[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || place.any { it.contains('e', true) }) return null
        if (runCatching { java.net.URI("geo:$rest") }.isFailure) return null
        return Link("geo:$rest", Kind.Map)
    }

    /** Digits with spaces, dashes, dots, and brackets, `+` only first. No `*` or `#`, which dial codes. */
    private fun phone(number: String): Link? {
        val text = number.trim()
        if (text.isEmpty() || text.any { !(it.isDigit() && it in '0'..'9') && it !in "+ -(). " }) return null
        if (text.indexOf('+', 1) != -1) return null
        val digits = text.count { it in '0'..'9' }
        if (digits !in MIN_DIGITS..MAX_DIGITS) return null
        val plus = if (text.startsWith("+")) "+" else ""
        return Link("tel:$plus${text.filter { it in '0'..'9' }}", Kind.Phone)
    }
}

/** Ringer modes and Do Not Disturb, and when they go back. */
object QuietMath {
    const val SILENT = "silent"
    const val VIBRATE = "vibrate"
    const val NORMAL = "normal"
    const val DND_ON = "dnd.on"
    const val DND_OFF = "dnd.off"
    val MODES = listOf(SILENT, VIBRATE, NORMAL, DND_ON, DND_OFF)
    const val MORNING_HOUR = 7
    const val MAX_MINUTES = 12 * 60

    fun valid(request: QuietRequest): Boolean =
        request.mode in MODES && (request.minutes == null || request.minutes in 1..MAX_MINUTES)

    /** Modes that can end by themselves; Normal and Do Not Disturb off are where they end. */
    fun lasts(mode: String): Boolean = mode == SILENT || mode == VIBRATE || mode == DND_ON

    /** When the mode goes back, or null when it stays until changed. Morning wins over minutes. */
    fun until(now: Long, request: QuietRequest, zone: ZoneId): Long? = when {
        !lasts(request.mode) -> null
        request.untilMorning == true -> nextMorning(now, zone)
        request.minutes != null -> now + request.minutes * 60_000L
        else -> null
    }

    /** The next 07:00 in [zone]: today's if it's still to come, otherwise tomorrow's. */
    fun nextMorning(now: Long, zone: ZoneId): Long {
        val at = Instant.ofEpochMilli(now).atZone(zone)
        var morning = at.toLocalDate().atTime(MORNING_HOUR, 0).atZone(zone)
        if (!morning.isAfter(at)) morning = at.toLocalDate().plusDays(1).atTime(MORNING_HOUR, 0).atZone(zone)
        return morning.toInstant().toEpochMilli()
    }

    fun label(mode: String): String = when (mode) {
        SILENT -> "Silent"
        VIBRATE -> "Vibrate"
        NORMAL -> "Normal"
        DND_ON -> "Do Not Disturb"
        DND_OFF -> "Do Not Disturb off"
        else -> mode
    }
}

/** What this phone (A) watches on another phone (B). Kept on A only. */
@Serializable
data class MonitorSettings(
    val lowAlert: Boolean = false,
    val threshold: Int = DEFAULT_THRESHOLD,
    val fullAlert: Boolean = false,
) {
    fun clamped(): MonitorSettings = copy(threshold = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD))

    companion object {
        const val MIN_THRESHOLD = 10
        const val MAX_THRESHOLD = 30
        const val DEFAULT_THRESHOLD = 15
    }
}

enum class BatteryAlert { Low, Full }

/**
 * Battery alerts about other phones, each once per cycle: "low" once while it runs down, again only after it
 * charged above the threshold (or rose [HYSTERESIS] points over it); "full" once while charging, again after unplugging.
 */
class BatteryAlerts {
    private class Armed(var low: Boolean = true, var full: Boolean = true)

    private val state = HashMap<String, Armed>()

    @Synchronized
    fun check(peerId: String, percent: Int?, charging: Boolean?, settings: MonitorSettings): BatteryAlert? {
        val level = percent ?: return null
        val threshold = settings.clamped().threshold
        val plugged = charging == true
        val armed = state.getOrPut(peerId) { Armed() }
        if (level >= threshold + HYSTERESIS || (plugged && level > threshold)) armed.low = true
        if (!plugged) armed.full = true
        return when {
            settings.lowAlert && armed.low && !plugged && level <= threshold -> BatteryAlert.Low.also { armed.low = false }
            settings.fullAlert && armed.full && plugged && level >= FULL -> BatteryAlert.Full.also { armed.full = false }
            else -> null
        }
    }

    @Synchronized
    fun forget(peerId: String) {
        state.remove(peerId)
    }

    companion object {
        const val HYSTERESIS = 5
        const val FULL = 100
    }
}

/** This phone's alerts for each paired phone, kept in `phone_monitor.json`, and the alerts' state. */
class PhoneMonitor(directory: File) {
    private val file = File(directory, "phone_monitor.json")
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Map<String, MonitorSettings>> = _settings.asStateFlow()
    private val alerts = BatteryAlerts()

    fun of(peerId: String): MonitorSettings = _settings.value[peerId] ?: MonitorSettings()

    @Synchronized
    fun set(peerId: String, change: (MonitorSettings) -> MonitorSettings) {
        val after = change(of(peerId)).clamped()
        _settings.value = if (after == MonitorSettings()) _settings.value - peerId else _settings.value + (peerId to after)
        save()
    }

    /** The alert for this `info` from [peerId], if one is due. */
    fun battery(peerId: String, info: DeviceInfo): BatteryAlert? = alerts.check(peerId, info.batteryPercent, info.isCharging, of(peerId))

    @Synchronized
    fun forget(peerId: String) {
        alerts.forget(peerId)
        if (peerId !in _settings.value) return
        _settings.value = _settings.value - peerId
        save()
    }

    private fun load(): Map<String, MonitorSettings> = runCatching {
        protocolJson.decodeFromString(serializer, file.readText())
    }.getOrDefault(emptyMap())

    private fun save() {
        runCatching { file.writeText(protocolJson.encodeToString(serializer, _settings.value)) }
    }

    private companion object {
        val serializer = MapSerializer(String.serializer(), MonitorSettings.serializer())
    }
}

/**
 * The answers this phone waits for from each phone. An answer is only taken for a request sent, once each, and not
 * after [timeoutMs] for that kind: another phone can't push music, screen time, or a location here unasked.
 */
class AwaitedReplies(private val clock: () -> Long = System::currentTimeMillis) {
    private class Waiting(var count: Int, var until: Long)

    private val waiting = HashMap<Pair<String, PeerAsk>, Waiting>()

    @Synchronized
    fun expect(peerId: String, kind: PeerAsk) {
        val key = peerId to kind
        val until = clock() + timeoutMs(kind)
        val entry = waiting[key]?.takeIf { it.until >= clock() }
        if (entry == null) waiting[key] = Waiting(1, until) else {
            entry.count = (entry.count + 1).coerceAtMost(MAX_WAITING)
            entry.until = until
        }
    }

    /** True once per request sent and still in time. */
    @Synchronized
    fun take(peerId: String, kind: PeerAsk): Boolean {
        val key = peerId to kind
        val entry = waiting[key] ?: return false
        if (entry.until < clock()) {
            waiting.remove(key)
            return false
        }
        if (--entry.count <= 0) waiting.remove(key)
        return true
    }

    @Synchronized
    fun forget(peerId: String) {
        waiting.keys.removeAll { it.first == peerId }
    }

    companion object {
        const val MAX_WAITING = 16
        /** Finding a location can take a while outdoors; everything else answers at once. */
        fun timeoutMs(kind: PeerAsk): Long = if (kind == PeerAsk.Locate) 60_000L else 20_000L
    }
}

/** A request the phone couldn't carry out, with why. */
class RemoteRefused(message: String) : Exception(message)

/** The app side of the remote requests, on the phone that carries them out (B). Blocking; called off the main thread. */
interface PhoneRemote {
    /** Shows a notification "A sent a link" with Open. Never opens it by itself. */
    fun offerLink(peerId: String, peerName: String, link: SafeLink.Link)

    /** Presses a music button ([RemoteAnswers.MEDIA_ACTIONS]), or only reads the state, then says what plays. */
    fun media(action: String): NowPlaying

    /** Sets the ringer mode or Do Not Disturb, with [QuietRequest.until] already worked out. Returns what was set. */
    fun quiet(peerId: String, peerName: String, request: QuietRequest): QuietRequest

    /** The current or last known location. Throws [RemoteRefused] with why when there is none. */
    fun locate(): LocationFix
}

/** B's answers to A's requests, once [PhoneRules.fromController] let them through. Pure, so it can be tested. */
class RemoteAnswers(
    private val data: PhoneData,
    private val remote: () -> PhoneRemote?,
    private val localName: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    fun answer(peerId: String, peerName: String, envelope: Envelope): Envelope? {
        val kind = PhoneRules.FROM_CONTROLLER[envelope.type] ?: return null
        val type = PhoneRules.ANSWER.getValue(kind)
        val local = localName()
        fun fail(why: String) = Envelope(type, key = envelope.key, error = why)
        fun phone(): PhoneRemote = remote() ?: throw RemoteRefused("Not available on $local.")
        return runCatching {
            when (kind) {
                PeerAsk.Links -> {
                    val link = SafeLink.parse(envelope.text) ?: return fail("Only web, map, and phone links can be sent.")
                    phone().offerLink(peerId, peerName, link)
                    Envelope(type, key = envelope.key, text = link.uri)
                }
                PeerAsk.Media -> {
                    val action = envelope.media?.takeIf { it in MEDIA_ACTIONS } ?: return fail("Unknown music button.")
                    Envelope(type, key = envelope.key, nowPlaying = phone().media(action))
                }
                PeerAsk.Usage -> {
                    val report = data.screenTime() ?: return fail("Screen time needs Usage access for DyskLink on $local.")
                    Envelope(type, key = envelope.key, usage = report)
                }
                PeerAsk.Quiet -> {
                    val request = envelope.quiet?.takeIf(QuietMath::valid) ?: return fail("Unknown sound mode.")
                    val asked = request.copy(until = QuietMath.until(clock(), request, zone()))
                    Envelope(type, key = envelope.key, quiet = phone().quiet(peerId, peerName, asked))
                }
                PeerAsk.Locate -> Envelope(type, key = envelope.key, location = phone().locate())
                PeerAsk.Move -> {
                    val move = envelope.move?.takeIf { it.valid() } ?: return fail("Bad move request.")
                    val free = data.freeBytes()
                    if (!StorageGuard.enough(free, move.bytes)) {
                        return Envelope(type, key = envelope.key, error = StorageGuard.lowSpaceText(local, free), file = FileMessage("", reason = PhoneKinds.LOW_SPACE))
                    }
                    Envelope(type, key = envelope.key, move = move, info = DeviceInfo(storageFreeBytes = free))
                }
                PeerAsk.Sms, PeerAsk.Backup -> return null
            }
        }.getOrElse { fail(it.message ?: "Couldn't do it on $local.") }
    }

    companion object {
        const val STATE = "state"
        val MEDIA_ACTIONS = setOf("play_pause", "next", "previous", "volume_up", "volume_down", STATE)
    }
}
