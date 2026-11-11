package id.phonelink.android.ui

import id.phonelink.android.core.Transfer
import id.phonelink.android.core.TransferStatus
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class FileHistoryTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private fun at(date: String, hour: Int) = LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun t(id: String, time: Long) = Transfer(id, "mac", "$id.jpg", 10, 10, false, TransferStatus.Done, time = time)

    @Test
    fun groupsByDayNewestFirst() {
        val today = LocalDate.parse("2026-11-10")
        val groups = historyGroups(
            listOf(t("old", 0), t("a", at("2026-11-10", 8)), t("b", at("2026-11-10", 15)), t("c", at("2026-11-09", 9)), t("d", at("2026-11-01", 9))),
            today, zone,
        )
        assertEquals(listOf("Today", "Yesterday"), groups.take(2).map { it.first })
        assertEquals(listOf("b", "a"), groups[0].second.map { it.id })
        assertEquals("Earlier", groups.last().first)
        assertEquals(4, groups.size)
    }

    @Test
    fun formatsSizes() {
        assertEquals("512 B", formatSize(512))
        assertEquals("12 KB", formatSize(12_000))
        assertEquals("3.4 MB", formatSize(3_400_000))
        assertEquals("1.2 GB", formatSize(1_200_000_000))
    }
}
