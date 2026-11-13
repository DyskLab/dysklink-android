package id.dysklink.desktop

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.PcSoundCapture
import id.phonelink.android.core.PcSoundSender
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.roundToInt

// This PC's own sound for "Hear the PC" on the phone: what plays on the speakers, recorded without a microphone.
// Windows: WASAPI loopback on the default output. Linux: the PulseAudio or PipeWire monitor of the default output.

object PcSound {
    /** A new recorder for this system, or null where there's none (a Mac running the desktop app while it's built). */
    fun capture(): PcSoundCapture? = when {
        Platform.isWindows -> WasapiLoopback()
        Platform.isMac -> null
        else -> ProcessCapture(
            ProcessCapture.LINUX_COMMANDS,
            missing = HostChecks.soundMissing(HostChecks.distro(runCatching { java.io.File("/etc/os-release").readText() }.getOrDefault(""))),
        )
    }
}

/**
 * Turns whatever the system records in into the protocol's sound: 48 kHz, stereo, 16-bit little-endian. Mono is
 * doubled, more channels keep the first two (front left and right), and other rates are resampled linearly, which
 * carries over from one buffer to the next.
 */
class PcmConverter(private val inRate: Int, private val inChannels: Int, private val emit: (ByteArray, Int) -> Unit) {
    private val step = inRate.toDouble() / PcSoundSender.RATE
    /** Where the next output frame falls, in input frames from the last frame of the previous buffer. */
    private var position = 1.0
    private var lastLeft = 0f
    private var lastRight = 0f

    init {
        require(inRate > 0 && inChannels > 0) { "Bad format $inRate Hz × $inChannels" }
    }

    /** [samples] holds [frames] interleaved frames of [inChannels] floats, -1 to 1. */
    fun feed(samples: FloatArray, frames: Int) {
        if (frames <= 0) return
        fun left(i: Int) = if (i < 0) lastLeft else samples[i * inChannels]
        fun right(i: Int) = if (i < 0) lastRight else samples[i * inChannels + if (inChannels > 1) 1 else 0]
        val capacity = ((frames + 1) / step).toInt() + 2
        val out = ByteBuffer.allocate(capacity * 4).order(ByteOrder.LITTLE_ENDIAN)
        // Input frame -1 is the last one of the previous buffer, so frames between buffers are joined smoothly.
        var t = position - 1.0
        while (t <= frames - 1 + EPSILON && out.remaining() >= 4) {
            val index = kotlin.math.floor(t).toInt()
            val fraction = (t - index).toFloat()
            val l: Float
            val r: Float
            if (index + 1 > frames - 1 || fraction < 1e-6f) {
                l = left(index.coerceAtMost(frames - 1)); r = right(index.coerceAtMost(frames - 1))
            } else {
                l = left(index) + (left(index + 1) - left(index)) * fraction
                r = right(index) + (right(index + 1) - right(index)) * fraction
            }
            out.putShort(sample(l)); out.putShort(sample(r))
            t += step
        }
        position = t - (frames - 1)
        lastLeft = left(frames - 1)
        lastRight = right(frames - 1)
        if (out.position() > 0) emit(out.array(), out.position())
    }

    /** [frames] of silence, as WASAPI reports for a silent buffer. */
    fun silence(frames: Int) = feed(FloatArray(frames * inChannels), frames)

    companion object {
        private const val EPSILON = 1e-9

        fun sample(value: Float): Short = (value * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()

        /** Interleaved samples in [bytes] as floats: 32-bit float, or 16-, 24-, or 32-bit integers. */
        fun toFloats(bytes: ByteBuffer, count: Int, bits: Int, isFloat: Boolean): FloatArray {
            val buffer = bytes.order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(count) { i ->
                when {
                    isFloat && bits == 32 -> buffer.getFloat(i * 4)
                    bits == 16 -> buffer.getShort(i * 2) / 32768f
                    bits == 24 -> {
                        val at = i * 3
                        val value = (buffer.get(at).toInt() and 0xff) or ((buffer.get(at + 1).toInt() and 0xff) shl 8) or (buffer.get(at + 2).toInt() shl 16)
                        value / 8388608f
                    }
                    bits == 32 -> buffer.getInt(i * 4) / 2147483648f
                    else -> 0f
                }
            }
        }
    }
}

/**
 * WASAPI loopback, through JNA and a few COM calls by vtable slot: the default output device in shared mode with
 * AUDCLNT_STREAMFLAGS_LOOPBACK, read by polling IAudioCaptureClient every 10 ms, converted by [PcmConverter].
 * Everything runs on its own thread, which owns the COM objects from start to end.
 */
class WasapiLoopback : PcSoundCapture {
    @Suppress("FunctionName")
    private interface Ole32 : Library {
        fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
        fun CoUninitialize()
        fun CoCreateInstance(clsid: Pointer, outer: Pointer?, context: Int, iid: Pointer, out: PointerByReference): Int
        fun CoTaskMemFree(memory: Pointer?)
    }

    /** A COM interface pointer: calls go through its vtable. */
    private class Com(val pointer: Pointer) {
        fun call(slot: Int, vararg args: Any?): Int {
            val table = pointer.getPointer(0)
            val function = Function.getFunction(table.getPointer(slot.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
            return function.invokeInt(arrayOf(pointer, *args))
        }

        fun release() { runCatching { call(2) } }
    }

    class Failure(message: String) : Exception(message)

    @Volatile private var running = false
    @Volatile private var worker: Thread? = null

    override fun start(onPcm: (ByteArray, Int) -> Unit, onEnded: (String) -> Unit): String? {
        val ole = runCatching { Native.load("ole32", Ole32::class.java) }.getOrNull() ?: return "Windows sound recording isn't available."
        val ready = CountDownLatch(1)
        var error: String? = null
        running = true
        worker = thread(isDaemon = true, name = "dysk-wasapi") {
            try {
                record(ole, onPcm) { ready.countDown() }
            } catch (failure: Exception) {
                val message = failure.message ?: "The PC's sound couldn't be recorded."
                if (ready.count > 0) {
                    error = message
                    ready.countDown()
                } else if (running) {
                    running = false
                    onEnded(message)
                }
                CoreLog.w("DyskLink", "WASAPI loopback", failure)
            }
        }
        if (!ready.await(5, TimeUnit.SECONDS)) {
            running = false
            return "Windows didn't start recording the PC's sound."
        }
        if (error != null) running = false
        return error
    }

    override fun stop() {
        running = false
        val thread = worker ?: return
        worker = null
        if (thread !== Thread.currentThread()) thread.join(1_000)
    }

    private fun check(hr: Int, what: String) {
        if (hr < 0) throw Failure(
            when (hr) {
                E_NOTFOUND -> "This PC has no sound output to record."
                AUDCLNT_E_DEVICE_INVALIDATED -> "The PC's sound output changed. Start listening again."
                else -> "Windows couldn't record the PC's sound ($what, 0x%08X).".format(hr)
            },
        )
    }

    private fun record(ole: Ole32, onPcm: (ByteArray, Int) -> Unit, started: () -> Unit) {
        val init = ole.CoInitializeEx(null, COINIT_MULTITHREADED)
        val objects = ArrayList<Com>()
        var format: Pointer? = null
        try {
            val enumeratorRef = PointerByReference()
            check(ole.CoCreateInstance(guid(CLSID_MM_DEVICE_ENUMERATOR), null, CLSCTX_ALL, guid(IID_IMM_DEVICE_ENUMERATOR), enumeratorRef), "enumerator")
            val enumerator = Com(enumeratorRef.value).also { objects += it }
            val deviceRef = PointerByReference()
            // GetDefaultAudioEndpoint(eRender, eConsole): what plays on the speakers or headphones now.
            check(enumerator.call(4, 0, 0, deviceRef), "output")
            val device = Com(deviceRef.value).also { objects += it }
            val clientRef = PointerByReference()
            check(device.call(3, guid(IID_IAUDIO_CLIENT), CLSCTX_ALL, null, clientRef), "activate")
            val client = Com(clientRef.value).also { objects += it }
            val formatRef = PointerByReference()
            check(client.call(8, formatRef), "format")
            format = formatRef.value
            val head = format!!.getByteArray(0, 18)
            val extra = (head[16].toInt() and 0xff) or ((head[17].toInt() and 0xff) shl 8)
            val mix = MixFormat.read(if (extra >= 22) format!!.getByteArray(0, 40) else head + ByteArray(22))
            if (!mix.supported) throw Failure("The PC's sound format (${mix.bits}-bit) can't be recorded.")
            // Shared mode, loopback, a 200 ms buffer.
            check(client.call(3, 0, AUDCLNT_STREAMFLAGS_LOOPBACK, 2_000_000L, 0L, format, null), "initialize")
            val captureRef = PointerByReference()
            check(client.call(14, guid(IID_IAUDIO_CAPTURE_CLIENT), captureRef), "capture")
            val capture = Com(captureRef.value).also { objects += it }
            check(client.call(10), "start")
            started()
            CoreLog.i("DyskLink", "WASAPI loopback ${mix.rate} Hz × ${mix.channels}, ${mix.bits}-bit${if (mix.isFloat) " float" else ""}")
            val converter = PcmConverter(mix.rate, mix.channels, onPcm)
            val packet = IntByReference()
            val data = PointerByReference()
            val frames = IntByReference()
            val flags = IntByReference()
            while (running) {
                Thread.sleep(10)
                check(capture.call(5, packet), "packet")
                while (running && packet.value > 0) {
                    check(capture.call(3, data, frames, flags, null, null), "read")
                    val count = frames.value
                    if (count > 0) {
                        if (flags.value and AUDCLNT_BUFFERFLAGS_SILENT != 0) {
                            converter.silence(count)
                        } else {
                            val bytes = data.value.getByteArray(0, count * mix.blockAlign)
                            val samples = PcmConverter.toFloats(ByteBuffer.wrap(bytes), count * mix.channels, mix.bits, mix.isFloat)
                            converter.feed(samples, count)
                        }
                    }
                    check(capture.call(4, count), "release")
                    check(capture.call(5, packet), "packet")
                }
            }
            runCatching { client.call(11) }
        } finally {
            objects.asReversed().forEach { it.release() }
            format?.let { ole.CoTaskMemFree(it) }
            if (init >= 0) ole.CoUninitialize()
        }
    }

    /** WAVEFORMATEX, or WAVEFORMATEXTENSIBLE with its subformat. */
    data class MixFormat(val tag: Int, val channels: Int, val rate: Int, val blockAlign: Int, val bits: Int, val isFloat: Boolean) {
        val supported: Boolean get() = channels > 0 && rate > 0 && blockAlign > 0 && ((isFloat && bits == 32) || (!isFloat && bits in setOf(16, 24, 32)))

        companion object {
            fun read(bytes: ByteArray): MixFormat {
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val tag = b.getShort(0).toInt() and 0xffff
                val channels = b.getShort(2).toInt() and 0xffff
                val rate = b.getInt(4)
                val blockAlign = b.getShort(12).toInt() and 0xffff
                val bits = b.getShort(14).toInt() and 0xffff
                val extensible = tag == 0xFFFE && (b.getShort(16).toInt() and 0xffff) >= 22
                // WAVE_FORMAT_IEEE_FLOAT is 3; in the extensible form, the first word of the subformat GUID says it.
                val isFloat = if (extensible) b.getInt(24) == 3 else tag == 3
                return MixFormat(tag, channels, rate, blockAlign, bits, isFloat)
            }
        }
    }

    companion object {
        const val COINIT_MULTITHREADED = 0
        const val CLSCTX_ALL = 0x17
        const val AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
        const val AUDCLNT_BUFFERFLAGS_SILENT = 0x2
        const val E_NOTFOUND = 0x80070490.toInt()
        const val AUDCLNT_E_DEVICE_INVALIDATED = 0x88890004.toInt()
        const val CLSID_MM_DEVICE_ENUMERATOR = "BCDE0395-E52F-467C-8E3D-C4579291692E"
        const val IID_IMM_DEVICE_ENUMERATOR = "A95664D2-9614-4F35-A746-DE8DB63617E6"
        const val IID_IAUDIO_CLIENT = "1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"
        const val IID_IAUDIO_CAPTURE_CLIENT = "C8ADBD64-E71E-48A0-A4DE-185C395CD317"

        /** A GUID as it lies in memory: the first three groups little-endian, the rest as written. */
        fun guidBytes(text: String): ByteArray {
            val hex = text.replace("-", "")
            require(hex.length == 32)
            val raw = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return byteArrayOf(raw[3], raw[2], raw[1], raw[0], raw[5], raw[4], raw[7], raw[6]) + raw.copyOfRange(8, 16)
        }

        private fun guid(text: String): Memory = guidBytes(text).let { bytes -> Memory(16).apply { write(0, bytes, 0, 16) } }
    }
}

/**
 * Records by running a tool that writes raw 48 kHz stereo s16le to its output: `parec` on the default output's
 * monitor (PulseAudio, and PipeWire's pipewire-pulse), else `pw-record` on the default output. The tool is stopped
 * with the recording, and when DyskLink quits.
 */
class ProcessCapture(
    private val commands: List<List<String>>,
    private val readBytes: Int = PcSoundSender.CHUNK_BYTES,
    /** What the phone is told when none of the tools is installed. */
    private val missing: String = "Install pulseaudio-utils (parec) or pipewire (pw-record) so phones can hear this PC.",
) : PcSoundCapture {
    @Volatile private var process: Process? = null
    @Volatile private var running = false

    override fun start(onPcm: (ByteArray, Int) -> Unit, onEnded: (String) -> Unit): String? {
        var last = missing
        for (command in commands) {
            val started = runCatching { ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.PIPE).start() }.getOrNull() ?: continue
            // A tool that can't find the device ends at once.
            if (started.waitFor(300, TimeUnit.MILLISECONDS)) {
                val why = runCatching { started.errorStream.readBytes().decodeToString().lineSequence().firstOrNull { it.isNotBlank() } }.getOrNull()
                last = "${command.first()} couldn't record the PC's sound" + (why?.let { ": ${it.take(160)}" } ?: ".")
                continue
            }
            process = started
            running = true
            live += started
            // Its messages aren't needed, but must be read so it never waits on a full pipe.
            thread(isDaemon = true, name = "dysk-sound-err") { runCatching { started.errorStream.copyTo(java.io.OutputStream.nullOutputStream()) } }
            thread(isDaemon = true, name = "dysk-sound-in") {
                val input = started.inputStream
                runCatching {
                    while (running) {
                        val buffer = ByteArray(readBytes)
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0 && running) onPcm(buffer, count)
                    }
                }
                if (running) {
                    running = false
                    live -= started
                    onEnded("Recording the PC's sound stopped (${command.first()} ended).")
                }
            }
            CoreLog.i("DyskLink", "Recording the PC's sound with ${command.first()}")
            return null
        }
        return last
    }

    override fun stop() {
        running = false
        val running = process ?: return
        process = null
        end(running)
        live -= running
    }

    companion object {
        val LINUX_COMMANDS = listOf(
            listOf("parec", "--device=@DEFAULT_MONITOR@", "--format=s16le", "--rate=48000", "--channels=2", "--latency-msec=40", "--raw"),
            listOf("pw-record", "-P", "{ stream.capture.sink=true }", "--format=s16", "--rate=48000", "--channels=2", "--raw", "-"),
        )

        /** Tools still recording, ended when DyskLink quits, so none stays behind. */
        private val live: MutableSet<Process> = java.util.Collections.synchronizedSet(HashSet())

        init {
            Runtime.getRuntime().addShutdownHook(Thread { live.toList().forEach { it.destroyForcibly() } })
        }

        fun end(process: Process) {
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}
