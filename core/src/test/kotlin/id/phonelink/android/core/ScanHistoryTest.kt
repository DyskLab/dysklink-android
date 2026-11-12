package id.phonelink.android.core

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class ScanHistoryTest {
    private fun record(n: Int) = ScanRecord("code $n", "QR", n.toLong())

    @Test
    fun keepsNewestFirstAndTrims() {
        var history = emptyList<ScanRecord>()
        repeat(205) { history = ScanHistory.add(history, record(it)) }
        assertEquals(ScanHistory.MAX, history.size)
        assertEquals("code 204", history.first().text)
        assertEquals("code 5", history.last().text)
        assertEquals(listOf(record(2)), ScanHistory.add(listOf(record(1)), record(2), max = 1))
        assertEquals(emptyList(), ScanHistory.trim(listOf(record(1)), 0))
    }

    @Test
    fun savesAndLoads() {
        val history = listOf(record(2), record(1))
        assertEquals(history, ScanHistory.decode(ScanHistory.encode(history)))
        assertEquals(emptyList(), ScanHistory.decode(null))
        assertEquals(emptyList(), ScanHistory.decode("not json"))
        assertEquals(ScanHistory.MAX, ScanHistory.decode(ScanHistory.encode(List(300) { record(it) }), ).size)
    }

    @Test
    fun exports() {
        val history = listOf(ScanRecord("a,\"b\"\nc", "Code 128", 60_000), ScanRecord("plain", "QR", 0))
        assertEquals(
            "Time,Type,Value\r\n1970-01-01 00:00:00,QR,plain\r\n1970-01-01 00:01:00,Code 128,\"a,\"\"b\"\"\nc\"\r\n",
            ScanHistory.toCsv(history, ZoneOffset.UTC),
        )
        assertEquals("plain\na,\"b\"\nc", ScanHistory.toText(history))
    }
}
