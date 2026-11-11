package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetToolsTest {
    @Test
    fun buildsTheSubnet() {
        val hosts = NetTools.subnet("192.168.1.23")
        assertEquals(253, hosts.size)
        assertFalse("192.168.1.23" in hosts)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals(emptyList(), NetTools.subnet("fe80::1"))
        assertEquals(emptyList(), NetTools.subnet("192.168.1.300"))
    }

    @Test
    fun sortsAddressesByNumber() {
        val sorted = listOf("10.0.0.10", "10.0.0.2", "10.0.0.100").sortedBy { NetTools.ipKey(it) }
        assertEquals(listOf("10.0.0.2", "10.0.0.10", "10.0.0.100"), sorted)
    }

    @Test
    fun guessesDevices() {
        assertEquals("Router", NetTools.guess(setOf(80, 53), isRouter = true, isSelf = false).label)
        assertEquals("Mac with DyskLink", NetTools.guess(setOf(445, PhoneLinkProtocol.PORT), false, false).label)
        assertEquals("iPhone or iPad", NetTools.guess(setOf(62078), false, false).label)
        assertEquals("Printer", NetTools.guess(setOf(80, 9100), false, false).label)
        assertEquals("Chromecast or TV", NetTools.guess(setOf(8009), false, false).label)
        assertEquals("Device", NetTools.guess(emptySet(), false, false).label)
        assertEquals("This phone", NetTools.guess(setOf(80), true, true).label)
    }

    @Test
    fun readsPingOutput() {
        assertEquals(NetTools.PingLine(1, 12.3), NetTools.parsePingLine("64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=12.3 ms"))
        assertEquals(0.5, NetTools.parsePingLine("64 bytes from 192.168.1.1: icmp_seq=4 ttl=64 time<0.5 ms")?.millis)
        assertNull(NetTools.parsePingLine("PING google.com (142.250.4.100) 56(84) bytes of data."))
        assertNull(NetTools.parsePingLine("5 packets transmitted, 5 received, 0% packet loss, time 4005ms"))
        val summary = NetTools.summarize(5, listOf(10.0, 20.0, 30.0))
        assertEquals(40, summary.lossPercent)
        assertEquals(20.0, summary.avg)
        assertEquals(100, NetTools.summarize(5, emptyList()).lossPercent)
    }

    @Test
    fun checksHostsAndSpeeds() {
        assertTrue(NetTools.isValidHost("google.com"))
        assertTrue(NetTools.isValidHost(" 192.168.1.1 "))
        assertFalse(NetTools.isValidHost("-c 100 x"))
        assertFalse(NetTools.isValidHost("a;rm -rf"))
        assertFalse(NetTools.isValidHost(""))
        assertEquals(80.0, NetTools.mbps(10_000_000, 1_000))
        assertEquals("80.0", NetTools.formatMbps(80.0))
        assertEquals("250", NetTools.formatMbps(250.4))
        assertEquals("3.50", NetTools.formatMbps(3.5))
        assertEquals(20L, NetTools.median(listOf(30, 10, 20)))
        assertEquals(15L, NetTools.median(listOf(10, 20)))
    }
}
