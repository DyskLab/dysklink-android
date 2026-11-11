package id.phonelink.android.service

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import id.phonelink.android.core.GalleryFile
import id.phonelink.android.core.GalleryItem
import java.io.ByteArrayOutputStream

/** Photos and videos for the Mac's gallery, from MediaStore, with small thumbnails. */
object Gallery {
    private const val THUMB = 256
    private const val QUALITY = 70

    private val table get() = MediaStore.Files.getContentUri("external")

    private fun videos(context: Context) = PhotoSync.canReadVideos(context)

    fun page(context: Context, offset: Int, limit: Int): List<GalleryItem> {
        val columns = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT, MediaStore.Video.VideoColumns.DURATION, MediaStore.Files.FileColumns.MIME_TYPE,
        )
        val pending = if (Build.VERSION.SDK_INT >= 29) " AND ${MediaStore.MediaColumns.IS_PENDING} = 0" else ""
        val where = PhotoSync.typeFilter(videos(context)).removePrefix(" AND ") + pending
        val order = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns._ID} DESC"
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        val items = mutableListOf<GalleryItem>()
        runCatching {
            context.contentResolver.query(table, columns, args, null)?.use { c ->
                while (c.moveToNext() && items.size < limit) {
                    val id = c.getLong(0)
                    val video = c.getInt(4) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    items += GalleryItem(
                        id = id,
                        name = PhotoSync.fileName(c.getString(1), id, c.getString(8)),
                        date = c.getLong(2) * 1000,
                        size = c.getLong(3),
                        video = video,
                        durationMs = if (video) c.getLong(7).takeIf { it > 0 } else null,
                        width = c.getInt(5).takeIf { it > 0 },
                        height = c.getInt(6).takeIf { it > 0 },
                        thumb = thumbnail(context, id, video),
                    )
                }
            }
        }
        return items
    }

    private fun uri(id: Long, video: Boolean) =
        ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    @Suppress("DEPRECATION")
    private fun thumbnail(context: Context, id: Long, video: Boolean): String? = runCatching {
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(uri(id, video), Size(THUMB, THUMB), null)
        } else if (video) {
            MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        } ?: return null
        val scale = THUMB.toFloat() / maxOf(bitmap.width, bitmap.height)
        val small = if (scale < 1) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }.getOrNull()

    /** The file for [id], only when it's a photo, or a video the app may read. */
    fun file(context: Context, id: Long): GalleryFile? {
        val columns = arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.MIME_TYPE)
        val where = "${MediaStore.Files.FileColumns._ID} = ?" + PhotoSync.typeFilter(videos(context))
        val found = runCatching {
            context.contentResolver.query(table, columns, where, arrayOf(id.toString()), null)?.use { c ->
                if (!c.moveToFirst()) null else Triple(c.getString(0), c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, c.getString(2))
            }
        }.getOrNull() ?: return null
        val (name, video, mime) = found
        val uri = uri(id, video)
        val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it > 0 } ?: return null
        return GalleryFile(PhotoSync.fileName(name, id, mime), size) { context.contentResolver.openInputStream(uri) ?: error("The photo is gone") }
    }
}
