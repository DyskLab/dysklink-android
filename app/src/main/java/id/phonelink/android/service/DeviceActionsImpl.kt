package id.phonelink.android.service

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

    private fun mediaKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }
}

/** Android 10 and up saves to Download/DyskLink. Older versions use the app folder. */
class DownloadSinks(private val context: Context) : FileSinkFactory {
    override fun open(name: String, size: Long): FileSink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreSink(name) else LegacySink(name)

    private inner class MediaStoreSink(name: String) : FileSink {
        private val resolver = context.contentResolver
        private val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DyskLink")
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        ) ?: error("Can't create the file")
        private val output: OutputStream = resolver.openOutputStream(uri) ?: error("Can't open the file")

        override fun write(bytes: ByteArray) = output.write(bytes)

        override fun commit(): String {
            output.close()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return "Download/DyskLink"
        }

        override fun abort() {
            runCatching { output.close() }
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    private inner class LegacySink(private val name: String) : FileSink {
        private val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "DyskLink").apply { mkdirs() }
        private val partial = File(folder, "$name.part")
        private val output = partial.outputStream()

        override fun write(bytes: ByteArray) = output.write(bytes)

        override fun commit(): String {
            output.close()
            var target = File(folder, name)
            var index = 1
            while (target.exists()) target = File(folder, "${index++}-$name")
            check(partial.renameTo(target)) { "Can't save the file" }
            return target.parent ?: "DyskLink"
        }

        override fun abort() {
            runCatching { output.close() }
            partial.delete()
        }
    }
}
