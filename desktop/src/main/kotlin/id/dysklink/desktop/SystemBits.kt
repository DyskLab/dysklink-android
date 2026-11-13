package id.dysklink.desktop

import id.phonelink.android.core.FileSink
import id.phonelink.android.core.FileOrigin
import id.phonelink.android.core.FileSinkFactory
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/** The system clipboard. Text set from a phone isn't sent back to it. */
class Clipboard(
    private val onCopied: (String) -> Unit,
    private val onImage: (ByteArray) -> Unit = {},
    /** The system clipboard; tests pass their own. */
    private val system: () -> java.awt.datatransfer.Clipboard = { Toolkit.getDefaultToolkit().systemClipboard },
    private val restoreAfterMs: Long = RESTORE_AFTER_MS,
) {
    /** Guarded by [changes]: what this side last saw or set, so it isn't sent back. */
    private var last: String? = read()
    private var lastImage: String? = null
    private var lastQuick: String? = null
    private val changes = ClipGeneration()
    @Volatile var enabled = true
    private var watcher: Thread? = null

    private fun read(): String? = runCatching {
        val clipboard = system()
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) clipboard.getData(DataFlavor.stringFlavor) as String else null
    }.getOrNull()

    /**
     * A picture from another computer. It's remembered by its pixels, not its PNG bytes, because the watcher
     * encodes it again differently and would otherwise send it straight back.
     */
    fun setImage(png: ByteArray): Boolean = runCatching {
        val image = javax.imageio.ImageIO.read(png.inputStream()) ?: error("Not a picture")
        val print = fingerprint(image)
        val quick = quickFingerprint(image)
        changes.change {
            lastImage = print
            lastQuick = quick
        }
        system().setContents(ImageSelection(image), null)
    }.isSuccess

    /** The copied picture, when there's a picture and no text. */
    private fun readImage(): BufferedImage? = runCatching {
        val clipboard = system()
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        val image = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
        image as? BufferedImage ?: BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
            .also { it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }
    }.getOrNull()

    fun set(text: String): Boolean = runCatching {
        changes.change { last = text }
        system().setContents(StringSelection(text), null)
    }.isSuccess

    private val pasteLock = Any()
    /** What the clipboard held before a run of pastes, put back once they're done. Guarded by [pasteLock]. */
    private var beforePaste: Snapshot? = null
    private var pasted: String? = null
    /** Text on the clipboard only for a paste of typed text, until it's put back; the history doesn't keep it. */
    @Volatile var passingThrough: String? = null
        private set
    private var pastedAt = 0L
    private var restore: java.util.concurrent.ScheduledFuture<*>? = null
    private val restorer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dysk-clip-restore").apply { isDaemon = true }
    }

    /**
     * Pastes [text] into the app in front through the clipboard, for text that can't be typed, then puts back what
     * the clipboard held. Neither change is sent to phones or computers: both go through [changes], as [set] does.
     */
    fun pasteThrough(text: String, pressPaste: () -> Unit): Boolean = synchronized(pasteLock) {
        restore?.cancel(false)
        // Only the first paste of a run saves the clipboard; later ones would save the text pasted before.
        if (beforePaste == null) beforePaste = snapshot()
        try {
            // The paste before may still be being read by the app: don't change the clipboard under it.
            val wait = pastedAt + PASTE_GAP_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            passingThrough = text
            if (!set(text)) return false
            pressPaste()
            pasted = text
            pastedAt = System.currentTimeMillis()
            true
        } finally {
            // Whatever happened, the run ends with the clipboard put back; a failed paste mustn't leave the saved
            // clipboard waiting, or a paste much later would put back that stale one.
            restore = restorer.schedule(::restoreAfterPaste, restoreAfterMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private fun restoreAfterPaste(): Unit = synchronized(pasteLock) {
        val saved = beforePaste ?: return
        beforePaste = null
        passingThrough = null
        // Something copied meanwhile, by the person or a phone, stays.
        if (read() != pasted) return
        runCatching {
            val image = saved.image
            changes.change {
                last = saved.text
                if (image != null) { lastImage = fingerprint(image); lastQuick = quickFingerprint(image) }
            }
            system().setContents(saved, null)
        }.onFailure { id.phonelink.android.core.CoreLog.w("DyskLink", "Couldn't put the clipboard back", it) }
        Unit
    }

    private fun snapshot(): Snapshot {
        val clipboard = runCatching { system() }.getOrNull() ?: return Snapshot(null, null, null)
        fun <T> get(flavor: DataFlavor, cast: (Any) -> T?): T? = runCatching {
            if (clipboard.isDataFlavorAvailable(flavor)) cast(clipboard.getData(flavor)) else null
        }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        val files = get(DataFlavor.javaFileListFlavor) { it as? List<File> }
        return Snapshot(get(DataFlavor.stringFlavor) { it as? String }, readImage(), files)
    }

    /** Text, a picture, and copied files: what can be read back and put back. Formatting is lost. */
    private class Snapshot(val text: String?, val image: BufferedImage?, val files: List<File>?) : java.awt.datatransfer.Transferable {
        private val flavors = listOfNotNull(
            DataFlavor.stringFlavor.takeIf { text != null },
            DataFlavor.imageFlavor.takeIf { image != null },
            DataFlavor.javaFileListFlavor.takeIf { files != null },
        )
        override fun getTransferDataFlavors() = flavors.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor in flavors
        override fun getTransferData(flavor: DataFlavor): Any = when {
            flavor == DataFlavor.stringFlavor && text != null -> text
            flavor == DataFlavor.imageFlavor && image != null -> image
            flavor == DataFlavor.javaFileListFlavor && files != null -> files
            else -> throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
        }
    }

    /** Neither Windows nor Linux tells apps when the clipboard changes in a portable way, so it's checked often. */
    fun start() {
        watcher = thread(isDaemon = true, name = "dysk-clipboard") {
            while (!Thread.currentThread().isInterrupted) {
                runCatching { Thread.sleep(700) }.onFailure { return@thread }
                if (!enabled) continue
                // Anything set here while reading makes the read stale: it's dropped, and the next look sees the new value.
                val seen = changes.current()
                val now = read()
                if (now == null) {
                    // Pictures are only checked while there's no text. A cheap look at a sample of pixels comes first;
                    // only when that changed is every pixel hashed, and only new pixels are encoded and sent.
                    val image = readImage() ?: continue
                    val quick = quickFingerprint(image)
                    val unchanged = changes.ifUnchanged(seen) { quick == lastQuick } ?: continue
                    if (unchanged) continue
                    val print = fingerprint(image)
                    val isNew = changes.ifUnchanged(seen) {
                        lastQuick = quick
                        (print != lastImage).also { if (it) lastImage = print }
                    } ?: continue
                    if (isNew) {
                        runCatching { java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray() }
                            .getOrNull()?.let(onImage)
                    }
                    continue
                }
                val isNew = changes.ifUnchanged(seen) { (now != last).also { if (it) last = now } } ?: continue
                if (isNew && now.isNotBlank()) onCopied(now)
            }
        }
    }

    fun stop() { watcher?.interrupt() }

    companion object {
        /** The size and a hash of every pixel as ARGB, which stays the same however the picture is stored or encoded. */
        fun fingerprint(image: BufferedImage): String {
            val width = image.width
            val height = image.height
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val row = IntArray(width)
            val bytes = java.nio.ByteBuffer.allocate(width * 4)
            for (y in 0 until height) {
                image.getRGB(0, y, width, 1, row, 0, width)
                bytes.clear()
                bytes.asIntBuffer().put(row)
                digest.update(bytes.array())
            }
            return "${width}x$height:" + digest.digest().joinToString("") { "%02x".format(it) }
        }

        const val QUICK_SAMPLES = 1_000
        /** How long an app gets to read a paste before the clipboard changes again. */
        const val PASTE_GAP_MS = 150L
        const val RESTORE_AFTER_MS = 800L

        /** The size and about a thousand pixels spread over the picture: cheap enough to take every time it's checked. */
        fun quickFingerprint(image: BufferedImage): String {
            val width = image.width
            val height = image.height
            val total = width.toLong() * height
            var hash = -0x340d631b7bdddcdbL // FNV-1a offset basis
            val count = minOf(total, QUICK_SAMPLES.toLong())
            for (i in 0 until count) {
                val at = i * total / count
                val pixel = image.getRGB((at % width).toInt(), (at / width).toInt())
                hash = (hash xor pixel.toLong()) * 0x100000001b3L
            }
            return "${width}x$height:" + java.lang.Long.toHexString(hash)
        }
    }

    private class ImageSelection(private val image: java.awt.Image) : java.awt.datatransfer.Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any =
            if (flavor == DataFlavor.imageFlavor) image else throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
    }
}

/**
 * Counts what this side sets on the clipboard, so the watcher can tell whether a read overlapped a change.
 * The watcher takes [current] before reading and applies what it read only through [ifUnchanged].
 */
class ClipGeneration {
    private var value = 0L

    @Synchronized
    fun current(): Long = value

    /** Records a change made here, with [block] updating what the watcher compares against. */
    @Synchronized
    fun change(block: () -> Unit) {
        value++
        block()
    }

    /** Runs [block] if nothing changed since [seen]; null when something did, and what was read is stale. */
    @Synchronized
    fun <T> ifUnchanged(seen: Long, block: () -> T): T? = if (value == seen) block() else null
}

/** Writes each file to a hidden temporary file first, and gives it a free name only when it's complete. */
class DownloadSinks(
    /** Where document scans from phones go (`kind: "scan"`); null keeps them in [folder] too. */
    private val scans: (() -> File)? = null,
    /** A scan was saved, at this place, from this phone. */
    private val onScan: (File, FileOrigin) -> Unit = { _, _ -> },
    private val folder: () -> File,
) : FileSinkFactory {
    override fun open(name: String, size: Long): FileSink = sink(folder(), name) {}

    override fun open(name: String, size: Long, origin: FileOrigin): FileSink {
        val scanFolder = scans?.takeIf { origin.kind == "scan" } ?: return open(name, size)
        return sink(scanFolder(), name) { saved -> runCatching { onScan(saved, origin) } }
    }

    private fun sink(folder: File, name: String, saved: (File) -> Unit): FileSink {
        val dir = folder.apply { mkdirs() }
        val temp = File.createTempFile(".dysklink-", ".part", dir)
        val out = FileOutputStream(temp)
        return object : FileSink {
            override fun write(bytes: ByteArray) = out.write(bytes)
            override fun commit(): String {
                out.close()
                return place(temp, dir, windowsSafe(name)).also(saved).path
            }
            override fun abort() { runCatching { out.close() }; temp.delete() }
        }
    }

    companion object {
        private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

        /**
         * Windows can't create files named CON, NUL.txt, COM1.jpg, and so on (any extension), so those get a "_" in
         * front. Done everywhere, so a file keeps the same name wherever it's received.
         */
        fun windowsSafe(name: String): String {
            val stem = name.substringBefore('.').trimEnd(' ')
            return if (stem.uppercase() in RESERVED) "_$name" else name
        }

        /**
         * Moves [temp] to a free name in [dir] without ever replacing a file, even one that appears at the same
         * moment, such as the same name arriving from two devices. A name taken meanwhile moves on to the next one.
         */
        fun place(temp: File, dir: File, name: String): File {
            repeat(MAX_NAME_TRIES) {
                val target = freeName(dir, name)
                try {
                    moveWithoutReplacing(temp.toPath(), target.toPath())
                    return target
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    // Someone took that name just now: try the next one.
                }
            }
            throw java.io.IOException("No free name for $name")
        }

        private fun moveWithoutReplacing(from: java.nio.file.Path, to: java.nio.file.Path) {
            // A rename on Linux replaces silently, but a hard link fails if the name is taken, so it claims the name atomically.
            if (!Platform.isWindows) {
                val linked = try {
                    java.nio.file.Files.createLink(to, from)
                    true
                } catch (taken: java.nio.file.FileAlreadyExistsException) {
                    throw taken
                } catch (_: Exception) {
                    false // Some file systems, such as FAT on a USB stick, have no hard links.
                }
                if (linked) {
                    java.nio.file.Files.deleteIfExists(from)
                    return
                }
            }
            // Without REPLACE_EXISTING this fails when the name is taken; on Windows it's one atomic MoveFileEx.
            java.nio.file.Files.move(from, to)
        }

        private const val MAX_NAME_TRIES = 50

        /** "photo.jpg", then "photo (1).jpg", so nothing is overwritten. */
        fun freeName(dir: File, name: String): File {
            var candidate = File(dir, name)
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
            var n = 1
            while (candidate.exists()) candidate = File(dir, "$base (${n++})$ext")
            return candidate
        }
    }
}

/** A beeping sound while a phone looks for this PC. It stops by itself after 30 seconds, and then calls [onStopped]. */
class Ringer(private val onStopped: () -> Unit = {}) {
    @Volatile private var playing: Thread? = null

    val isRinging get() = playing != null

    fun start() {
        if (playing != null) return
        playing = thread(isDaemon = true, name = "dysk-ring") {
            val me = Thread.currentThread()
            val until = System.currentTimeMillis() + 30_000
            runCatching {
                val format = AudioFormat(44_100f, 16, 1, true, false)
                val line = AudioSystem.getSourceDataLine(format).apply { open(format); start() }
                val beep = tone(880.0, 0.25) + ByteArray(44_100 / 4 * 2) + tone(660.0, 0.25) + ByteArray(44_100 * 2 / 2)
                while (playing === me && System.currentTimeMillis() < until) line.write(beep, 0, beep.size)
                line.drain(); line.close()
            }
            // Without a speaker it stays "ringing" for the same time, so the banner still shows who's looking.
            while (playing === me && System.currentTimeMillis() < until) runCatching { Thread.sleep(200) }
            if (playing === me) {
                playing = null
                onStopped()
            }
        }
    }

    fun stop() { playing = null }

    private fun tone(hz: Double, seconds: Double): ByteArray {
        val count = (44_100 * seconds).toInt()
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val fade = minOf(1.0, i / 400.0, (count - i) / 400.0)
            val value = (sin(2 * PI * hz * i / 44_100) * 0.5 * fade * Short.MAX_VALUE).toInt()
            out[i * 2] = value.toByte(); out[i * 2 + 1] = (value shr 8).toByte()
        }
        return out
    }
}

/** A few switches, kept in settings.properties next to the pairing keys. */
class Settings(private val file: File) {
    private val values = Properties().apply { runCatching { file.inputStream().use { load(it) } } }

    fun bool(key: String, default: Boolean) = values.getProperty(key)?.toBooleanStrictOrNull() ?: default
    fun set(key: String, value: Boolean) = set(key, value.toString())
    fun string(key: String): String? = values.getProperty(key)
    fun int(key: String): Int? = values.getProperty(key)?.toIntOrNull()

    @Synchronized
    fun set(key: String, value: String) {
        values.setProperty(key, value)
        runCatching { file.outputStream().use { values.store(it, "DyskLink") } }
    }
}
