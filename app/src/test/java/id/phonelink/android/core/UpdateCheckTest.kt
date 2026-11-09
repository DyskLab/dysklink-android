package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckTest {
    @Test
    fun comparesVersions() {
        assertTrue(UpdateCheck.isNewer("v0.1.2", "0.1.1"))
        assertFalse(UpdateCheck.isNewer("0.1.1", "0.1.1"))
        assertTrue(UpdateCheck.isNewer("0.1.10", "0.1.9"))
        assertFalse(UpdateCheck.isNewer("0.2", "0.2.0"))
        assertTrue(UpdateCheck.isNewer("0.2.1", "0.2"))
        assertTrue(UpdateCheck.isNewer("0.2.0-beta", "0.1.9"))
        assertFalse(UpdateCheck.isNewer("nightly", "0.1.0"))
    }

    private val releases = """
        [
          {"tag_name":"v0.1.1","html_url":"https://example.com/1","draft":false,"prerelease":true,
           "assets":[{"name":"DyskLink-android-0.1.1.apk","browser_download_url":"https://example.com/1.apk"}]},
          {"tag_name":"v0.3.0","html_url":"https://example.com/3","draft":true,"assets":[]},
          {"tag_name":"v0.1.10","html_url":"https://example.com/10","draft":false,
           "assets":[{"name":"notes.txt","browser_download_url":"https://example.com/n"},
                     {"name":"DyskLink-android-0.1.10.apk","browser_download_url":"https://example.com/10.apk"}]},
          {"tag_name":"v0.1.2","html_url":"https://example.com/2"}
        ]
    """.trimIndent()

    @Test
    fun picksTheHighestPublishedReleaseAndItsApk() {
        assertEquals(
            AppRelease("0.1.10", "https://example.com/10", "https://example.com/10.apk"),
            UpdateCheck.newest(releases, "0.1.1"),
        )
    }

    @Test
    fun releaseWithoutApkFallsBackToItsPage() {
        assertEquals(AppRelease("0.1.2", "https://example.com/2", null), UpdateCheck.newest(releases.replace("v0.1.10", "v0.0.1"), "0.1.1"))
    }

    @Test
    fun nothingNewerGivesNothing() {
        assertNull(UpdateCheck.newest(releases, "0.1.10"))
    }
}
