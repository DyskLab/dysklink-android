package id.phonelink.android.service

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Base64
import android.util.LruCache
import java.io.ByteArrayOutputStream

/** Small pictures for notifications on the Mac, kept small so a notification stays a few KB. */
object NotificationImages {
    private const val ICON_SIZE = 64
    private const val AVATAR_SIZE = 96
    private const val PICTURE_SIZE = 360
    private val icons = LruCache<String, String>(64)

    /** The app's icon. The same for every notification of an app, so it is made once. */
    fun appIcon(context: Context, packageName: String): String? {
        icons.get(packageName)?.let { return it }
        val encoded = runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            png(render(drawable, ICON_SIZE))
        }.getOrNull() ?: return null
        icons.put(packageName, encoded)
        return encoded
    }

    /** The large icon, which chat apps use for the sender's photo. */
    fun avatar(context: Context, notification: Notification): String? = runCatching {
        val icon: Icon = notification.getLargeIcon() ?: return null
        val drawable = icon.loadDrawable(context) ?: return null
        png(render(drawable, AVATAR_SIZE))
    }.getOrNull()

    /** A picture attached with BigPictureStyle, such as a photo sent in a chat. */
    fun picture(context: Context, notification: Notification): String? = runCatching {
        val extras = notification.extras
        val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
                ?: extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)?.loadDrawable(context)?.let { bitmapOf(it) }
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
        }
        bitmap?.let { jpeg(scaled(it, PICTURE_SIZE)) }
    }.getOrNull()

    private fun bitmapOf(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: PICTURE_SIZE
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: PICTURE_SIZE
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(it))
        }
    }

    private fun render(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun scaled(bitmap: Bitmap, longest: Int): Bitmap {
        val scale = longest.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun png(bitmap: Bitmap): String = encode(bitmap, Bitmap.CompressFormat.PNG, 100)

    private fun jpeg(bitmap: Bitmap): String = encode(bitmap, Bitmap.CompressFormat.JPEG, 72)

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
