package id.phonelink.android.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PartialNameTest {
    @Test
    fun eachTransferHasItsOwnTempFile() {
        val first = DownloadSinks.partialName("3f2a-11")
        val second = DownloadSinks.partialName("3f2a-12")
        assertNotEquals(first, second, "Two files of the same name don't share a temp file")
        assertEquals(".dysklink-3f2a-11.part", first)
        assertTrue(first.startsWith("."), "Hidden while it's written")
    }

    @Test
    fun theIdCantLeaveTheFolder() {
        val name = DownloadSinks.partialName("../../evil/x")
        assertFalse('/' in name)
        assertFalse(".." in name.removePrefix("."))
        assertTrue(DownloadSinks.partialName("a".repeat(500)).length < 60)
    }
}
