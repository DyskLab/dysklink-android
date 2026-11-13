package id.dysklink.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.phonelink.android.core.ControlEvent
import java.awt.event.KeyEvent
import java.io.File
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** PC keys to the Mac key codes game profiles keep, by US key position, so a profile works on both. */
object GameKeyCodes {
    const val SHIFT = 56
    const val CONTROL = 59
    const val OPTION = 58
    const val ESCAPE = 53
    /** ` starts and stops aiming with the mouse, as on the Mac. */
    const val LOOK = 50

    private val fromVk: Map<Int, Int> = buildMap {
        // The lower code wins: Return over keypad Enter, = over keypad =.
        for (code in 127 downTo 0) MacKeys.vk(code)?.let { put(it, code) }
    }

    /** The Mac code for an AWT key, or null for one the Mac doesn't have. [location] tells right modifiers apart. */
    fun mac(vk: Int, location: Int = KeyEvent.KEY_LOCATION_STANDARD): Int? {
        val right = location == KeyEvent.KEY_LOCATION_RIGHT
        return when (vk) {
            KeyEvent.VK_SHIFT -> if (right) 60 else SHIFT
            KeyEvent.VK_CONTROL -> if (right) 62 else CONTROL
            KeyEvent.VK_ALT -> if (right) 61 else OPTION
            KeyEvent.VK_ALT_GRAPH -> 61
            KeyEvent.VK_ENTER -> if (location == KeyEvent.KEY_LOCATION_NUMPAD) 76 else 36
            else -> fromVk[vk]
        }
    }

    /** What a key is called on the picture and in the panel. */
    fun label(code: Int): String = when (code) {
        49 -> "Space"
        48 -> "Tab"
        36, 76 -> "Enter"
        51 -> "Bksp"
        56, 60 -> "Shift"
        59, 62 -> "Ctrl"
        58, 61 -> "Alt"
        123 -> "←"
        124 -> "→"
        125 -> "↓"
        126 -> "↑"
        53 -> "Esc"
        else -> MacKeys.vk(code)?.let { vk ->
            when (vk) {
                in KeyEvent.VK_F1..KeyEvent.VK_F12 -> "F${vk - KeyEvent.VK_F1 + 1}"
                in KeyEvent.VK_NUMPAD0..KeyEvent.VK_NUMPAD9 -> "Num${vk - KeyEvent.VK_NUMPAD0}"
                KeyEvent.VK_BACK_QUOTE -> "`"
                KeyEvent.VK_MINUS -> "-"
                KeyEvent.VK_EQUALS -> "="
                KeyEvent.VK_OPEN_BRACKET -> "["
                KeyEvent.VK_CLOSE_BRACKET -> "]"
                KeyEvent.VK_SEMICOLON -> ";"
                KeyEvent.VK_QUOTE -> "'"
                KeyEvent.VK_COMMA -> ","
                KeyEvent.VK_PERIOD -> "."
                KeyEvent.VK_SLASH -> "/"
                KeyEvent.VK_BACK_SLASH -> "\\"
                else -> if (vk in KeyEvent.VK_A..KeyEvent.VK_Z || vk in KeyEvent.VK_0..KeyEvent.VK_9) vk.toChar().toString()
                else KeyEvent.getKeyText(vk).take(6)
            }
        } ?: "?"
    }
}

/**
 * Game profiles per phone, kept as the Mac's JSON in the data folder (`gamekeys-<phone>.json`), and how far a mouse
 * move turns, for every game (`gamekeys-settings.json`). [dir] null keeps them in memory only, for snapshots.
 */
class GameKeyStore(private val dir: File?) {
    data class Saved(val profiles: List<GameProfile>, val current: Int)

    private val memory = mutableMapOf<String, Saved>()
    private var speed: Double? = null
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun file(phoneId: String) = dir?.let { File(it, "gamekeys-${phoneId.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' }.take(80)}.json") }

    fun load(phoneId: String): Saved {
        memory[phoneId]?.let { return it }
        val text = file(phoneId)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        val root = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val profiles = (root?.get("profiles") as? JsonArray)?.let { GameKeysJson.decode(it.toString()) }.orEmpty()
        val saved = if (profiles.isEmpty()) Saved(listOf(GameProfile("Default", emptyList())), 0)
        else Saved(profiles, ((root?.get("current") as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, profiles.size - 1))
        memory[phoneId] = saved
        return saved
    }

    fun save(phoneId: String, profiles: List<GameProfile>, current: Int) {
        memory[phoneId] = Saved(profiles, current)
        val target = file(phoneId) ?: return
        val body = JsonObject(mapOf("profiles" to JsonArray(profiles.map(GameKeysJson::profileJson)), "current" to JsonPrimitive(current)))
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(json.encodeToString(JsonElement.serializer(), body))
            if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
        }
    }

    var lookSpeed: Double
        get() = speed ?: (dir?.let { File(it, "gamekeys-settings.json") }?.takeIf { it.isFile }
            ?.let { runCatching { ((json.parseToJsonElement(it.readText()) as? JsonObject)?.get("lookSpeed") as? JsonPrimitive)?.doubleOrNull }.getOrNull() }
            ?.takeIf { it in 0.1..5.0 } ?: 1.0).also { speed = it }
        set(value) {
            speed = value
            dir?.let { runCatching { File(it, "gamekeys-settings.json").writeText("{\"lookSpeed\":$value}") } }
        }
}

/** Mouse moves for looking: turned with the picture, scaled as on the Mac. */
object LookMath {
    /** The share of the phone's width one point (logical pixel) of mouse movement turns, before the speed setting. */
    const val SCALE = 1.0 / 1400

    /**
     * A mouse move of [dx], [dy] logical pixels over the picture turned [rotation] degrees clockwise, as a move of the
     * phone's own picture: shares of its width and height.
     */
    fun phoneDelta(dx: Double, dy: Double, rotation: Int, aspect: Double, speed: Double = 1.0): PhonePoint {
        val u = dx * SCALE
        val v = dy * SCALE
        val (px, py) = when (Math.floorMod(rotation, 360)) {
            90 -> v to -u
            180 -> -u to -v
            270 -> -v to u
            else -> u to v
        }
        return PhonePoint(px * speed, py * aspect * speed)
    }
}

/**
 * Holds the pointer in the middle of the picture while looking (Windows and X11, through Robot): each poll reads where
 * the pointer went, counts the move, and puts it back once it strays [margin] pixels, so it never reaches an edge.
 * Positions are logical screen pixels.
 */
class PointerRecentre(private val warp: (Int, Int) -> Unit, private val margin: Int = 80) {
    private var centre: java.awt.Point? = null
    private var last: java.awt.Point? = null

    val active: Boolean get() = centre != null

    fun begin(at: java.awt.Point) {
        centre = at
        last = at
        warp(at.x, at.y)
    }

    /** The move since the last poll, or null when there was none. */
    fun sample(pointer: java.awt.Point?): Pair<Int, Int>? {
        val c = centre ?: return null
        val from = last ?: return null
        val p = pointer ?: return null
        val dx = p.x - from.x
        val dy = p.y - from.y
        last = p
        if (abs(p.x - c.x) > margin || abs(p.y - c.y) > margin) {
            warp(c.x, c.y)
            last = c
        }
        return if (dx == 0 && dy == 0) null else dx to dy
    }

    fun end() {
        centre = null
        last = null
    }
}

/**
 * X11 repeats a held key as a release and a press at once, which would lift a held finger. A release waits [delayMs];
 * a press of the same key meanwhile cancels both. Windows only repeats presses, which the engine ignores anyway.
 */
class ReleaseDebounce(private val delayMs: Double = 25.0) {
    private val waiting = linkedMapOf<Int, Double>()

    /** True when this press is a real one, false for a repeat that cancelled a release. */
    fun down(code: Int): Boolean = waiting.remove(code) == null

    fun up(code: Int, now: Double) { waiting[code] = now + delayMs }

    /** Releases that are now real. */
    fun due(now: Double): List<Int> {
        val out = waiting.filterValues { it <= now }.keys.toList()
        out.forEach(waiting::remove)
        return out
    }

    /** Every release waiting, at once (focus lost). */
    fun flush(): List<Int> = waiting.keys.toList().also { waiting.clear() }
}

/**
 * Game keys for the phone's screen on this PC: the layout per phone, editing, and play, as the Mac's GameKeysModel.
 * The tab and the pop-out window share one per phone. Everything runs on the UI thread; [tick] is called every few
 * milliseconds while it's on, for swipes, combos, auto-fire, and the heartbeat.
 */
class GameKeysController(
    private val phoneId: String,
    private val store: GameKeyStore,
    var send: (ControlEvent) -> Unit = {},
    /** Milliseconds; tests set their own clock. */
    var clock: () -> Double = { System.nanoTime() / 1_000_000.0 },
) {
    /** What the next key press or click while editing goes to. */
    sealed interface Capture {
        data class Key(val id: String) : Capture
        data class StickKeys(val id: String, val codes: List<Int>, val labels: List<String>) : Capture
        data class Combo(val id: String) : Capture
    }

    enum class Direction { Up, Down, Left, Right }

    var enabled by mutableStateOf(false)
        private set
    var editing by mutableStateOf(false)
        private set
    /** A kind picked in the panel's palette: the next click on the picture places it. */
    var placing by mutableStateOf<GameKey.Kind?>(null)
    var pendingKind by mutableStateOf(GameKey.Kind.Tap)
        private set
    var keys by mutableStateOf<List<GameKey>>(emptyList())
        private set
    var profiles by mutableStateOf<List<GameProfile>>(emptyList())
        private set
    var current by mutableStateOf(0)
        private set
    val profileName: String get() = profiles.getOrNull(current)?.name ?: "Default"
    /** The mouse aims through the look area; the pointer is held meanwhile. */
    var looking by mutableStateOf(false)
        private set
    /** How far a mouse move turns, saved for every game. */
    var lookSpeed by mutableStateOf(store.lookSpeed)
        private set
    /** A spot clicked while editing, waiting for a key. */
    var pending by mutableStateOf<PhonePoint?>(null)
    /** A drag while editing, waiting for a key to become a swipe. */
    var pendingSwipe by mutableStateOf<Pair<PhonePoint, PhonePoint>?>(null)
    /** The key the panel shows. */
    var selected by mutableStateOf<String?>(null)
    var capture by mutableStateOf<Capture?>(null)
    /** A short note, such as why a key couldn't be placed. */
    var message by mutableStateOf<String?>(null)

    var aspect = 0.5
        private set
    private var engine = GameKeyEngine(emptyList(), aspect)
    private var heartbeatAt: Double? = null
    private var lastAimAt = Double.NEGATIVE_INFINITY
    private var lastTouchMoveAt = Double.NEGATIVE_INFINITY
    private val now: Double get() = clock()

    /** Fingers held on the phone now (for tests and the status line). */
    val heldFingers: Map<Int, PhonePoint> get() = engine.down

    fun enable(on: Boolean) {
        if (on == enabled) return
        if (!on) { edit(false); release() }
        enabled = on
    }

    fun edit(on: Boolean) {
        placing = null
        pending = null
        pendingSwipe = null
        capture = null
        message = null
        if (!on) selected = null
        if (on) release()
        editing = on
    }

    private fun save() {
        if (current in profiles.indices) profiles = profiles.toMutableList().also { it[current] = it[current].copy(keys = keys) }
        store.save(phoneId, profiles, current)
        rebuild()
    }

    // Profiles

    fun selectProfile(index: Int) {
        if (index !in profiles.indices || index == current) return
        current = index
        keys = profiles[index].keys
        selected = null
        save()
    }

    fun addProfile(name: String) {
        val trimmed = name.trim().take(40)
        profiles = profiles + GameProfile(trimmed.ifEmpty { "Game ${profiles.size + 1}" }, emptyList())
        current = profiles.size - 1
        keys = emptyList()
        selected = null
        save()
    }

    fun renameProfile(name: String) {
        val trimmed = name.trim().take(40)
        if (trimmed.isEmpty() || current !in profiles.indices) return
        profiles = profiles.toMutableList().also { it[current] = it[current].copy(name = trimmed) }
        save()
    }

    fun deleteProfile() {
        if (profiles.size <= 1) return clear()
        profiles = profiles.toMutableList().also { it.removeAt(current) }
        current = maxOf(0, current - 1)
        keys = profiles[current].keys
        selected = null
        save()
    }

    /** The current profile as the Mac's JSON. */
    fun exportProfile(): String = GameKeysJson.encodeProfile(GameProfile(profileName, keys))

    /**
     * Adds the profiles in [text] (one, or the Mac's list) and switches to the first. Ids are made new, so importing
     * the same file twice gives two profiles. Returns how many came in; 0 when it wasn't a game profile.
     */
    fun importProfiles(text: String): Int {
        val found = GameKeysJson.decode(text).map { profile ->
            GameProfile(profile.name.trim().ifEmpty { "Imported" }, profile.keys.map { key -> key.copy(id = GameKey.newId(), steps = key.steps.map { it.copy(id = GameKey.newId()) }) })
        }
        if (found.isEmpty()) {
            message = "That file isn't a game profile."
            return 0
        }
        val first = profiles.size
        // A first profile with nothing in it is replaced, so an import doesn't leave an empty Default behind.
        val keep = if (profiles.size == 1 && profiles[0].keys.isEmpty()) emptyList() else profiles
        profiles = keep + found
        current = if (keep.isEmpty()) 0 else first
        keys = profiles[current].keys
        selected = null
        message = null
        save()
        return found.size
    }

    // Looking

    val hasLook: Boolean get() = keys.any { it.kind == GameKey.Kind.Look }

    /** Starts or stops aiming with the mouse. Only works with a look area placed. */
    fun toggleLook() {
        if (!enabled || editing) return
        if (looking) {
            dispatch(engine.lookEnd(now) + engine.fire(false))
            looking = false
        } else if (hasLook) {
            dispatch(engine.lookBegin(now))
            looking = true
        }
    }

    fun chooseLookSpeed(value: Double) {
        lookSpeed = value
        store.lookSpeed = value
    }

    /** A mouse move while looking, in logical pixels over the picture turned [rotation] degrees. */
    fun lookPixels(dx: Double, dy: Double, rotation: Int) {
        if (!looking) return
        val d = LookMath.phoneDelta(dx, dy, rotation, aspect, lookSpeed)
        dispatch(engine.look(d.x, d.y, now))
    }

    fun fire(down: Boolean) {
        if (!looking) return
        dispatch(engine.fire(down))
    }

    fun placeLook(x: Double, y: Double) {
        val key = GameKey(kind = GameKey.Kind.Look, label = "Look", x = x, y = y, radius = 0.12)
        keys = keys.filter { it.kind != GameKey.Kind.Look } + key
        selected = key.id
        save()
    }

    fun placeFire(x: Double, y: Double) {
        val key = GameKey(kind = GameKey.Kind.Fire, label = "Click", x = x, y = y)
        keys = keys.filter { it.kind != GameKey.Kind.Fire } + key
        selected = key.id
        save()
    }

    private fun rebuild() {
        release()
        engine = GameKeyEngine(keys, aspect)
    }

    /** The phone's picture width over height; joysticks and aiming reach as far up as sideways. */
    fun setAspect(value: Double) {
        if (!value.isFinite() || value <= 0 || abs(value - aspect) <= 0.001) return
        aspect = value
        rebuild()
    }

    val tapCount: Int get() = keys.count { it.isAction }
    val joystickCount: Int get() = keys.count { it.kind == GameKey.Kind.Joystick }
    val freeFingers: Int get() = maxOf(0, GameKeyEngine.FINGER_POOL - keys.count { it.usesFinger })
    val selectedKey: GameKey? get() = keys.firstOrNull { it.id == selected }

    /** True when there is a finger for one more key; otherwise says why. */
    private fun roomForFinger(): Boolean {
        if (freeFingers > 0) return true
        message = "No free finger: all ${GameKeyEngine.FINGER_POOL} key fingers are used (8 is the click spot, 9 the mouse). Delete a key first."
        return false
    }

    /** True when another key or joystick also uses one of this key's keys. */
    fun conflicts(key: GameKey): Boolean {
        fun codes(k: GameKey): Set<Int> = when {
            k.kind == GameKey.Kind.Joystick -> k.directionKeys.toSet()
            k.isAction -> setOf(GameKeyEngine.normalized(k.keyCode))
            else -> emptySet()
        }
        val mine = codes(key)
        return keys.any { it.id != key.id && codes(it).any(mine::contains) }
    }

    // Editing

    /** A click on the picture while a palette kind is picked. */
    fun place(kind: GameKey.Kind, x: Double, y: Double) {
        placing = null
        when (kind) {
            GameKey.Kind.Joystick -> placeJoystick(x, y)
            GameKey.Kind.Look -> placeLook(x, y)
            GameKey.Kind.Fire -> placeFire(x, y)
            GameKey.Kind.Tap, GameKey.Kind.Skill, GameKey.Kind.Swipe -> {
                if (!roomForFinger()) return
                pendingSwipe = null
                pendingKind = kind
                pending = PhonePoint(x, y)
            }
        }
    }

    /** A plain click on the picture: a spot waiting for a tap key. */
    fun pendTap(x: Double, y: Double) {
        pendingSwipe = null
        pendingKind = GameKey.Kind.Tap
        pending = PhonePoint(x, y)
    }

    /** Esc while editing: stops recording or picking, or drops the waiting spot, or closes the settings. */
    fun cancelEditStep() {
        when {
            placing != null -> placing = null
            capture != null -> capture = null
            pending != null || pendingSwipe != null -> { pending = null; pendingSwipe = null }
            else -> selected = null
        }
    }

    /** Gives the waiting spot, swipe, or picked key a keyboard key. Returns false when nothing is waiting. */
    fun assign(rawCode: Int, label: String = GameKeyCodes.label(rawCode)): Boolean {
        // Right Shift, Control, and Alt are stored as the left ones, as the engine reads them.
        val code = GameKeyEngine.normalized(rawCode)
        when (val waiting = capture) {
            is Capture.Key -> {
                capture = null
                update(waiting.id) { it.copy(keyCode = code, label = label) }
                return true
            }
            is Capture.StickKeys -> {
                if (code in waiting.codes) return true
                val codes = waiting.codes + code
                val labels = waiting.labels + label
                if (codes.size == 4) {
                    capture = null
                    update(waiting.id) { it.copy(keySet = GameKey.KeySet.Custom, customKeys = codes, customLabels = labels, label = labels.joinToString("")) }
                } else {
                    capture = Capture.StickKeys(waiting.id, codes, labels)
                }
                return true
            }
            is Capture.Combo -> return true
            null -> Unit
        }
        val swipe = pendingSwipe
        if (swipe != null) {
            pendingSwipe = null
            val replacing = keys.any { it.isAction && it.keyCode == code }
            if (!replacing && !roomForFinger()) return true
            val key = GameKey(kind = GameKey.Kind.Swipe, keyCode = code, label = label, x = swipe.first.x, y = swipe.first.y, toX = swipe.second.x, toY = swipe.second.y)
            keys = keys.filterNot { it.isAction && it.keyCode == code } + key
            selected = key.id
            message = null
            save()
            return true
        }
        val spot = pending ?: return false
        pending = null
        val replacing = keys.any { it.isAction && it.keyCode == code }
        if (!replacing && !roomForFinger()) return true
        // A key does one thing, so an older spot for it moves here.
        var key = GameKey(kind = pendingKind, keyCode = code, label = label, x = spot.x, y = spot.y)
        if (key.kind == GameKey.Kind.Skill) key = key.copy(radius = 0.07)
        if (key.kind == GameKey.Kind.Swipe) {
            val end = presetEnd(Direction.Up, spot, aspect)
            key = key.copy(toX = end.x, toY = end.y)
        }
        pendingKind = GameKey.Kind.Tap
        keys = keys.filterNot { it.isAction && it.keyCode == code } + key
        selected = key.id
        message = null
        save()
        return true
    }

    /** Adds a joystick, with the first key set no other joystick uses. */
    fun placeJoystick(x: Double, y: Double) {
        if (!roomForFinger()) return
        val used = keys.filter { it.kind == GameKey.Kind.Joystick }.map { it.keySet }.toSet()
        val set = listOf(GameKey.KeySet.Wasd, GameKey.KeySet.Arrows, GameKey.KeySet.Ijkl).firstOrNull { it !in used } ?: GameKey.KeySet.Wasd
        val base = GameKey(kind = GameKey.Kind.Joystick, x = x, y = y, keySet = set)
        val key = base.copy(label = base.directionLabels.joinToString(""))
        keys = keys + key
        selected = key.id
        message = null
        save()
    }

    fun update(id: String, change: (GameKey) -> GameKey) {
        val index = keys.indexOfFirst { it.id == id }
        if (index < 0) return
        val changed = change(keys[index])
        if (changed == keys[index]) return
        keys = keys.toMutableList().also { it[index] = changed }
        save()
    }

    /** Turns a tap key into a skill or swipe and back. Joysticks and look areas stay what they are. */
    fun setKind(id: String, kind: GameKey.Kind) = update(id) { key ->
        if (!key.isAction || kind !in setOf(GameKey.Kind.Tap, GameKey.Kind.Skill, GameKey.Kind.Swipe) || key.kind == kind) return@update key
        var out = key.copy(kind = kind)
        if (kind == GameKey.Kind.Skill) out = out.copy(radius = 0.07)
        if (kind == GameKey.Kind.Swipe && abs(key.toX - key.x) + abs(key.toY - key.y) < 0.01) {
            val end = presetEnd(Direction.Up, PhonePoint(key.x, key.y), aspect)
            out = out.copy(toX = end.x, toY = end.y)
        }
        out
    }

    fun swipePreset(id: String, direction: Direction) = update(id) { key ->
        val end = presetEnd(direction, PhonePoint(key.x, key.y), aspect)
        key.copy(toX = end.x, toY = end.y)
    }

    /** Starts recording a combo: each click on the picture adds a tap, each drag a swipe. */
    fun recordCombo(id: String) {
        update(id) { it.copy(mode = GameKey.Mode.Combo, steps = emptyList()) }
        capture = Capture.Combo(id)
        selected = id
    }

    /** Adds a step to the combo being recorded, with an 80 ms wait before it. */
    fun recordStep(step: GameKey.Step) {
        val id = (capture as? Capture.Combo)?.id ?: return
        update(id) { key ->
            val wait = key.steps.lastOrNull()?.takeIf { it.kind != GameKey.Step.Kind.Wait }?.let { listOf(GameKey.Step(kind = GameKey.Step.Kind.Wait, ms = 80.0)) }.orEmpty()
            key.copy(steps = key.steps + wait + step)
        }
    }

    /** Drags a control while editing; [end] moves a swipe's end. Saved by [finishMove]. */
    fun move(id: String, x: Double, y: Double, end: Boolean = false) {
        val index = keys.indexOfFirst { it.id == id }
        if (index < 0) return
        val key = keys[index]
        val moved = if (end) key.copy(toX = x.coerceIn(0.0, 1.0), toY = y.coerceIn(0.0, 1.0))
        else key.copy(x = x.coerceIn(0.0, 1.0), y = y.coerceIn(0.0, 1.0))
        keys = keys.toMutableList().also { it[index] = moved }
    }

    fun finishMove() = save()

    fun resize(id: String, by: Double) {
        val index = keys.indexOfFirst { it.id == id }
        if (index < 0 || keys[index].kind !in setOf(GameKey.Kind.Joystick, GameKey.Kind.Look, GameKey.Kind.Skill)) return
        keys = keys.toMutableList().also { it[index] = it[index].copy(radius = (it[index].radius + by).coerceIn(0.03, 0.25)) }
        save()
    }

    fun remove(id: String) {
        keys = keys.filter { it.id != id }
        if (selected == id) selected = null
        if ((capture as? Capture.Combo)?.id == id) capture = null
        save()
    }

    fun clear() {
        keys = emptyList()
        selected = null
        capture = null
        save()
    }

    // Playing

    val playing: Boolean get() = enabled && !editing

    private fun dispatch(events: List<ControlEvent>) {
        events.forEach { send(it) }
        val held = engine.down.isNotEmpty()
        // Held fingers are sent again every 1.5 s, starting 1.5 s after the first went down.
        if (held && heartbeatAt == null) heartbeatAt = now + HEARTBEAT_MS
        if (!held) heartbeatAt = null
    }

    /** Returns true when the key belongs to the game layout. */
    fun key(code: Int, down: Boolean): Boolean {
        if (!playing || !engine.handles(code)) return false
        dispatch(if (down) engine.keyDown(code, now) else engine.keyUp(code, now))
        return true
    }

    /** X11 repeats a held key as a release and a press; releases wait a moment there (see [ReleaseDebounce]). */
    var debounceReleases = false
    private val debounce = ReleaseDebounce()

    /** A key pressed on the picture while playing. Returns true when it belongs to the game. */
    fun keyPressed(code: Int): Boolean {
        if (!playing || !engine.handles(code)) return false
        if (debounceReleases && !debounce.down(GameKeyEngine.normalized(code))) return true
        return key(code, true)
    }

    fun keyReleased(code: Int): Boolean {
        if (!playing || !engine.handles(code)) return false
        if (!debounceReleases) return key(code, false)
        debounce.up(GameKeyEngine.normalized(code), now)
        return true
    }

    /** Asks the picture to take the keyboard again, after a click in the panel. */
    var focusRequests by mutableStateOf(0)
        private set

    fun refocus() { focusRequests++ }

    /** The mouse over the phone picture while playing, for aiming skills; about 90 a second at most. */
    fun mouse(at: PhonePoint) {
        if (!playing || looking) return
        val time = now
        if (time - lastAimAt < MOVE_INTERVAL_MS) return
        lastAimAt = time
        dispatch(engine.mouseMoved(at, time))
    }

    /** The mouse button on the picture while playing, as finger 9 ("tdown", "tmove", "tup"). */
    fun mouseTouch(kind: String, at: PhonePoint) {
        if (!playing) return
        val time = now
        if (kind == "tmove") {
            if (time - lastTouchMoveAt < MOVE_INTERVAL_MS) return
            lastTouchMoveAt = time
        }
        dispatch(engine.mouseTouch(kind, at.x, at.y, time))
    }

    /** Esc while a skill is held cancels it. Returns true when one was held. */
    fun cancelSkill(): Boolean {
        if (!playing || !engine.isAiming) return false
        dispatch(engine.cancelAim(now))
        return true
    }

    /** Lifts every finger: focus lost, play stopped, the tab left, the phone gone. */
    fun release() {
        debounce.flush()
        dispatch(engine.releaseAll())
        looking = false
    }

    /** Gestures due by now, and the heartbeat for held fingers. */
    fun tick() {
        val time = now
        if (debounceReleases) debounce.due(time).forEach { key(it, false) }
        if (engine.needsTick) dispatch(engine.tick(time))
        val beat = heartbeatAt ?: return
        if (time < beat) return
        engine.held().forEach { send(it) }
        heartbeatAt = if (time - beat > HEARTBEAT_MS) time + HEARTBEAT_MS else beat + HEARTBEAT_MS
    }

    // Last, once every field above is set.
    init {
        val saved = store.load(phoneId)
        profiles = saved.profiles
        current = saved.current
        keys = profiles[current].keys
        rebuild()
    }

    companion object {
        /** The phone lifts all fingers after 5 s without news; held ones are sent again well before. */
        const val HEARTBEAT_MS = 1_500.0
        const val MOVE_INTERVAL_MS = 1000.0 / 90

        /** Where a swipe preset ends: a sixth of the phone's width from the spot. */
        fun presetEnd(direction: Direction, from: PhonePoint, aspect: Double): PhonePoint {
            val length = 0.16
            val end = when (direction) {
                Direction.Up -> PhonePoint(from.x, from.y - length * aspect)
                Direction.Down -> PhonePoint(from.x, from.y + length * aspect)
                Direction.Left -> PhonePoint(from.x - length, from.y)
                Direction.Right -> PhonePoint(from.x + length, from.y)
            }
            return PhonePoint(end.x.coerceIn(0.0, 1.0), end.y.coerceIn(0.0, 1.0))
        }
    }
}
