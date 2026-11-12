package id.phonelink.android.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.InputStream

/**
 * Photos and videos taken for another phone, kept in this phone's gallery under Pictures/DyskLink and
 * Movies/DyskLink. Android 9 and older keep them in the app's own Pictures and Movies folders, which need no permission.
 */
object RemoteCaptures {
    class Saved(val name: String, val size: Long, val open: () -> InputStream)

    /** Saves a JPEG. Throws when it can't. */
    fun savePhoto(context: Context, name: String, jpeg: ByteArray): Saved {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/DyskLink")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ) ?: error("Can't create the photo")
            try {
                resolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: error("Can't write the photo")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
            return Saved(name, jpeg.size.toLong()) { resolver.openInputStream(uri) ?: error("The photo is gone") }
        }
        val file = File(folder(context, Environment.DIRECTORY_PICTURES), name)
        file.writeBytes(jpeg)
        return Saved(name, file.length()) { file.inputStream() }
    }

    /** A video being recorded: write to [descriptor], then [finish] or [discard]. */
    class Video(private val context: Context, val name: String, private val uri: Uri?, private val file: File?, val descriptor: ParcelFileDescriptor) {
        fun finish(): Saved {
            runCatching { descriptor.close() }
            val target = uri
            if (target != null) {
                val resolver = context.contentResolver
                resolver.update(target, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                val size = resolver.openFileDescriptor(target, "r")?.use { it.statSize } ?: 0L
                return Saved(name, size) { resolver.openInputStream(target) ?: error("The video is gone") }
            }
            val saved = file!!
            return Saved(name, saved.length()) { saved.inputStream() }
        }

        fun discard() {
            runCatching { descriptor.close() }
            uri?.let { target -> runCatching { context.contentResolver.delete(target, null, null) } }
            file?.delete()
        }
    }

    fun startVideo(context: Context, name: String): Video {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/DyskLink")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                },
            ) ?: error("Can't create the video")
            val descriptor = resolver.openFileDescriptor(uri, "rw") ?: run {
                runCatching { resolver.delete(uri, null, null) }
                error("Can't write the video")
            }
            return Video(context, name, uri, null, descriptor)
        }
        val file = File(folder(context, Environment.DIRECTORY_MOVIES), name)
        val descriptor = ParcelFileDescriptor.open(
            file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
        return Video(context, name, null, file, descriptor)
    }

    private fun folder(context: Context, type: String): File =
        File(context.getExternalFilesDir(type) ?: File(context.filesDir, type), "DyskLink").apply { mkdirs() }
}
