package id.dysklink.desktop

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import id.phonelink.android.core.CoreLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The bits of linux/videodev2.h that a v4l2loopback writer needs: the ioctl numbers and struct v4l2_format, laid
 * out by hand so the layout can be tested against the header's numbers.
 */
object V4l2 {
    const val BUF_TYPE_VIDEO_OUTPUT = 2
    const val FIELD_NONE = 1
    const val COLORSPACE_SRGB = 8
    val PIX_FMT_YUYV = fourcc('Y', 'U', 'Y', 'V')
    val PIX_FMT_YUV420 = fourcc('Y', 'U', '1', '2')

    /** struct v4l2_capability: driver[16], card[32], bus_info[32], version, capabilities, device_caps, reserved[3]. */
    const val CAPABILITY_SIZE = 104
    /** The union in struct v4l2_format holds pointers (struct v4l2_window), so it starts 8 bytes in on 64-bit. */
    fun formatSize(pointerSize: Int = Native.POINTER_SIZE) = (if (pointerSize == 8) 8 else 4) + 200
    fun formatUnionOffset(pointerSize: Int = Native.POINTER_SIZE) = if (pointerSize == 8) 8 else 4

    fun fourcc(a: Char, b: Char, c: Char, d: Char): Int = a.code or (b.code shl 8) or (c.code shl 16) or (d.code shl 24)

    // _IOC from asm-generic/ioctl.h: dir << 30 | size << 16 | type << 8 | nr.
    private fun ioc(dir: Int, nr: Int, size: Int): Long = ((dir.toLong() shl 30) or (size.toLong() shl 16) or ('V'.code.toLong() shl 8) or nr.toLong())
    private const val IOC_WRITE = 1
    private const val IOC_READ = 2

    /** _IOR('V', 0, struct v4l2_capability): 0x80685600. */
    val VIDIOC_QUERYCAP: Long = ioc(IOC_READ, 0, CAPABILITY_SIZE)
    /** _IOWR('V', 5, struct v4l2_format): 0xc0d05605 on 64-bit. */
    fun vidiocSFmt(pointerSize: Int = Native.POINTER_SIZE): Long = ioc(IOC_READ or IOC_WRITE, 5, formatSize(pointerSize))

    /** struct v4l2_format for an output of [width] × [height] in [pixelFormat], as bytes in this machine's order. */
    fun outputFormat(width: Int, height: Int, pixelFormat: Int, pointerSize: Int = Native.POINTER_SIZE): ByteArray {
        val buffer = ByteBuffer.allocate(formatSize(pointerSize)).order(ByteOrder.nativeOrder())
        buffer.putInt(0, BUF_TYPE_VIDEO_OUTPUT)
        // struct v4l2_pix_format: width, height, pixelformat, field, bytesperline, sizeimage, colorspace, priv, ...
        val pix = formatUnionOffset(pointerSize)
        buffer.putInt(pix, width)
        buffer.putInt(pix + 4, height)
        buffer.putInt(pix + 8, pixelFormat)
        buffer.putInt(pix + 12, FIELD_NONE)
        buffer.putInt(pix + 16, if (pixelFormat == PIX_FMT_YUYV) width * 2 else width)
        buffer.putInt(pix + 20, frameSize(width, height, pixelFormat))
        buffer.putInt(pix + 24, COLORSPACE_SRGB)
        return buffer.array()
    }

    fun frameSize(width: Int, height: Int, pixelFormat: Int): Int =
        if (pixelFormat == PIX_FMT_YUYV) width * height * 2 else width * height * 3 / 2

    /** The driver name in a v4l2_capability: "v4l2 loopback" for v4l2loopback. */
    fun driverOf(capability: ByteArray): String = String(capability, 0, 16, Charsets.US_ASCII).substringBefore('\u0000')
    fun cardOf(capability: ByteArray): String = String(capability, 16, 32, Charsets.UTF_8).substringBefore('\u0000')
}

/** A v4l2loopback device as sysfs tells it. */
data class LoopbackDevice(val node: String, val name: String, val writable: Boolean)

/** What this PC has of v4l2loopback: loaded, installed, its devices, and whether Secure Boot is on. */
data class LoopbackProbe(
    val loaded: Boolean = false,
    /** Whether the module is on disk for this kernel; null when unknown. */
    val installed: Boolean? = null,
    val devices: List<LoopbackDevice> = emptyList(),
    val secureBoot: Boolean? = null,
    /** Whether a file in /etc/modules-load.d loads it at startup; null when unknown. */
    val persistent: Boolean? = null,
)

object Loopback {
    const val LABEL = "DyskLink Camera"
    const val MODPROBE = "sudo modprobe v4l2loopback devices=1 video_nr=10 card_label=\"$LABEL\" exclusive_caps=1"
    const val MODULES_LOAD = "v4l2loopback"
    const val MODPROBE_OPTIONS = "options v4l2loopback devices=1 video_nr=10 card_label=\"$LABEL\" exclusive_caps=1"
    /** Writes both files, so the device comes back after a restart. */
    const val PERSIST = "echo $MODULES_LOAD | sudo tee /etc/modules-load.d/dysklink.conf && " +
        "echo '$MODPROBE_OPTIONS' | sudo tee /etc/modprobe.d/dysklink.conf"

    /**
     * The loopback devices under [sys] (/sys): every video4linux device v4l2loopback makes is virtual. Whether /dev's
     * node can be written comes from [canWrite].
     */
    fun scan(sys: File, canWrite: (String) -> Boolean = { File(it).canWrite() }): LoopbackProbe {
        val loaded = File(sys, "module/v4l2loopback").isDirectory
        val devices = File(sys, "devices/virtual/video4linux").listFiles().orEmpty()
            .filter { it.name.startsWith("video") }
            .sortedBy { it.name.removePrefix("video").toIntOrNull() ?: Int.MAX_VALUE }
            .map { dir ->
                val node = "/dev/${dir.name}"
                LoopbackDevice(node, runCatching { File(dir, "name").readText().trim() }.getOrDefault(""), canWrite(node))
            }
        return LoopbackProbe(loaded = loaded || devices.isNotEmpty(), devices = devices)
    }

    /** The device to send to: DyskLink's own label first, then any other that can be written. */
    fun pick(devices: List<LoopbackDevice>): LoopbackDevice? =
        devices.firstOrNull { it.writable && it.name == LABEL } ?: devices.firstOrNull { it.writable }

    /** From the SecureBoot EFI variable: 4 bytes of attributes, then 1 when it's on. */
    fun secureBoot(efivar: ByteArray?): Boolean? = efivar?.takeIf { it.size >= 5 }?.let { it[4].toInt() == 1 }

    /** Whether any file in [dirs] (such as /etc/modules-load.d) names v4l2loopback on a line of its own. */
    fun loadsAtBoot(dirs: List<File>): Boolean = dirs.any { dir ->
        dir.listFiles { f -> f.name.endsWith(".conf") }.orEmpty().any { file ->
            runCatching { file.readLines() }.getOrDefault(emptyList()).any { it.substringBefore('#').trim() == "v4l2loopback" }
        }
    }

    /** This PC: sysfs, then (only while it isn't loaded) whether the module is on disk, and Secure Boot. */
    fun probe(): LoopbackProbe {
        val found = scan(File("/sys"))
        if (found.loaded) {
            return found.copy(persistent = loadsAtBoot(listOf("/etc/modules-load.d", "/usr/lib/modules-load.d", "/lib/modules-load.d").map(::File)))
        }
        val installed = runCatching {
            val modinfo = listOf("/usr/sbin/modinfo", "/sbin/modinfo", "/usr/bin/modinfo").firstOrNull { File(it).canExecute() } ?: "modinfo"
            val process = ProcessBuilder(modinfo, "-n", "v4l2loopback").redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrNull()
        val efi = File("/sys/firmware/efi/efivars").listFiles { f -> f.name.startsWith("SecureBoot-") }?.firstOrNull()
        return found.copy(installed = installed, secureBoot = secureBoot(efi?.let { runCatching { it.readBytes() }.getOrNull() }))
    }
}

/** Why a webcam output couldn't start: [busy] when another app holds it. */
class WebcamOpenException(message: String, val busy: Boolean = false) : Exception(message)

/** Writes frames to a v4l2loopback device in YUYV. */
class V4l2Sink(private val node: String, override val width: Int, override val height: Int) : WebcamSink {
    @Suppress("FunctionName")
    private interface LibC : Library {
        @Throws(LastErrorException::class) fun open(path: String, flags: Int): Int
        @Throws(LastErrorException::class) fun ioctl(fd: Int, request: NativeLong, arg: Pointer): Int
        @Throws(LastErrorException::class) fun write(fd: Int, buffer: ByteArray, count: NativeLong): NativeLong
        fun close(fd: Int): Int
    }

    private val libc: LibC = Native.load("c", LibC::class.java)
    private var fd = -1
    private val out = ByteArray(V4l2.frameSize(width, height, V4l2.PIX_FMT_YUYV))
    override val name: String get() = node

    override fun open() {
        fd = try {
            libc.open(node, O_RDWR)
        } catch (e: LastErrorException) {
            throw WebcamOpenException("Can't open $node: ${errnoText(e.errorCode)}", busy = e.errorCode == EBUSY)
        }
        try {
            val cap = Memory(V4l2.CAPABILITY_SIZE.toLong()).apply { clear() }
            runCatching { libc.ioctl(fd, NativeLong(V4l2.VIDIOC_QUERYCAP), cap) }.onSuccess {
                val driver = V4l2.driverOf(cap.getByteArray(0, V4l2.CAPABILITY_SIZE))
                if (driver.isNotEmpty() && driver != "v4l2 loopback") throw WebcamOpenException("$node is a $driver device, not v4l2loopback")
            }
            val format = V4l2.outputFormat(width, height, V4l2.PIX_FMT_YUYV)
            val memory = Memory(format.size.toLong()).apply { write(0, format, 0, format.size) }
            try {
                libc.ioctl(fd, NativeLong(V4l2.vidiocSFmt()), memory)
            } catch (e: LastErrorException) {
                throw WebcamOpenException("$node didn't take the picture format: ${errnoText(e.errorCode)}", busy = e.errorCode == EBUSY)
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun send(pixels: IntArray) {
        WebcamFrames.toYuyv(pixels, width, height, out)
        try {
            libc.write(fd, out, NativeLong(out.size.toLong()))
        } catch (e: LastErrorException) {
            throw WebcamOpenException("Writing to $node failed: ${errnoText(e.errorCode)}", busy = e.errorCode == EBUSY)
        }
    }

    override fun close() {
        if (fd >= 0) runCatching { libc.close(fd) }.onFailure { CoreLog.w("DyskLink", "close $node", it) }
        fd = -1
    }

    private fun errnoText(code: Int) = when (code) {
        EBUSY -> "it's busy, another app is sending to it"
        EACCES -> "permission denied"
        ENOENT -> "it doesn't exist"
        EINVAL -> "invalid argument"
        else -> "error $code"
    }

    companion object {
        const val O_RDWR = 2
        const val ENOENT = 2
        const val EACCES = 13
        const val EBUSY = 16
        const val EINVAL = 22
    }
}
