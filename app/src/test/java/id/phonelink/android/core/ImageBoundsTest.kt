package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageBoundsTest {
    @Test
    fun picturesWithinTheLimitDecodeAsTheyAre() {
        assertEquals(1, ImageBounds.sampleSize(1920, 1080))
        assertEquals(1, ImageBounds.sampleSize(4096, 4096))
    }

    @Test
    fun largerPicturesDecodeSmaller() {
        assertEquals(2, ImageBounds.sampleSize(5120, 2880), "A 5K screen at half")
        assertEquals(2, ImageBounds.sampleSize(4097, 100))
        assertEquals(4, ImageBounds.sampleSize(100, 16_000))
        assertEquals(8, ImageBounds.sampleSize(3000, 3000, ImageBounds.THUMB_SIDE), "Album art comes down to thumbnail size")
    }

    @Test
    fun absurdOrUnknownSizesAreRefused() {
        assertNull(ImageBounds.sampleSize(-1, -1), "Not a picture: the header couldn't be read")
        assertNull(ImageBounds.sampleSize(0, 500))
        assertNull(ImageBounds.sampleSize(40_000, 40_000))
        assertNull(ImageBounds.sampleSize(100, ImageBounds.REFUSE_SIDE + 1))
    }
}
