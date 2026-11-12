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
        assertEquals("Scan 2026-11-10 2130", scans.newName(time))
        scans.save(scans.newName(time), { it.writeText("%PDF") }, 1, "")
        assertEquals("Scan 2026-11-10 2130 (2)", scans.newName(time))
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

    @Test
    fun fileNamesUseDateAndTime() {
        assertEquals("Scan 2026-01-05 0907", DocScans.baseName(LocalDateTime.of(2026, 1, 5, 9, 7)))
        val taken = setOf("Scan A", "Scan A (2)")
        assertEquals("Scan A (3)", DocScans.uniqueName("Scan A") { it in taken })
        assertEquals("Scan B", DocScans.uniqueName("Scan B") { it in taken })
    }

    @Test
    fun pageImagesFollowRenameAndDelete() {
        val saved = scans.save("Letter", { it.writeText("%PDF") }, 2, "")
        scans.saveImage(saved, 2) { it.writeText("b") }
        scans.saveImage(saved, 1) { it.writeText("a") }
        assertEquals(listOf("Letter page 1.jpg", "Letter page 2.jpg"), scans.images(saved).map { it.name })
        val renamed = scans.rename(saved, "Letter 2")!!
        assertEquals(listOf("Letter 2 page 1.jpg", "Letter 2 page 2.jpg"), scans.images(renamed).map { it.name })
        scans.delete(renamed)
        assertTrue(folder.listFiles().isNullOrEmpty())
    }
}
