package id.phonelink.android.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Pictures from another device are checked before they're decoded: the size is read from the header first, a
 * picture larger than [MAX_SIDE] on a side is decoded smaller, and one that claims an absurd size is refused, so a
 * bad or hostile picture can't run the app out of memory.
 */
object ImageBounds {
    /** The largest side decoded as is. A 5K screen comes down to half. */
    const val MAX_SIDE = 4096
    /** Beyond this on a side the picture is refused instead of shrunk. */
    const val REFUSE_SIDE = 32_768
    /** Album art and avatars only show small. */
    const val THUMB_SIDE = 512

    /**
     * The `inSampleSize` that brings [width] by [height] within [maxSide] on both sides: 1, 2, 4, … Null when the
     * size is unknown or beyond [REFUSE_SIDE], so the picture shouldn't be decoded at all.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int? {
        if (width <= 0 || height <= 0 || width > REFUSE_SIDE || height > REFUSE_SIDE || maxSide <= 0) return null
        var sample = 1
        while (width / sample > maxSide || height / sample > maxSide) sample *= 2
        return sample
    }

    /** Decodes [bytes] within [maxSide], or null when it isn't a picture or is refused by [sampleSize]. */
    fun decode(bytes: ByteArray, maxSide: Int = MAX_SIDE, reuse: BitmapFactory.Options? = null): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) ?: return null
        val options = (reuse ?: BitmapFactory.Options()).apply {
            inJustDecodeBounds = false
            inSampleSize = sample
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()
}
