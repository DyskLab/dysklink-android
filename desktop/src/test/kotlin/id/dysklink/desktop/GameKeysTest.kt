package id.dysklink.desktop

import id.phonelink.android.core.ControlEvent
import java.awt.Point
import java.awt.event.KeyEvent
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Game keys on the phone's screen: the Mac's data model and JSON, the engine, and the controller's timing. */
class GameKeysTest {
    private fun near(expected: Double, actual: Double, message: String = "") = assertTrue(abs(expected - actual) < 1e-6, "$message: expected $expected, got $actual")

    private class Rig(keys: List<GameKey> = emptyList(), dir: java.io.File? = null, phone: String = "p1") {
        var time = 0.0
        val sent = mutableListOf<ControlEvent>()
        val game = GameKeysController(phone, GameKeyStore(dir), send = { sent += it }, clock = { time })
        init {
            if (keys.isNotEmpty()) game.importProfiles(GameKeysJson.encodeProfile(GameProfile("All", keys)))
            game.enable(true)
            sent.clear()
        }
        fun take(): List<String> = sent.map { "${it.kind}:${it.key}" }.also { sent.clear() }
    }

    // The Mac's JSON

    private val everyKind = listOf(
        GameKey(kind = GameKey.Kind.Joystick, x = 0.2, y = 0.75, radius = 0.1, keySet = GameKey.KeySet.Custom, customKeys = listOf(13, 0, 1, 2), customLabels = listOf("W", "A", "S", "D"), walk = GameKey.Walk.HalfWithKey, walkKey = 59, label = "WASD"),
        GameKey(kind = GameKey.Kind.Tap, keyCode = 49, label = "Space", x = 0.85, y = 0.8),
        GameKey(kind = GameKey.Kind.Tap, keyCode = 14, label = "E", x = 0.7, y = 0.6, mode = GameKey.Mode.Combo, steps = listOf(
            GameKey.Step(kind = GameKey.Step.Kind.Tap, x = 0.1, y = 0.2),
            GameKey.Step(kind = GameKey.Step.Kind.Wait, ms = 120.0),
            GameKey.Step(kind = GameKey.Step.Kind.Swipe, x = 0.3, y = 0.4, toX = 0.5, toY = 0.6, ms = 200.0),
        )),
        GameKey(kind = GameKey.Kind.Skill, keyCode = 12, label = "Q", x = 0.75, y = 0.7, radius = 0.07, quickCast = true),
        GameKey(kind = GameKey.Kind.Swipe, keyCode = 3, label = "F", x = 0.5, y = 0.5, toX = 0.5, toY = 0.4, duration = 160.0),
        GameKey(kind = GameKey.Kind.Look, label = "Look", x = 0.65, y = 0.4, radius = 0.12),
        GameKey(kind = GameKey.Kind.Fire, label = "Click", x = 0.9, y = 0.55),
        GameKey(kind = GameKey.Kind.Tap, keyCode = 15, label = "R", x = 0.6, y = 0.9, mode = GameKey.Mode.AutoFire, rate = 12.0),
    )

    @Test
    fun profilesGoRoundTheMacsJsonUnchanged() {
        val profile = GameProfile("Mobile Legends / ranked", everyKind)
        assertEquals(listOf(profile), GameKeysJson.decode(GameKeysJson.encodeProfile(profile)))
        val two = listOf(profile, GameProfile("Empty", emptyList()))
        assertEquals(two, GameKeysJson.decode(GameKeysJson.encodeProfiles(two)))
        // Every field the Mac writes is there, under the Mac's names, so Swift's decoder (which needs a step's every field) reads it.
        val key = GameKeysJson.keyJson(everyKind[2])
        assertEquals(
            listOf("id", "kind", "keyCode", "label", "x", "y", "radius", "mode", "rate", "keySet", "customKeys", "customLabels", "walk", "walkKey", "toX", "toY", "duration", "quickCast", "steps"),
            key.keys.toList(),
        )
        assertEquals("combo", (key["mode"] as kotlinx.serialization.json.JsonPrimitive).content)
        val step = (key["steps"] as kotlinx.serialization.json.JsonArray)[2] as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("id", "kind", "x", "y", "toX", "toY", "ms"), step.keys)
        assertTrue(Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}").matches(everyKind[0].id), "Ids look like Swift's UUIDs")
    }

    @Test
    fun aProfileMadeOnTheMacLoadsHere() {
        // As Swift's JSONEncoder writes it (slashes escaped, whole numbers without a point), plus an old layout from
        // before the newer fields, which the Mac also reads with its defaults.
        val mac = """
            [{"name":"PUBG \/ TPP","keys":[
              {"id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","kind":"joystick","keyCode":0,"label":"WASD","x":0.18,"y":0.72,
               "radius":0.09,"mode":"tap","rate":10,"keySet":"wasd","customKeys":[],"customLabels":[],"walk":"fullWithKey",
               "walkKey":56,"toX":0,"toY":0,"duration":120,"quickCast":false,"steps":[]},
              {"id":"0E984725-C51C-4BF4-9960-E1C80E27ABA0","kind":"tap","keyCode":49,"label":"Space","x":0.9,"y":0.8},
              {"kind":"look","x":0.6,"y":0.4,"radius":0.12},
              {"kind":"fire","label":"Click","x":0.88,"y":0.55},
              {"id":"A1B2C3D4-0000-4000-8000-000000000001","kind":"tap","keyCode":14,"label":"E","x":0.7,"y":0.6,"mode":"combo",
               "steps":[{"id":"A1B2C3D4-0000-4000-8000-000000000002","kind":"tap","x":0.1,"y":0.2,"toX":0,"toY":0,"ms":80},
                        {"id":"A1B2C3D4-0000-4000-8000-000000000003","kind":"wait","x":0,"y":0,"toX":0,"toY":0,"ms":80}]},
              {"kind":"rocket","x":0.5,"y":0.5},
              {"kind":"tap","label":"no place"}
            ]},{"name":"Default","keys":[]}]
        """.trimIndent()
        val profiles = GameKeysJson.decode(mac)
        assertEquals(listOf("PUBG / TPP", "Default"), profiles.map { it.name })
        val keys = profiles[0].keys
        assertEquals(5, keys.size, "Unknown kinds and keys without a place are left out, as the Mac's decoder fails them")
        assertEquals(GameKey.Walk.FullWithKey, keys[0].walk)
        assertEquals(listOf(13, 0, 1, 2), keys[0].directionKeys)
        assertEquals(GameKey.Mode.Tap, keys[1].mode, "Missing fields get the Mac's defaults")
        near(0.08, keys[1].radius)
        near(10.0, keys[1].rate)
        assertEquals(56, keys[1].walkKey)
        near(120.0, keys[1].duration)
        assertEquals(GameKey.Kind.Look, keys[2].kind)
        assertEquals(2, keys[4].steps.size)
        // Imported, it plays here with the same Mac key codes.
        val rig = Rig()
        assertEquals(2, rig.game.importProfiles(mac))
        assertEquals("PUBG / TPP", rig.game.profileName)
        assertTrue(rig.game.keyPressed(49))
        assertEquals(listOf("tdown:1"), rig.take(), "Space taps with the first finger after the joystick's")
        assertEquals(0, rig.game.importProfiles("{\"hello\":1}"))
        assertNotNull(rig.game.message)
    }

    @Test
    fun pcKeysBecomeMacKeyCodes() {
        assertEquals(13, GameKeyCodes.mac(KeyEvent.VK_W))
        assertEquals(0, GameKeyCodes.mac(KeyEvent.VK_A))
        assertEquals(49, GameKeyCodes.mac(KeyEvent.VK_SPACE))
        assertEquals(126, GameKeyCodes.mac(KeyEvent.VK_UP))
        assertEquals(50, GameKeyCodes.mac(KeyEvent.VK_BACK_QUOTE))
        assertEquals(53, GameKeyCodes.mac(KeyEvent.VK_ESCAPE))
        assertEquals(36, GameKeyCodes.mac(KeyEvent.VK_ENTER))
        assertEquals(76, GameKeyCodes.mac(KeyEvent.VK_ENTER, KeyEvent.KEY_LOCATION_NUMPAD))
        assertEquals(56, GameKeyCodes.mac(KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_LEFT))
        assertEquals(60, GameKeyCodes.mac(KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_RIGHT))
        assertEquals(56, GameKeyEngine.normalized(60), "Right Shift counts as the left one")
        assertEquals(59, GameKeyCodes.mac(KeyEvent.VK_CONTROL))
        assertEquals(58, GameKeyCodes.mac(KeyEvent.VK_ALT))
        assertEquals(122, GameKeyCodes.mac(KeyEvent.VK_F1))
        assertNull(GameKeyCodes.mac(KeyEvent.VK_WINDOWS))
        assertEquals("W", GameKeyCodes.label(13))
        assertEquals("Space", GameKeyCodes.label(49))
        assertEquals("Shift", GameKeyCodes.label(60))
        assertEquals("F1", GameKeyCodes.label(122))
        // Every key mapped one way maps back.
        for (code in 0..127) {
            val vk = MacKeys.vk(code) ?: continue
            assertEquals(vk, MacKeys.vk(GameKeyCodes.mac(vk)!!), "Mac key $code")
        }
    }

    @Test
    fun profilesAreKeptPerPhoneInTheDataFolder() {
        val dir = Files.createTempDirectory("dysk-gk").toFile()
        val first = Rig(dir = dir)
        first.game.placeJoystick(0.2, 0.7)
        first.game.addProfile("Genshin")
        first.game.pendTap(0.8, 0.8)
        assertTrue(first.game.assign(49))
        first.game.chooseLookSpeed(1.7)
        val again = GameKeysController("p1", GameKeyStore(dir))
        assertEquals(listOf("Default", "Genshin"), again.profiles.map { it.name })
        assertEquals(1, again.current)
        assertEquals(listOf(GameKey.Kind.Tap), again.keys.map { it.kind })
        assertEquals(49, again.keys[0].keyCode)
        assertEquals(1.7, GameKeyStore(dir).lookSpeed)
        // What's on disk is the Mac's JSON.
        val saved = java.io.File(dir, "gamekeys-p1.json").readText()
        val profiles = GameKeysJson.decode(kotlinx.serialization.json.Json.parseToJsonElement(saved).let { (it as kotlinx.serialization.json.JsonObject)["profiles"].toString() })
        assertEquals(2, profiles.size)
        assertEquals(listOf("Default"), GameKeysController("p2", GameKeyStore(dir)).profiles.map { it.name }, "Another phone has its own")
    }

    // The engine

    @Test
    fun fingersComeFromAPoolOfEightJoysticksFirst() {
        val taps = (0 until 8).map { GameKey(kind = GameKey.Kind.Tap, keyCode = 18 + it, label = "$it", x = 0.1 * it, y = 0.5) }
        val stick = GameKey(kind = GameKey.Kind.Joystick, x = 0.2, y = 0.7)
        val engine = GameKeyEngine(taps + stick + GameKey(kind = GameKey.Kind.Look, x = 0.6, y = 0.4) + GameKey(kind = GameKey.Kind.Fire, x = 0.9, y = 0.5), 0.5)
        assertEquals(0, engine.fingers[stick.id], "The joystick gets finger 0")
        assertEquals((1..7).toList(), taps.take(7).map { engine.fingers[it.id] })
        assertNull(engine.fingers[taps[7].id], "The ninth key has no finger")
        assertFalse(engine.handles(taps[7].keyCode))
        // The click spot is 8 and the mouse 9, outside the pool.
        val look = GameKeyEngine(listOf(GameKey(kind = GameKey.Kind.Look, x = 0.6, y = 0.4), GameKey(kind = GameKey.Kind.Fire, x = 0.9, y = 0.5)), 0.5)
        assertEquals("9", look.lookBegin().single().key)
        assertEquals("8", look.fire(true).single().key)
        assertEquals(setOf("tup:8", "tup:9"), look.releaseAll().map { "${it.kind}:${it.key}" }.toSet())

        // The controller refuses a ninth and says why; deleting one frees a finger.
        val rig = Rig()
        repeat(8) { rig.game.pendTap(0.1 * it, 0.5); rig.game.assign(18 + it) }
        assertEquals(0, rig.game.freeFingers)
        rig.game.edit(true)
        rig.game.place(GameKey.Kind.Tap, 0.5, 0.5)
        assertNull(rig.game.pending)
        assertTrue(rig.game.message!!.contains("No free finger"))
        rig.game.remove(rig.game.keys[3].id)
        assertEquals(1, rig.game.freeFingers)
        // Pressing the same key on a new spot moves it instead of taking another finger.
        rig.game.pendTap(0.9, 0.9)
        rig.game.assign(18)
        assertEquals(1, rig.game.freeFingers)
        assertEquals(0.9, rig.game.keys.first { it.keyCode == 18 }.x)
    }

    @Test
    fun joystickVectors() {
        val c = PhonePoint(0.2, 0.7)
        val aspect = 0.45
        val up = GameKeyEngine.stickPoint(c, 0.1, aspect, up = true, left = false, down = false, right = false)!!
        near(0.2, up.x); near(0.7 - 0.1 * aspect, up.y, "Up reaches as far as sideways, in phone widths")
        val right = GameKeyEngine.stickPoint(c, 0.1, aspect, up = false, left = false, down = false, right = true)!!
        near(0.3, right.x); near(0.7, right.y)
        val diagonal = GameKeyEngine.stickPoint(c, 0.1, aspect, up = true, left = true, down = false, right = false)!!
        near(0.1, hypot(diagonal.x - c.x, (diagonal.y - c.y) / aspect), "A diagonal reaches as far as a straight move")
        assertNull(GameKeyEngine.stickPoint(c, 0.1, aspect, up = true, left = false, down = true, right = false), "Opposite keys cancel")
        val half = GameKeyEngine.stickPoint(c, 0.1, aspect, up = false, left = true, down = false, right = false, scale = 0.5)!!
        near(0.15, half.x)

        // In play: one finger down at the centre, then dragged around it; walking halves the reach while Ctrl is held.
        val stick = GameKey(kind = GameKey.Kind.Joystick, x = 0.2, y = 0.7, radius = 0.1, walk = GameKey.Walk.HalfWithKey, walkKey = 59)
        val rig = Rig(listOf(stick))
        rig.game.setAspect(aspect)
        rig.time = 100.0
        rig.game.keyPressed(13)
        assertEquals(listOf("tdown"), rig.sent.map { it.kind }, "The tdown goes alone")
        near(0.2, rig.sent[0].x!!.toDouble()); near(0.7, rig.sent[0].y!!.toDouble())
        rig.sent.clear()
        rig.time = 110.0
        rig.game.tick()
        assertTrue(rig.sent.isEmpty(), "Its first move waits ${GameKeyEngine.SETTLE_MS} ms")
        rig.time = 121.0
        rig.game.tick()
        assertEquals("tmove", rig.sent.single().kind)
        near(0.7 - 0.1 * aspect, rig.sent.single().y!!.toDouble(), "W pushes up")
        rig.sent.clear()
        rig.game.keyPressed(2)
        val diag = rig.sent.single()
        near(0.1, hypot(diag.x!! - 0.2, (diag.y!! - 0.7) / aspect), "W+D: full reach diagonally")
        rig.sent.clear()
        rig.game.keyPressed(59)
        val walking = rig.sent.single()
        near(0.05, hypot(walking.x!! - 0.2, (walking.y!! - 0.7) / aspect), "Ctrl held: half reach")
        rig.sent.clear()
        rig.game.keyReleased(13); rig.game.keyReleased(2)
        assertEquals(listOf("tmove", "tup"), rig.sent.map { it.kind })
    }

    @Test
    fun tapsSkillsSwipesAndCombos() {
        val rig = Rig(everyKind)
        rig.time = 1000.0
        // Toggle-free tap: down while held.
        rig.game.keyPressed(49)
        rig.time = 1010.0
        rig.game.keyReleased(49)
        assertEquals(listOf("tdown", "tup"), rig.sent.map { it.kind })
        rig.sent.clear()
        // A quick skill press taps the button's middle.
        rig.game.mouse(PhonePoint(0.9, 0.2))
        rig.sent.clear()
        rig.time = 2000.0
        rig.game.keyPressed(12)
        rig.time = 2100.0
        rig.game.keyReleased(12)
        val skill = rig.sent.map { it.kind }
        assertEquals(listOf("tdown", "tmove", "tup"), skill)
        near(0.75, rig.sent.last().x!!.toDouble(), "Quick cast lifts at the button")
        rig.sent.clear()
        // A swipe plays on ticks: down, moves about every 16 ms, up at its end.
        rig.time = 3000.0
        rig.game.keyPressed(3)
        var t = 3000.0
        while (t < 3200) { t += 8; rig.time = t; rig.game.tick() }
        val swipe = rig.sent.map { it.kind }
        assertEquals("tdown", swipe.first())
        assertEquals("tup", swipe.last())
        near(0.4, rig.sent.last().y!!.toDouble())
        assertTrue(swipe.count { it == "tmove" } >= 8)
        rig.sent.clear()
        // A combo: tap, wait, swipe, on one finger.
        rig.time = 4000.0
        rig.game.keyPressed(14)
        t = 4000.0
        while (t < 4500) { t += 8; rig.time = t; rig.game.tick() }
        assertEquals(2, rig.sent.count { it.kind == "tdown" })
        assertEquals(2, rig.sent.count { it.kind == "tup" })
        assertEquals(1, rig.sent.map { it.key }.distinct().size)
    }

    // Looking with the mouse

    @Test
    fun mouseLookIsScaledTurnedAndWraps() {
        val d = LookMath.phoneDelta(140.0, 0.0, 0, aspect = 0.5)
        near(0.1, d.x); near(0.0, d.y)
        val down = LookMath.phoneDelta(0.0, 140.0, 0, aspect = 0.5)
        near(0.05, down.y, "Up and down are shares of the phone's height")
        val turned = LookMath.phoneDelta(140.0, 0.0, 90, aspect = 0.5)
        near(0.0, turned.x); near(-0.05, turned.y, "Picture turned right: moving right is the phone's up")
        val back = LookMath.phoneDelta(140.0, 0.0, 180, aspect = 0.5, speed = 1.7)
        near(-0.17, back.x)
        val left = LookMath.phoneDelta(0.0, 140.0, 270, aspect = 0.5)
        near(-0.1, left.x)

        val rig = Rig(everyKind)
        rig.game.toggleLook()
        assertTrue(rig.game.looking)
        assertEquals(listOf("tdown:9"), rig.take(), "The look finger goes down at the area's middle")
        rig.time = 100.0
        rig.game.lookPixels(140.0, 0.0, 0)
        val move = rig.sent.single()
        near(0.75, move.x!!.toDouble()); near(0.4, move.y!!.toDouble())
        rig.sent.clear()
        // At the edge of the area it lifts and starts again from the middle; the move waits for the new tdown to land.
        rig.time = 200.0
        rig.game.lookPixels(400.0, 0.0, 0)
        assertEquals(listOf("tup", "tdown"), rig.sent.map { it.kind })
        near(0.65, rig.sent[1].x!!.toDouble())
        rig.sent.clear()
        rig.time = 221.0
        rig.game.tick()
        assertEquals(listOf("tmove"), rig.sent.map { it.kind })
        rig.sent.clear()
        // The mouse button presses the click spot while looking; Esc or ` stops.
        rig.game.fire(true)
        assertEquals(listOf("tdown:8"), rig.take())
        rig.game.toggleLook()
        assertEquals(listOf("tup:9", "tup:8"), rig.take())
        assertFalse(rig.game.looking)
    }

    @Test
    fun thePointerIsPutBackOnlyWhenItStrays() {
        val warps = mutableListOf<Point>()
        val lock = PointerRecentre({ x, y -> warps += Point(x, y) }, margin = 80)
        lock.begin(Point(500, 400))
        assertEquals(listOf(Point(500, 400)), warps, "Looking starts with the pointer in the middle")
        assertEquals(10 to -5, lock.sample(Point(510, 395)))
        assertNull(lock.sample(Point(510, 395)), "No move, nothing to send")
        assertEquals(60 to 0, lock.sample(Point(570, 395)))
        assertEquals(1, warps.size, "Still within the margin")
        assertEquals(40 to 5, lock.sample(Point(610, 400)))
        assertEquals(Point(500, 400), warps.last(), "Past the margin it goes back to the middle")
        // The move after the warp counts from the middle, so nothing is counted twice.
        assertEquals(-3 to 2, lock.sample(Point(497, 402)))
        assertNull(lock.sample(null), "No pointer reading (Wayland) gives nothing")
        lock.end()
        assertNull(lock.sample(Point(900, 900)))
        assertFalse(lock.active)
    }

    // Timing and letting go

    @Test
    fun heldFingersAreSentAgainEveryOneAndAHalfSeconds() {
        val rig = Rig(everyKind)
        rig.time = 0.0
        rig.game.keyPressed(49)
        assertEquals(listOf("tdown:1"), rig.take())
        rig.time = 1499.0
        rig.game.tick()
        assertTrue(rig.sent.isEmpty())
        rig.time = 1500.0
        rig.game.tick()
        assertEquals(listOf("tmove:1"), rig.take(), "The heartbeat")
        rig.game.toggleLook()
        rig.take()
        var beats = 0
        var last = 1500.0
        var t = 1500.0
        while (t < 9000) {
            t += 8
            rig.time = t
            rig.game.tick()
            if (rig.sent.isNotEmpty()) {
                assertEquals(setOf("1", "9"), rig.sent.map { it.key }.toSet(), "Every held finger, the look finger too")
                assertTrue(t - last <= GameKeysController.HEARTBEAT_MS + 8, "Never longer than 1.5 s apart")
                last = t
                beats++
                rig.sent.clear()
            }
        }
        assertTrue(beats >= 4)
        assertTrue(GameKeysController.HEARTBEAT_MS * 3 <= 5_000, "The phone lifts after 5 s without news, three beats later")
        rig.game.release()
        rig.take()
        rig.time = 20_000.0
        rig.game.tick()
        assertTrue(rig.sent.isEmpty(), "Nothing held, no heartbeat: the phone lifts what it had")
    }

    @Test
    fun focusLossAndLeavingLiftEveryFinger() {
        val rig = Rig(everyKind)
        rig.game.keyPressed(13)
        rig.game.keyPressed(49)
        rig.game.mouseTouch("tdown", PhonePoint(0.5, 0.5))
        rig.time = 30.0
        rig.game.tick()
        assertEquals(setOf(0, 1, 9), rig.game.heldFingers.keys)
        rig.sent.clear()
        // The window lost focus, the tab was left, play mode ended, or the phone went: all the same.
        rig.game.release()
        assertEquals(setOf("tup:0", "tup:1", "tup:9"), rig.take().toSet())
        assertTrue(rig.game.heldFingers.isEmpty())
        // The keys' own releases that come later (or never) change nothing.
        rig.game.keyReleased(13)
        rig.game.keyReleased(49)
        assertTrue(rig.sent.isEmpty())
        // Pressing again works as new.
        rig.game.keyPressed(49)
        assertEquals(listOf("tdown:1"), rig.take())
        // Editing and turning game keys off lift too.
        rig.game.edit(true)
        assertEquals(listOf("tup:1"), rig.take())
        assertFalse(rig.game.keyPressed(49), "Keys don't play while editing")
        rig.game.edit(false)
        rig.game.keyPressed(49)
        rig.take()
        rig.game.enable(false)
        assertEquals(listOf("tup:1"), rig.take())
        assertFalse(rig.game.keyPressed(49))
    }

    @Test
    fun x11KeyRepeatDoesNotLiftAHeldKey() {
        val rig = Rig(everyKind)
        rig.game.debounceReleases = true
        rig.time = 0.0
        rig.game.keyPressed(49)
        assertEquals(listOf("tdown:1"), rig.take())
        // X11's autorepeat: a release and a press of the same key at the same moment, again and again.
        for (i in 1..10) {
            rig.time = i * 33.0
            rig.game.keyReleased(49)
            rig.game.keyPressed(49)
            rig.game.tick()
        }
        assertTrue(rig.sent.none { it.kind == "tup" }, "The finger stays down")
        // A real release goes out a moment later.
        rig.game.keyReleased(49)
        rig.time += 10
        rig.game.tick()
        assertTrue(rig.sent.none { it.kind == "tup" })
        rig.time += 20
        rig.game.tick()
        assertEquals(listOf("tup:1"), rig.take())
        // A release waiting when focus goes is dropped with everything else.
        rig.game.keyPressed(49)
        rig.game.keyReleased(49)
        rig.take()
        rig.game.release()
        assertEquals(listOf("tup:1"), rig.take())
        rig.time += 100
        rig.game.tick()
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun editingPlacesKeysAsOnTheMac() {
        val rig = Rig()
        rig.game.edit(true)
        rig.game.place(GameKey.Kind.Skill, 0.7, 0.7)
        assertTrue(rig.game.assign(12))
        assertEquals(GameKey.Kind.Skill, rig.game.keys.single().kind)
        near(0.07, rig.game.keys.single().radius)
        rig.game.pendingSwipe = PhonePoint(0.5, 0.5) to PhonePoint(0.5, 0.3)
        rig.game.assign(3)
        assertEquals(GameKey.Kind.Swipe, rig.game.keys.last().kind)
        val stick = rig.game.also { it.placeJoystick(0.2, 0.7) }.keys.last()
        rig.game.capture = GameKeysController.Capture.StickKeys(stick.id, emptyList(), emptyList())
        listOf(34, 38, 40, 37).forEach { rig.game.assign(it) }
        val custom = rig.game.keys.first { it.id == stick.id }
        assertEquals(GameKey.KeySet.Custom, custom.keySet)
        assertEquals(listOf(34, 38, 40, 37), custom.directionKeys)
        assertEquals("IJKL", custom.label)
        rig.game.placeJoystick(0.3, 0.7)
        assertEquals(GameKey.KeySet.Wasd, rig.game.keys.last().keySet, "A second joystick takes the first key set not in use")
        rig.game.pendTap(0.6, 0.6)
        rig.game.assign(13)
        assertTrue(rig.game.conflicts(rig.game.keys.last()), "W is the joystick's too")
        rig.game.recordCombo(rig.game.keys.last().id)
        rig.game.recordStep(GameKey.Step(kind = GameKey.Step.Kind.Tap, x = 0.1, y = 0.1))
        rig.game.recordStep(GameKey.Step(kind = GameKey.Step.Kind.Tap, x = 0.2, y = 0.2))
        assertEquals(listOf(GameKey.Step.Kind.Tap, GameKey.Step.Kind.Wait, GameKey.Step.Kind.Tap), rig.game.keys.last().steps.map { it.kind })
        rig.game.cancelEditStep()
        assertNull(rig.game.capture)
        rig.game.resize(stick.id, 1.0)
        near(0.25, rig.game.keys.first { it.id == stick.id }.radius, "Sizes stay within the Mac's range")
        assertTrue(rig.sent.isEmpty(), "Editing sends nothing to the phone")
    }
}
