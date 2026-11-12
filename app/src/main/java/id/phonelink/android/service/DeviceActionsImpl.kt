package id.phonelink.android.service

import androidx.core.content.FileProvider
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import id.phonelink.android.core.DeviceActions
import id.phonelink.android.core.FileSink
import id.phonelink.android.core.FileSinkFactory
import id.phonelink.android.core.FileNames
import id.phonelink.android.core.FileOrigin
import id.phonelink.android.core.PhoneKinds
import java.io.File
import java.io.OutputStream

class DeviceActionsImpl(private val context: Context) : DeviceActions {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

    override fun setClipboard(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("DyskLink", text))
    }

    /** Since Android 10, this only works while the app is open. */
    fun readClipboard(): String? {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0 || isSensitive(clip.description.extras)) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
    }

    private fun isSensitive(extras: PersistableBundle?): Boolean =
        extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true ||
            extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true

    override fun media(action: String) {
        when (action) {
            "play_pause" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
    }

    override fun ring(from: String) = PhoneRinger.start(context, from)

    override fun ring(from: String, flash: Boolean) = PhoneRinger.start(context, from, flash)

    override fun stopRinging() = PhoneRinger.stop(context)

    private fun mediaKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }
}

/**
 * Android 10 and up saves to Download/DyskLink. Photo backup and photos of a move from another phone go to
 * Pictures/DyskLink/<phone>, and the files of a move to Download/DyskLink/<phone>/<folder>. Older versions use the app
 * folder.
 */
class DownloadSinks(private val context: Context) : FileSinkFactory {
    override fun open(name: String, size: Long): FileSink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreSink(name, downloads(), "${Environment.DIRECTORY_DOWNLOADS}/DyskLink", null)
        else LegacySink(name, null)

    override fun open(name: String, size: Long, origin: FileOrigin): FileSink {
        val place = place(name, origin)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreSink(name, place.collection, place.path, place.mime)
        else LegacySink(name, place.path)
    }

    override fun exists(origin: FileOrigin, name: String, size: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val place = place(name, origin)
        val where = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
        return runCatching {
            context.contentResolver.query(place.collection, arrayOf(MediaStore.MediaColumns._ID), where, arrayOf("${place.path}/", name, size.toString()), null)
                ?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    private class Place(val collection: android.net.Uri, val path: String, val mime: String?)

    private fun downloads(): android.net.Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri("external")

    /** Photos and videos to Pictures, everything else to Download, each under DyskLink and the sending phone's name. */
    private fun place(name: String, origin: FileOrigin): Place {
        val phone = FileNames.sanitize(origin.peerName)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        val media = origin.kind == PhoneKinds.PHOTO || origin.kind == PhoneKinds.MOVE_MEDIA
        return when {
            media && mime?.startsWith("image/") == true -> Place(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${Environment.DIRECTORY_PICTURES}/DyskLink/$phone", mime)
            media && mime?.startsWith("video/") == true -> Place(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "${Environment.DIRECTORY_PICTURES}/DyskLink/$phone", mime)
            else -> {
                val folder = FileNames.folder(origin.folder)?.let { "/$it" }.orEmpty()
                Place(downloads(), "${Environment.DIRECTORY_DOWNLOADS}/DyskLink/$phone$folder", mime)
            }
        }
    }

    private inner class MediaStoreSink(name: String, collection: android.net.Uri, private val path: String, mime: String?) : FileSink {
        private val resolver = context.contentResolver
        private val uri = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                if (mime != null) put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: error("Can't create the file")
        private val output: OutputStream = resolver.openOutputStream(uri) ?: error("Can't open the file")
        override val openUri: String get() = uri.toString()

        override fun write(bytes: ByteArray) = output.write(bytes)

        override fun commit(): String {
            output.close()
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return path
        }

        override fun abort() {
            runCatching { output.close() }
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    private inner class LegacySink(private val name: String, subfolder: String?) : FileSink {
        private val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, subfolder ?: "DyskLink").apply { mkdirs() }
        private val partial = File(folder, "$name.part")
        private val output = partial.outputStream()
        private var saved: File? = null
        override val openUri: String?
            get() = saved?.let { FileProvider.getUriForFile(context, "${context.packageName}.files", it).toString() }

        override fun write(bytes: ByteArray) = output.write(bytes)

        override fun commit(): String {
            output.close()
            var target = File(folder, name)
            var index = 1
            while (target.exists()) target = File(folder, "${index++}-$name")
            check(partial.renameTo(target)) { "Can't save the file" }
            saved = target
            return target.parent ?: "DyskLink"
        }

        override fun abort() {
            runCatching { output.close() }
            partial.delete()
        }
    }
}
