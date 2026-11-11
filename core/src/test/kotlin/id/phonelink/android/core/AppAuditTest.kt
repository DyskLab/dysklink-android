package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppAuditTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun app(name: String, size: Long? = 10, usedDaysAgo: Long? = 1, installedDaysAgo: Long = 400, groups: Set<PermissionGroup> = emptySet()) =
        AppEntry("pkg.$name", name, false, size, usedDaysAgo?.let { if (it < 0) 0 else now - it * day }, now - installedDaysAgo * day, groups)

    @Test
    fun unusedNeedsAnOldInstall() {
        assertTrue(AppAudit.isUnused(app("a", usedDaysAgo = 60), now))
        assertTrue(AppAudit.isUnused(app("never", usedDaysAgo = -1), now))
        assertFalse(AppAudit.isUnused(app("new", usedDaysAgo = -1, installedDaysAgo = 3), now))
        assertFalse(AppAudit.isUnused(app("fresh", usedDaysAgo = 2), now))
        // Without Usage access nothing is called unused.
        assertFalse(AppAudit.isUnused(app("unknown", usedDaysAgo = null), now))
    }

    @Test
    fun sorting() {
        val list = listOf(app("b", size = 5, usedDaysAgo = 1), app("A", size = 50, usedDaysAgo = 90), app("c", size = null, usedDaysAgo = -1))
        assertEquals(listOf("A", "b", "c"), AppAudit.sorted(list, AppSort.Size).map { it.name })
        assertEquals(listOf("c", "A", "b"), AppAudit.sorted(list, AppSort.LastUsed).map { it.name })
        assertEquals(listOf("A", "b", "c"), AppAudit.sorted(list, AppSort.Name).map { it.name })
    }

    @Test
    fun textAndGroups() {
        assertEquals("Used today", AppAudit.lastUsedText(app("a", usedDaysAgo = 0), now))
        assertEquals("Not used in 2 months", AppAudit.lastUsedText(app("a", usedDaysAgo = 65), now))
        assertEquals("Never opened", AppAudit.lastUsedText(app("a", usedDaysAgo = -1), now))
        assertEquals(
            setOf(PermissionGroup.Camera, PermissionGroup.Location),
            PermissionGroup.of(setOf("android.permission.CAMERA", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.INTERNET")),
        )
    }
}
