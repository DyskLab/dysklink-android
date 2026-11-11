package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WakeOnLanTest {
    @Test
    fun magicPacketIsSixFfThenTheAddressSixteenTimes() {
        val packet = WakeOnLan.packet("68:5e:dd:75:79:2c")!!
        assertEquals(102, packet.size)
        assertContentEquals(ByteArray(6) { 0xFF.toByte() }, packet.copyOfRange(0, 6))
        val mac = byteArrayOf(0x68, 0x5e, 0xdd.toByte(), 0x75, 0x79, 0x2c)
        for (i in 0 until 16) assertContentEquals(mac, packet.copyOfRange(6 + i * 6, 12 + i * 6))
        assertEquals(102, WakeOnLan.packet("68-5E-DD-75-79-2C")?.size)
        assertNull(WakeOnLan.packet("02:00:00"))
    }

    @Test
    fun oldRecordsAndInfoStillRead() {
        val record = protocolJson.decodeFromString(PairedRecord.serializer(), """{"name":"Mac","platform":"macos","model":"M","pairedAt":1}""")
        assertEquals(emptyList(), record.wakeMacs)
        val info = protocolJson.decodeFromString(DeviceInfo.serializer(), """{"wakeMacs":["68:5e:dd:75:79:2c"],"wakeBroadcast":"192.168.1.255","wakeEnabled":true}""")
        assertEquals("192.168.1.255", info.wakeBroadcast)
        assertEquals(true, info.wakeEnabled)
    }
}
