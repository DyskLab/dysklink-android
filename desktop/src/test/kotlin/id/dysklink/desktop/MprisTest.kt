@file:Suppress("FunctionName", "UNCHECKED_CAST")

package id.dysklink.desktop

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.bin.EmbeddedDBusDaemon
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.connections.transports.TransportBuilder
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

/** MPRIS players on dbus-java's own bus: what's read, which one shows, and that commands reach it. */
class MprisTest {
    private var daemon: EmbeddedDBusDaemon? = null
    private val connections = mutableListOf<DBusConnection>()

    @AfterTest
    fun stop() {
        connections.forEach { runCatching { it.close() } }
        runCatching { daemon?.close() }
    }

    private fun startBus(): String? {
        val listen = runCatching { TransportBuilder.createDynamicSession("UNIX", true) }.getOrNull() ?: return null
        daemon = runCatching { EmbeddedDBusDaemon(listen).apply { startInBackgroundAndWait(5000) } }.getOrNull() ?: return null
        return listen.replace(",listen=true", "")
    }

    private fun connect(address: String) = DBusConnectionBuilder.forAddress(address).withShared(false).build().also { connections += it }

    private class FakePlayer(val identity: String, @Volatile var status: String, val title: String, val artist: List<String>) : MprisPlayer, Properties {
        val calls = CopyOnWriteArrayList<String>()
        override fun PlayPause() { calls += "PlayPause"; status = if (status == "Playing") "Paused" else "Playing" }
        override fun Next() { calls += "Next" }
        override fun Previous() { calls += "Previous" }
        override fun SetPosition(trackId: DBusPath, position: Long) { calls += "SetPosition ${trackId.path} $position" }
        override fun getObjectPath() = MprisParse.PATH

        private fun metadata(): Map<String, Variant<*>> = mapOf(
            "xesam:title" to Variant(title),
            "xesam:artist" to Variant(artist, "as"),
            "mpris:length" to Variant(213_000_000L),
            "mpris:trackid" to Variant(DBusPath("/track/1")),
            "mpris:artUrl" to Variant("file:///tmp/cover.png"),
        )

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
            if (interfaceName != MprisParse.PLAYER) emptyMap() else mapOf(
                "PlaybackStatus" to Variant(status),
                "Metadata" to Variant(metadata(), "a{sv}"),
                "Position" to Variant(61_000_000L),
                "CanSeek" to Variant(true),
                "CanControl" to Variant(true),
            )

        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = when {
            interfaceName == MprisParse.ROOT && propertyName == "Identity" -> identity
            else -> GetAll(interfaceName)[propertyName]?.value ?: throw UnknownProperty("No $propertyName")
        } as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {}
    }

    private fun player(address: String, name: String, fake: FakePlayer): FakePlayer {
        val c = connect(address)
        c.requestBusName(name)
        c.exportObject(MprisParse.PATH, fake)
        return fake
    }

    @Test
    fun readsPlayersAndPrefersTheOneThatPlays() {
        val address = startBus() ?: return
        val paused = player(address, "org.mpris.MediaPlayer2.vlc", FakePlayer("VLC media player", "Paused", "Old song", listOf("Someone")))
        val playing = player(address, "org.mpris.MediaPlayer2.spotify", FakePlayer("Spotify", "Playing", "Idol", listOf("YOASOBI")))
        player(address, "org.mpris.MediaPlayer2.playerctld", FakePlayer("playerctld", "Playing", "Idol", listOf("YOASOBI")))
        val source = MprisSource({ connect(address) }, clock = { 1_000 })
        source.poll()

        assertEquals(listOf("Spotify", "VLC media player"), source.players.value.map { it.name }.sorted())
        val track = assertNotNull(source.track.value)
        assertEquals("Idol", track.title)
        assertEquals("YOASOBI", track.artist)
        assertEquals(213_000L, track.durationMs)
        assertEquals(61_000L, track.positionMs)
        assertEquals("/track/1", track.trackId)
        assertEquals("file:///tmp/cover.png", track.artUrl)
        assertTrue(track.isPlaying && track.canSeek)
        assertFalse(track.browser)

        source.command(MediaCommand.Next)
        source.seek(30_000)
        Thread.sleep(200)
        assertEquals(listOf("Next", "SetPosition /track/1 30000000"), playing.calls)
        assertTrue(paused.calls.isEmpty())

        // The one chosen in the settings shows even while paused.
        source.preferred = "org.mpris.MediaPlayer2.vlc"
        source.poll()
        assertEquals("Old song", source.track.value?.title)
        source.stop()
    }

    @Test
    fun choosesAsTheMacDoes() {
        fun s(name: String, status: String?) = MprisSnapshot(name, null, status, emptyMap(), null)
        val a = s("org.mpris.MediaPlayer2.a", "Playing")
        val b = s("org.mpris.MediaPlayer2.b", "Playing")
        val c = s("org.mpris.MediaPlayer2.c", "Paused")
        val d = s("org.mpris.MediaPlayer2.d", "Stopped")
        // Two play: the one shown before stays.
        assertEquals(b, MprisParse.choose(listOf(a, b, c), null, b.busName))
        assertEquals(a, MprisParse.choose(listOf(a, b, c), null, null))
        assertEquals(c, MprisParse.choose(listOf(c, d), null, null))
        assertNull(MprisParse.choose(listOf(d), null, null))
        assertEquals(c, MprisParse.choose(listOf(a, c), "org.mpris.MediaPlayer2.c", null))
        // Firefox names its bus per instance.
        val ff = s("org.mpris.MediaPlayer2.firefox.instance_1_42", "Playing")
        assertEquals(ff, MprisParse.choose(listOf(a, ff), "org.mpris.MediaPlayer2.firefox", null))
        assertTrue(MprisParse.isBrowser(ff.busName, null))
        assertEquals("Firefox", MprisParse.displayName(ff.busName, null))
        assertFalse(MprisParse.isPlayerName("org.mpris.MediaPlayer2.playerctld"))
        assertNull(MprisParse.track(d.copy(metadata = mapOf("xesam:title" to "x")), 0))
    }
}
