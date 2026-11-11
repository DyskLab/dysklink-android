package id.phonelink.android.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneCleanerTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val root: File = Files.createTempDirectory("cleaner").toFile()

    private fun file(path: String, size: Long, ageDays: Long = 1): File = File(root, path).apply {
        parentFile.mkdirs()
        RandomAccessFile(this, "rw").use { it.setLength(size) }
        setLastModified(now - ageDays * day)
    }

    @AfterTest
    fun clean() { root.deleteRecursively() }

    @Test
    fun findsEachKind() {
        file("Movies/big.mp4", 150L * 1024 * 1024)
        file("Download/old.pdf", 1_000, ageDays = 200)
        file("Download/new.pdf", 1_000, ageDays = 3)
        file("Download/app.apk", 5_000)
        file(".hidden/huge.bin", 200L * 1024 * 1024)
        file("Android/data/x/cache.bin", 200L * 1024 * 1024)
        File(root, "Empty/Inner").mkdirs()
        File(root, "Android/obb").mkdirs()

        val report = PhoneCleaner(root) { now }.scan()
        assertEquals(listOf("Movies/big.mp4"), report.of(CleanKind.Large).map { it.path })
        assertEquals(listOf("Download/old.pdf"), report.of(CleanKind.OldDownloads).map { it.path })
        assertEquals(listOf("Download/app.apk"), report.of(CleanKind.Apks).map { it.path })
        // Only the outer empty folder, and never anything under Android or hidden folders.
        assertEquals(listOf("Empty"), report.of(CleanKind.Empty).map { it.path })
        assertTrue(report.items.none { it.path.startsWith("Android") || it.path.startsWith(".") })
    }

    @Test
    fun deletesOnlyInside() {
        val apk = file("Download/app.apk", 5_000)
        File(root, "Empty/Inner").mkdirs()
        val outside = File(root.parentFile, "keep-${root.name}.txt").apply { writeText("keep") }
        val keep = file("Android/data/x/a.bin", 10)
        val cleaner = PhoneCleaner(root) { now }
        val (freed, failed) = cleaner.delete(
            listOf(
                CleanItem("Download/app.apk", 5_000, 0, CleanKind.Apks),
                CleanItem("Empty", 0, 0, CleanKind.Empty, isFolder = true),
                CleanItem("../keep-${root.name}.txt", 4, 0, CleanKind.Large),
                CleanItem("Android/data/x/a.bin", 10, 0, CleanKind.Large),
                CleanItem("", 0, 0, CleanKind.Empty, isFolder = true),
            ),
        )
        assertEquals(5_000, freed)
        assertEquals(3, failed.size)
        assertFalse(apk.exists())
        assertFalse(File(root, "Empty").exists())
        assertTrue(outside.exists() && keep.exists() && root.exists())
        outside.delete()
    }

    @Test
    fun aFolderThatGotFilesIsKept() {
        File(root, "Was/Empty").mkdirs()
        val cleaner = PhoneCleaner(root) { now }
        file("Was/Empty/new.txt", 3)
        val (_, failed) = cleaner.delete(listOf(CleanItem("Was", 0, 0, CleanKind.Empty, isFolder = true)))
        assertEquals(1, failed.size)
        assertTrue(File(root, "Was/Empty/new.txt").exists())
    }
}
