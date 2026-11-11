package id.phonelink.android.core

import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocScansTest {
    private val folder = Files.createTempDirectory("scans").toFile()
    private val scans = DocScans(folder)

    @AfterTest
    fun clean() { folder.deleteRecursively() }

    @Test
    fun namesAreUniqueAndSafe() {
        val time = LocalDateTime.of(2026, 11, 10, 21, 30)
        assertEquals("Scan 10 Nov 2026 21.30", scans.newName(time))
        scans.save(scans.newName(time), { it.writeText("%PDF") }, 1, "")
        assertEquals("Scan 10 Nov 2026 21.30 (2)", scans.newName(time))
        assertEquals("a b c", DocScans.clean("a/b:c"))
        assertEquals("secret", DocScans.clean("../secret"))
    }

    @Test
    fun saveRenameDelete() {
        val saved = scans.save("Receipt", { it.writeText("%PDF") }, 3, "Total 50.000")
        assertEquals(3, scans.list().single().pages)
        assertEquals("Total 50.000", saved.readText())
        val renamed = scans.rename(saved, "Receipt November")
        assertEquals("Receipt November", renamed?.name)
        assertEquals("Total 50.000", renamed?.readText())
        assertNull(scans.rename(renamed!!, "  "))
        scans.delete(renamed)
        assertTrue(scans.list().isEmpty() && folder.listFiles().isNullOrEmpty())
    }

    @Test
    fun pagesJoin() {
        assertEquals("", DocScans.joinPages(listOf(" ", "")))
        assertEquals("one", DocScans.joinPages(listOf(" one ")))
        assertEquals("— Page 1 —\nA\n\n— Page 2 —\n(no text)", DocScans.joinPages(listOf("A", "")))
    }
}
