package id.phonelink.android.service

import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoSyncTest {
    @Test
    fun freshStartOnlyTakesLaterSeconds() {
        val (where, args) = PhotoSync.selection(PhotoMark(1000, Long.MAX_VALUE))
        assertEquals("(date_added > ?)", where)
        assertEquals(listOf("1000"), args.toList())
    }

    @Test
    fun afterAPhotoTheSameSecondContinuesByID() {
        val (where, args) = PhotoSync.selection(PhotoMark(1000, 42))
        assertEquals("(date_added > ? OR (date_added = ? AND _id > ?))", where)
        assertEquals(listOf("1000", "1000", "42"), args.toList())
    }

    @Test
    fun videosGetVideoNames() {
        assertEquals("VID_7.mp4", PhotoSync.fileName(null, 7, "video/mp4"))
        assertEquals("VID_7.mov", PhotoSync.fileName("", 7, "video/quicktime"))
    }

    @Test
    fun namesFallBackToTheID() {
        assertEquals("PXL_1.jpg", PhotoSync.fileName("PXL_1.jpg", 7, "image/jpeg"))
        assertEquals("IMG_7.png", PhotoSync.fileName(null, 7, "image/png"))
        assertEquals("IMG_7.jpg", PhotoSync.fileName(" ", 7, null))
    }
}
