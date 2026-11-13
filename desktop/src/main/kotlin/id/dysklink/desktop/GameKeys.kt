package id.dysklink.desktop

import id.phonelink.android.core.ControlEvent
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

// Game keys on the phone's screen, as on the Mac (mac/Sources/PhoneLinkMac/Tools/GameKeys.swift): keyboard keys and the
// mouse become fingers on the phone (`control` with `tdown`, `tmove`, `tup`, see PROTOCOL.md, Game keys). The layout is
// the Mac's own data model, with the Mac's virtual key codes, so a profile made on a Mac works here and back.

/** A keyboard key mapped to a touch on the phone. Points are 0 to 1 on the phone screen as it is now. */
data class GameKey(
    val id: String = newId(),
    val kind: Kind,
    /** The Mac key code (US position) for a tap, skill, or swipe key. A joystick uses its key set instead. */
    val keyCode: Int = 0,
    val label: String = "",
    val x: Double,
    val y: Double,
    /** Joystick, look area, or skill aim reach, as a share of the phone's width. */
    val radius: Double = 0.08,
    val mode: Mode = Mode.Tap,
    /** Auto-fire taps per second. */
    val rate: Double = 10.0,
    val keySet: KeySet = KeySet.Wasd,
    val customKeys: List<Int> = emptyList(),
    val customLabels: List<String> = emptyList(),
    val walk: Walk = Walk.Off,
    /** The modifier that changes walking: Shift, Control, or Option/Alt (left key codes). */
    val walkKey: Int = 56,
    /** Where a swipe ends. */
    val toX: Double = 0.0,
    val toY: Double = 0.0,
    /** A swipe's length in milliseconds. */
    val duration: Double = 120.0,
    /** A skill pressed briefly casts at the button's middle, like a tap. */
    val quickCast: Boolean = false,
    val steps: List<Step> = emptyList(),
) {
    enum class Kind(val wire: String) { Tap("tap"), Joystick("joystick"), Look("look"), Fire("fire"), Skill("skill"), Swipe("swipe") }
    /** What a tap key does while its key is held. */
    enum class Mode(val wire: String) { Tap("tap"), Toggle("toggle"), AutoFire("autoFire"), Combo("combo") }
    /** The four keys that push a joystick, in the order up, left, down, right. */
    enum class KeySet(val wire: String) { Wasd("wasd"), Arrows("arrows"), Ijkl("ijkl"), Custom("custom") }
    /** Walking: a held modifier switches the joystick between half and full reach. */
    enum class Walk(val wire: String) { Off("off"), FullWithKey("fullWithKey"), HalfWithKey("halfWithKey") }

    /** One part of a combo. */
    data class Step(
        val id: String = newId(),
        val kind: Kind,
        val x: Double = 0.0,
        val y: Double = 0.0,
        val toX: Double = 0.0,
        val toY: Double = 0.0,
        /** A wait's length, or a swipe's duration, in milliseconds. */
        val ms: Double = 80.0,
    ) {
        enum class Kind(val wire: String) { Tap("tap"), Wait("wait"), Swipe("swipe") }
    }

    /** Keys that hold a finger of their own from the pool. */
    val usesFinger: Boolean get() = kind in setOf(Kind.Tap, Kind.Joystick, Kind.Skill, Kind.Swipe)
    /** Keys bound to one keyboard key. */
    val isAction: Boolean get() = kind in setOf(Kind.Tap, Kind.Skill, Kind.Swipe)

    val directionKeys: List<Int>
        get() = when (keySet) {
            KeySet.Wasd -> WASD
            KeySet.Arrows -> ARROWS
            KeySet.Ijkl -> IJKL
            KeySet.Custom -> if (customKeys.size == 4) customKeys else WASD
        }

    val directionLabels: List<String>
        get() = when (keySet) {
            KeySet.Wasd -> listOf("W", "A", "S", "D")
            KeySet.Arrows -> listOf("↑", "←", "↓", "→")
            KeySet.Ijkl -> listOf("I", "J", "K", "L")
            KeySet.Custom -> if (customLabels.size == 4) customLabels else listOf("W", "A", "S", "D")
        }

    companion object {
        val WASD = listOf(13, 0, 1, 2)
        val ARROWS = listOf(126, 123, 125, 124)
        val IJKL = listOf(34, 38, 40, 37)
        /** As Swift writes a UUID. */
        fun newId(): String = UUID.randomUUID().toString().uppercase()
    }
}

/** A named layout, so each game keeps its own keys. */
data class GameProfile(val name: String, val keys: List<GameKey>)

/**
 * The Mac's JSON for game profiles: what Swift's JSONEncoder writes for `GameProfile` and `[GameProfile]`, field for
 * field, and what its JSONDecoder reads. Missing newer fields get the Mac's defaults, as on the Mac.
 */
object GameKeysJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val pretty = Json { prettyPrint = true }

    fun encodeProfile(profile: GameProfile): String = pretty.encodeToString(JsonElement.serializer(), profileJson(profile))

    fun encodeProfiles(profiles: List<GameProfile>): String =
        pretty.encodeToString(JsonElement.serializer(), JsonArray(profiles.map(::profileJson)))

    /** One profile, or the Mac's whole list of them. Empty when it isn't a game profile at all. */
    fun decode(text: String): List<GameProfile> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        return when (root) {
            is JsonArray -> root.mapNotNull { (it as? JsonObject)?.let(::profile) }
            is JsonObject -> listOfNotNull(profile(root))
            else -> emptyList()
        }
    }

    fun profileJson(profile: GameProfile): JsonObject = JsonObject(
        mapOf("name" to JsonPrimitive(profile.name), "keys" to JsonArray(profile.keys.map(::keyJson))),
    )

    fun keyJson(key: GameKey): JsonObject = JsonObject(
        linkedMapOf(
            "id" to JsonPrimitive(key.id),
            "kind" to JsonPrimitive(key.kind.wire),
            "keyCode" to JsonPrimitive(key.keyCode),
            "label" to JsonPrimitive(key.label),
            "x" to JsonPrimitive(key.x),
            "y" to JsonPrimitive(key.y),
            "radius" to JsonPrimitive(key.radius),
            "mode" to JsonPrimitive(key.mode.wire),
            "rate" to JsonPrimitive(key.rate),
            "keySet" to JsonPrimitive(key.keySet.wire),
            "customKeys" to JsonArray(key.customKeys.map(::JsonPrimitive)),
            "customLabels" to JsonArray(key.customLabels.map(::JsonPrimitive)),
            "walk" to JsonPrimitive(key.walk.wire),
            "walkKey" to JsonPrimitive(key.walkKey),
            "toX" to JsonPrimitive(key.toX),
            "toY" to JsonPrimitive(key.toY),
            "duration" to JsonPrimitive(key.duration),
            "quickCast" to JsonPrimitive(key.quickCast),
            // Swift's own decoder for a step needs every field.
            "steps" to JsonArray(
                key.steps.map { step ->
                    JsonObject(
                        linkedMapOf(
                            "id" to JsonPrimitive(step.id), "kind" to JsonPrimitive(step.kind.wire),
                            "x" to JsonPrimitive(step.x), "y" to JsonPrimitive(step.y),
                            "toX" to JsonPrimitive(step.toX), "toY" to JsonPrimitive(step.toY), "ms" to JsonPrimitive(step.ms),
                        ),
                    )
                },
            ),
        ),
    )

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    /** A key code is a UInt16 on the Mac. */
    private fun JsonObject.code(name: String): Int? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 0..0xFFFF }
    private fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun <E> wire(values: Array<E>, text: String?, of: (E) -> String): E? = values.firstOrNull { of(it) == text }

    private fun profile(item: JsonObject): GameProfile? {
        val name = item.text("name") ?: return null
        val keys = (item["keys"] as? JsonArray) ?: return null
        return GameProfile(name.take(40), keys.mapNotNull { (it as? JsonObject)?.let(::key) })
    }

    /** One key, or null for one the Mac couldn't read either (no kind, x, or y). */
    fun key(item: JsonObject): GameKey? {
        val kind = wire(GameKey.Kind.entries.toTypedArray(), item.text("kind")) { it.wire } ?: return null
        val x = item.number("x") ?: return null
        val y = item.number("y") ?: return null
        val steps = (item["steps"] as? JsonArray).orEmpty().mapNotNull { element ->
            val step = element as? JsonObject ?: return@mapNotNull null
            val stepKind = wire(GameKey.Step.Kind.entries.toTypedArray(), step.text("kind")) { it.wire } ?: return@mapNotNull null
            GameKey.Step(
                id = step.text("id") ?: GameKey.newId(), kind = stepKind,
                x = unit(step.number("x") ?: 0.0), y = unit(step.number("y") ?: 0.0),
                toX = unit(step.number("toX") ?: 0.0), toY = unit(step.number("toY") ?: 0.0),
                ms = (step.number("ms") ?: 80.0).coerceIn(0.0, 10_000.0),
            )
        }
        return GameKey(
            id = item.text("id") ?: GameKey.newId(),
            kind = kind,
            keyCode = item.code("keyCode") ?: 0,
            label = (item.text("label") ?: "").take(16),
            x = unit(x), y = unit(y),
            radius = (item.number("radius") ?: 0.08).coerceIn(0.01, 0.5),
            mode = wire(GameKey.Mode.entries.toTypedArray(), item.text("mode")) { it.wire } ?: GameKey.Mode.Tap,
            rate = (item.number("rate") ?: 10.0).coerceIn(1.0, 20.0),
            keySet = wire(GameKey.KeySet.entries.toTypedArray(), item.text("keySet")) { it.wire } ?: GameKey.KeySet.Wasd,
            customKeys = (item["customKeys"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull?.takeIf { c -> c in 0..0xFFFF } },
            customLabels = (item["customLabels"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.take(16) },
            walk = wire(GameKey.Walk.entries.toTypedArray(), item.text("walk")) { it.wire } ?: GameKey.Walk.Off,
            walkKey = item.code("walkKey") ?: 56,
            toX = unit(item.number("toX") ?: 0.0), toY = unit(item.number("toY") ?: 0.0),
            duration = (item.number("duration") ?: 120.0).coerceIn(1.0, 10_000.0),
            quickCast = item.flag("quickCast") ?: false,
            steps = steps.take(200),
        )
    }

    private fun unit(value: Double) = value.coerceIn(0.0, 1.0)
}

/** A point on the phone, 0 to 1. */
data class PhonePoint(val x: Double, val y: Double)

/**
 * Turns key presses into fingers on the phone, as the Mac's GameKeyEngine does. Fingers 0 to 7 are a pool: each
 * joystick, tap, skill, or swipe key owns one (joysticks first). 8 is the click spot while looking, and 9 is the mouse,
 * or the look finger while looking. Time is passed in, in milliseconds, so timing can be tested without waiting.
 */
class GameKeyEngine(val keys: List<GameKey>, val aspect: Double) {
    /** One event of a gesture, at a time from its start. */
    data class Scheduled(val at: Double, val kind: String, val x: Double, val y: Double)

    private class Run(val finger: Int, val start: Double, val timeline: List<Scheduled>, var next: Int = 0)
    private class Repeat(val finger: Int, val x: Double, val y: Double, val period: Double, var nextAt: Double, var isDown: Boolean)

    /** The finger each key owns. */
    val fingers: Map<String, Int>
    private val _pressed = mutableSetOf<Int>()
    val pressed: Set<Int> get() = _pressed
    private val _down = sortedMapOf<Int, PhonePoint>()
    /** Every finger that is down on the phone, and where. */
    val down: Map<Int, PhonePoint> get() = _down
    /** Where the look finger is while the mouse aims. */
    var lookAt: PhonePoint? = null
        private set
    /** The mouse on the phone screen, for aiming skills. */
    var mouse: PhonePoint? = null
        private set
    private var firing = false
    private val runs = mutableMapOf<String, Run>()
    private val repeats = mutableMapOf<String, Repeat>()
    private val aims = mutableMapOf<String, Double>()
    private val stickFor = mutableMapOf<Int, String>()
    private val actionFor = mutableMapOf<Int, String>()
    private val walkKeys = mutableSetOf<Int>()
    /** The time of the latest call, in milliseconds. */
    private var clock = 0.0
    /** Until when each new finger's moves wait, and where they go once it may move. */
    private val settleUntil = mutableMapOf<Int, Double>()
    private val owed = sortedMapOf<Int, PhonePoint>()

    init {
        val users = keys.filter { it.usesFinger }
        val ordered = users.filter { it.kind == GameKey.Kind.Joystick } + users.filter { it.kind != GameKey.Kind.Joystick }
        fingers = ordered.take(FINGER_POOL).withIndex().associate { (index, key) -> key.id to index }
        // The first key to claim a code gets it; joysticks come first.
        for (key in ordered) {
            if (key.id !in fingers || key.kind != GameKey.Kind.Joystick) continue
            for (code in key.directionKeys) if (code !in stickFor) stickFor[code] = key.id
            if (key.walk != GameKey.Walk.Off) walkKeys += normalized(key.walkKey)
        }
        for (key in ordered) {
            if (key.id !in fingers || !key.isAction) continue
            val code = normalized(key.keyCode)
            if (code !in stickFor && code !in actionFor) actionFor[code] = key.id
        }
    }

    val lookArea: GameKey? get() = keys.firstOrNull { it.kind == GameKey.Kind.Look }
    val fireSpot: GameKey? get() = keys.firstOrNull { it.kind == GameKey.Kind.Fire }
    /** True while a swipe, combo, auto-fire, or a waiting first move needs [tick]. */
    val needsTick: Boolean get() = runs.isNotEmpty() || repeats.isNotEmpty() || owed.isNotEmpty()
    val isAiming: Boolean get() = aims.isNotEmpty()

    private fun key(id: String): GameKey? = keys.firstOrNull { it.id == id }

    fun handles(code: Int): Boolean {
        val c = normalized(code)
        return c in stickFor || c in actionFor || c in walkKeys
    }

    private fun event(kind: String, finger: Int, x: Double, y: Double) = ControlEvent(kind, x.toFloat(), y.toFloat(), key = finger.toString())

    /**
     * One finger event. A new finger's tdown goes alone: its first move waits [SETTLE_MS] for a later tick, since the
     * phone folds a finger's first stroke to its last point when the move comes in the same batch. Moves in the
     * meantime only update where it goes.
     */
    private fun emit(kind: String, finger: Int, rawX: Double, rawY: Double): List<ControlEvent> {
        val x = rawX.coerceIn(0.0, 1.0)
        val y = rawY.coerceIn(0.0, 1.0)
        when (kind) {
            "tdown" -> {
                settleUntil[finger] = clock + SETTLE_MS
                owed.remove(finger)
                _down[finger] = PhonePoint(x, y)
                return listOf(event(kind, finger, x, y))
            }
            "tmove" -> {
                val until = settleUntil[finger]
                if (until != null && clock < until && finger in _down) {
                    owed[finger] = PhonePoint(x, y)
                    return emptyList()
                }
                settleUntil.remove(finger)
                owed.remove(finger)
                _down[finger] = PhonePoint(x, y)
                return listOf(event(kind, finger, x, y))
            }
            else -> {
                settleUntil.remove(finger)
                _down.remove(finger)
                // A move still waiting goes first, and the finger lifts where it went.
                val at = owed.remove(finger) ?: return listOf(event(kind, finger, x, y))
                return listOf(event("tmove", finger, at.x, at.y), event(kind, finger, at.x, at.y))
            }
        }
    }

    /** Moves that waited for their finger's tdown to go alone, sent once it has. */
    private fun sendOwed(): List<ControlEvent> {
        val events = mutableListOf<ControlEvent>()
        for (finger in owed.keys.toList()) {
            if (clock < (settleUntil[finger] ?: 0.0)) continue
            val at = owed[finger] ?: continue
            events += emit("tmove", finger, at.x, at.y)
        }
        return events
    }

    fun keyDown(rawCode: Int, now: Double = 0.0): List<ControlEvent> {
        clock = now
        val code = normalized(rawCode)
        if (!handles(code) || code in _pressed) return emptyList()
        _pressed += code
        val events = mutableListOf<ControlEvent>()
        if (code in walkKeys) events += updateSticks()
        val stick = stickFor[code]
        val action = actionFor[code]?.let(::key)
        if (stick != null) events += updateStick(stick) else if (action != null) events += press(action, now)
        return events
    }

    fun keyUp(rawCode: Int, now: Double = 0.0): List<ControlEvent> {
        clock = now
        val code = normalized(rawCode)
        if (!_pressed.remove(code)) return emptyList()
        val events = mutableListOf<ControlEvent>()
        if (code in walkKeys) events += updateSticks()
        val stick = stickFor[code]
        val action = actionFor[code]?.let(::key)
        if (stick != null) events += updateStick(stick) else if (action != null) events += release(action, now)
        return events
    }

    private fun press(key: GameKey, now: Double): List<ControlEvent> {
        val finger = fingers[key.id] ?: return emptyList()
        return when (key.kind) {
            GameKey.Kind.Tap -> when (key.mode) {
                GameKey.Mode.Tap -> emit("tdown", finger, key.x, key.y)
                GameKey.Mode.Toggle -> emit(if (finger !in _down) "tdown" else "tup", finger, key.x, key.y)
                GameKey.Mode.AutoFire -> {
                    val period = 1000 / key.rate.coerceIn(1.0, 20.0)
                    repeats[key.id] = Repeat(finger, key.x, key.y, period, now + period / 2, isDown = true)
                    emit("tdown", finger, key.x, key.y)
                }
                GameKey.Mode.Combo -> start(key.id, finger, comboTimeline(key), now)
            }
            GameKey.Kind.Swipe -> start(key.id, finger, swipeTimeline(PhonePoint(key.x, key.y), PhonePoint(key.toX, key.toY), key.duration), now)
            GameKey.Kind.Skill -> {
                aims[key.id] = now
                val events = emit("tdown", finger, key.x, key.y).toMutableList()
                // The move to the aim waits for a later tick, so the finger first lands on the button.
                mouse?.let { aimPoint(PhonePoint(key.x, key.y), key.radius, it, aspect) }?.let { events += emit("tmove", finger, it.x, it.y) }
                events
            }
            else -> emptyList()
        }
    }

    private fun release(key: GameKey, now: Double): List<ControlEvent> {
        val finger = fingers[key.id] ?: return emptyList()
        return when (key.kind) {
            GameKey.Kind.Tap -> when (key.mode) {
                GameKey.Mode.Tap -> _down[finger]?.let { emit("tup", finger, it.x, it.y) }.orEmpty()
                GameKey.Mode.AutoFire -> {
                    repeats.remove(key.id)
                    _down[finger]?.let { emit("tup", finger, it.x, it.y) }.orEmpty()
                }
                // A toggle waits for the next press; a combo plays to its end.
                GameKey.Mode.Toggle, GameKey.Mode.Combo -> emptyList()
            }
            GameKey.Kind.Skill -> {
                val start = aims.remove(key.id) ?: return emptyList()
                val at = _down[finger] ?: return emptyList()
                if (key.quickCast && now - start < QUICK_CAST_MS) {
                    emit("tmove", finger, key.x, key.y) + emit("tup", finger, key.x, key.y)
                } else {
                    emit("tup", finger, at.x, at.y)
                }
            }
            else -> emptyList()
        }
    }

    private fun start(id: String, finger: Int, timeline: List<Scheduled>, now: Double): List<ControlEvent> {
        // A gesture still playing is not started again.
        if (id in runs || finger in _down || timeline.isEmpty()) return emptyList()
        runs[id] = Run(finger, now, timeline)
        return advance(id, now)
    }

    private fun advance(id: String, now: Double): List<ControlEvent> {
        val run = runs[id] ?: return emptyList()
        val events = mutableListOf<ControlEvent>()
        while (run.next < run.timeline.size && run.start + run.timeline[run.next].at <= now) {
            val step = run.timeline[run.next]
            events += emit(step.kind, run.finger, step.x, step.y)
            run.next++
        }
        if (run.next >= run.timeline.size) runs.remove(id)
        return events
    }

    /** Plays whatever swipes, combos, and auto-fire are due by [now]. */
    fun tick(now: Double): List<ControlEvent> {
        clock = now
        val events = sendOwed().toMutableList()
        for (key in keys) if (key.id in runs) events += advance(key.id, now)
        for (key in keys) {
            val item = repeats[key.id] ?: continue
            if (now < item.nextAt) continue
            events += emit(if (item.isDown) "tup" else "tdown", item.finger, item.x, item.y)
            item.isDown = !item.isDown
            item.nextAt += item.period / 2
            // After a stall the rhythm starts again instead of catching up in a burst.
            if (now - item.nextAt > item.period) item.nextAt = now + item.period / 2
        }
        return events
    }

    // Joysticks

    private fun walkScale(stick: GameKey): Double {
        val held = normalized(stick.walkKey) in _pressed
        return when (stick.walk) {
            GameKey.Walk.Off -> 1.0
            GameKey.Walk.FullWithKey -> if (held) 1.0 else 0.5
            GameKey.Walk.HalfWithKey -> if (held) 0.5 else 1.0
        }
    }

    private fun updateSticks(): List<ControlEvent> =
        keys.filter { it.kind == GameKey.Kind.Joystick && it.walk != GameKey.Walk.Off }.flatMap { updateStick(it.id) }

    private fun updateStick(id: String): List<ControlEvent> {
        val stick = key(id) ?: return emptyList()
        val finger = fingers[id] ?: return emptyList()
        val held = stick.directionKeys.map { it in _pressed && stickFor[it] == id }
        val target = stickPoint(PhonePoint(stick.x, stick.y), stick.radius, aspect, held[0], held[1], held[2], held[3], walkScale(stick))
        if (target == null) {
            val current = _down[finger] ?: return emptyList()
            return emit("tup", finger, current.x, current.y)
        }
        val events = mutableListOf<ControlEvent>()
        if (finger !in _down) events += emit("tdown", finger, stick.x, stick.y)
        val current = _down[finger]
        if (current != null && abs(current.x - target.x) < 1e-9 && abs(current.y - target.y) < 1e-9 && events.isEmpty()) return emptyList()
        events += emit("tmove", finger, target.x, target.y)
        return events
    }

    // Skills

    /** The mouse moved on the phone screen; held skills follow it. */
    fun mouseMoved(point: PhonePoint, now: Double = 0.0): List<ControlEvent> {
        clock = now
        mouse = point
        val events = mutableListOf<ControlEvent>()
        for (key in keys) {
            if (key.id !in aims) continue
            val finger = fingers[key.id] ?: continue
            val at = aimPoint(PhonePoint(key.x, key.y), key.radius, point, aspect)
            events += emit("tmove", finger, at.x, at.y)
        }
        return events
    }

    /** Esc while a skill is held: the finger goes back to the button's middle before lifting, which games take as a cancel. */
    fun cancelAim(now: Double = 0.0): List<ControlEvent> {
        clock = now
        val events = mutableListOf<ControlEvent>()
        for (key in keys) {
            if (aims.remove(key.id) == null) continue
            val finger = fingers[key.id] ?: continue
            if (finger !in _down) continue
            events += emit("tmove", finger, key.x, key.y)
            events += emit("tup", finger, key.x, key.y)
        }
        return events
    }

    // Looking with the mouse

    /** Puts the look finger down in the middle of the look area. A mouse touch on the same finger lifts first. */
    fun lookBegin(now: Double = 0.0): List<ControlEvent> {
        clock = now
        val area = lookArea ?: return emptyList()
        if (lookAt != null) return emptyList()
        val events = mutableListOf<ControlEvent>()
        _down[MOUSE_FINGER]?.let { events += emit("tup", MOUSE_FINGER, it.x, it.y) }
        lookAt = PhonePoint(area.x, area.y)
        return events + emit("tdown", MOUSE_FINGER, area.x, area.y)
    }

    /** The mouse button on the picture while playing: finger 9. While looking the finger is the look finger, so it's ignored. */
    fun mouseTouch(kind: String, x: Double, y: Double, now: Double = 0.0): List<ControlEvent> {
        clock = now
        if (lookAt != null) return emptyList()
        val finger = MOUSE_FINGER
        return when (kind) {
            "tdown" -> (_down[finger]?.let { emit("tup", finger, it.x, it.y) }.orEmpty()) + emit("tdown", finger, x, y)
            "tmove", "tup" -> if (finger in _down) emit(kind, finger, x, y) else emptyList()
            else -> emptyList()
        }
    }

    /**
     * Drags the look finger by a share of the phone's width and height, as a thumb swipes to turn a game camera. At the
     * edge of the area the finger lifts and starts again from the middle, so turning never runs out.
     */
    fun look(dx: Double, dy: Double, now: Double = 0.0): List<ControlEvent> {
        clock = now
        val area = lookArea ?: return emptyList()
        val current = lookAt ?: return emptyList()
        var next = PhonePoint(current.x + dx, current.y + dy)
        val events = mutableListOf<ControlEvent>()
        val reachX = area.radius * 1.6
        val reachY = area.radius * 1.6 * aspect
        if (abs(next.x - area.x) > reachX || abs(next.y - area.y) > reachY || next.x !in 0.01..0.99 || next.y !in 0.01..0.99) {
            events += emit("tup", MOUSE_FINGER, current.x, current.y)
            events += emit("tdown", MOUSE_FINGER, area.x, area.y)
            next = PhonePoint(area.x + dx, area.y + dy)
        }
        lookAt = next
        events += emit("tmove", MOUSE_FINGER, next.x, next.y)
        return events
    }

    fun lookEnd(now: Double = 0.0): List<ControlEvent> {
        clock = now
        val current = lookAt ?: return emptyList()
        lookAt = null
        return emit("tup", MOUSE_FINGER, current.x, current.y)
    }

    /** The mouse button while looking presses the click spot. */
    fun fire(isDown: Boolean): List<ControlEvent> {
        val spot = fireSpot ?: return emptyList()
        if (isDown == firing) return emptyList()
        firing = isDown
        return emit(if (isDown) "tdown" else "tup", FIRE_FINGER, spot.x, spot.y)
    }

    /** Where every held finger is, sent again now and then so the phone knows this PC is still there. */
    fun held(): List<ControlEvent> = _down.map { (finger, at) -> event("tmove", finger, at.x, at.y) }

    /** Lifts every finger and stops every gesture, auto-fire, and aim. */
    fun releaseAll(): List<ControlEvent> {
        val events = _down.keys.map { finger -> (owed[finger] ?: _down.getValue(finger)).let { event("tup", finger, it.x, it.y) } }
        _down.clear()
        owed.clear()
        settleUntil.clear()
        _pressed.clear()
        runs.clear()
        repeats.clear()
        aims.clear()
        firing = false
        lookAt = null
        return events
    }

    companion object {
        const val FIRE_FINGER = 8
        const val MOUSE_FINGER = 9
        /** Fingers 0 to 7 belong to keys. */
        const val FINGER_POOL = 8
        /** How long a tap in a combo stays down. */
        const val TAP_MS = 40.0
        /** Swipes move about once a frame. */
        const val STEP_MS = 16.0
        /** A new finger's first move comes at least this long after its tdown, on a later tick. */
        const val SETTLE_MS = 20.0
        /** A skill let go sooner than this, with quick cast on, taps the button's middle. */
        const val QUICK_CAST_MS = 200.0
        /** The mouse this far from the screen's middle, as a share of the phone's width, aims a skill at full reach. */
        const val AIM_REACH = 0.25

        /** Right Shift, Control, and Option count as the left ones. */
        fun normalized(code: Int): Int = when (code) {
            60 -> 56
            62 -> 59
            61 -> 58
            else -> code
        }

        /**
         * Where a joystick's finger goes for the held directions, or null when none is held. Diagonals reach as far as
         * straight moves; [scale] is 0.5 while walking.
         */
        fun stickPoint(center: PhonePoint, radius: Double, aspect: Double, up: Boolean, left: Boolean, down: Boolean, right: Boolean, scale: Double = 1.0): PhonePoint? {
            var dx = 0.0
            var dy = 0.0
            if (left) dx -= 1
            if (right) dx += 1
            if (up) dy -= 1
            if (down) dy += 1
            val length = sqrt(dx * dx + dy * dy)
            if (length <= 0) return null
            return PhonePoint(center.x + dx / length * radius * scale, center.y + dy / length * radius * scale * aspect)
        }

        /** The skill finger for a mouse point: its direction and distance from the screen's middle, kept within reach. */
        fun aimPoint(button: PhonePoint, radius: Double, mouse: PhonePoint, aspect: Double, reach: Double = AIM_REACH): PhonePoint {
            // Measured in phone widths, so up and sideways count the same.
            val dx = mouse.x - 0.5
            val dy = (mouse.y - 0.5) / aspect
            var ox = dx / reach * radius
            var oy = dy / reach * radius
            val length = sqrt(ox * ox + oy * oy)
            if (length > radius && length > 0) { ox *= radius / length; oy *= radius / length }
            return PhonePoint(button.x + ox, button.y + oy * aspect)
        }

        /** A finger from [a] to [b] over [durationMs], moving about every 16 ms. */
        fun swipeTimeline(a: PhonePoint, b: PhonePoint, durationMs: Double, stepMs: Double = STEP_MS): List<Scheduled> {
            val duration = maxOf(durationMs, 1.0)
            val count = maxOf(1, ceil(duration / stepMs).toInt())
            val out = mutableListOf(Scheduled(0.0, "tdown", a.x, a.y))
            for (index in 1..count) {
                val t = index.toDouble() / count
                out += Scheduled(duration * t, "tmove", a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
            out += Scheduled(duration, "tup", b.x, b.y)
            return out
        }

        /** A combo's steps in order on one finger. With no steps it taps the key's own spot. */
        fun comboTimeline(key: GameKey, tapMs: Double = TAP_MS): List<Scheduled> {
            val steps = key.steps.ifEmpty { listOf(GameKey.Step(kind = GameKey.Step.Kind.Tap, x = key.x, y = key.y)) }
            val out = mutableListOf<Scheduled>()
            var t = 0.0
            for (step in steps) {
                when (step.kind) {
                    GameKey.Step.Kind.Tap -> {
                        out += Scheduled(t, "tdown", step.x, step.y)
                        out += Scheduled(t + tapMs, "tup", step.x, step.y)
                        t += tapMs
                    }
                    GameKey.Step.Kind.Wait -> t += maxOf(step.ms, 0.0)
                    GameKey.Step.Kind.Swipe -> {
                        val start = t
                        out += swipeTimeline(PhonePoint(step.x, step.y), PhonePoint(step.toX, step.toY), step.ms).map { it.copy(at = start + it.at) }
                        t += maxOf(step.ms, 1.0)
                    }
                }
            }
            return out
        }
    }
}
