package id.phonelink.android.service

import id.phonelink.android.core.DeviceInfo
import id.phonelink.android.core.NotchState
import id.phonelink.android.core.Peer
import id.phonelink.android.core.protocolJson
import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetsTest {
    @Test
    fun batteryRowsPutThisPhoneThenConnectedMacs() {
        val own = Widgets.BatteryRow("This phone", 55, false)
        val peers = listOf(
            Peer("p", "Y17", "android", "", isPaired = true, isConnected = true, info = DeviceInfo(batteryPercent = 40)),
            Peer("m2", "iMac", "macos", "", isPaired = true, isConnected = false, info = DeviceInfo(batteryPercent = 10)),
            Peer("m", "MacBook", "macos", "", isPaired = true, isConnected = true, info = DeviceInfo(batteryPercent = 80, isCharging = true)),
            Peer("x", "Stranger", "android", "", isPaired = false, isConnected = true, info = null),
        )
        val rows = Widgets.batteryRows(own, peers)
        assertEquals(listOf("This phone", "MacBook", "Y17", "iMac"), rows.map { it.name })
        assertEquals(Widgets.BatteryRow("MacBook", 80, true), rows[1])
        // An offline device has no current battery.
        assertEquals(null, rows[3].percent)
    }

    @Test
    fun notchStateReadsMusicAndOlderMacs() {
        val withMusic = protocolJson.decodeFromString(NotchState.serializer(), """{"music":{"title":"Lagu","artist":"Band","playing":true}}""")
        assertEquals("Lagu", withMusic.music?.title)
        val old = protocolJson.decodeFromString(NotchState.serializer(), """{"todos":[]}""")
        assertEquals(null, old.music)
        assertEquals(null, old.note)
    }
}
