package id.phonelink.android.core

import java.io.File
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

// What a Windows or Linux PC serves a phone, as a Mac does: its sound, commands, and a terminal. The protocol side
// lives here so it can be tested without a PC; the desktop app gives the parts that need the system (recording the
// sound, starting a shell on a pseudo terminal).

// Sound

/** What "Hear the PC" records: the PC's own output, as 48 kHz stereo 16-bit little-endian PCM. */
interface PcSoundCapture {
    /**
     * Starts recording. [onPcm] gets the first `length` bytes of each buffer, in any lengths, on the capture's own
     * thread, and may keep the array. [onEnded] runs once if it stops by itself, with why. Returns why it can't start,
     * or null.
     */
    fun start(onPcm: (ByteArray, Int) -> Unit, onEnded: (String) -> Unit): String?

    /** Stops for good. Nothing more reaches `onPcm` once it returns. Safe to call more than once. */
    fun stop()
}

/**
 * Sends a PC's sound to one phone, as the Mac does: `audio.started`, then 40 ms chunks (7,680 bytes) as `audio.frame`.
 * Chunks wait in a queue of [MAX_QUEUED]; when the network falls behind the oldest is dropped, so the sound stays
 * close to live. Toward a `bin1` phone digital silence is held back after 200 ms ([SilenceGate]).
 * [onEnded] runs once when it ends by itself (the recording failed, the phone stopped taking chunks), with why.
 */
class PcSoundSender(
    private val capture: PcSoundCapture,
    /** Waits like a direct write; false when it couldn't be sent. */
    private val send: (Envelope) -> Boolean,
    private val binary: () -> Boolean,
    private val onEnded: (String) -> Unit,
) {
    private val queue = ArrayBlockingQueue<ByteArray>(MAX_QUEUED)
    private val running = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private var pending = ByteArray(CHUNK_BYTES)
    private var filled = 0
    private val gate = SilenceGate()
    @Volatile private var sender: Thread? = null

    /** Chunks that went out, and chunks dropped because the network was behind. */
    @Volatile var sent = 0L
        private set
    @Volatile var dropped = 0L
        private set
    val held: Long get() = gate.held

    val isRunning: Boolean get() = running.get()

    /** Returns why it couldn't start, or null. */
    fun start(): String? {
        if (!running.compareAndSet(false, true)) return null
        val error = runCatching { capture.start(::accept) { why -> end(why) } }.getOrElse { it.message ?: "The PC's sound couldn't be recorded" }
        if (error != null) {
            running.set(false)
            runCatching { capture.stop() }
            return error
        }
        sender = thread(isDaemon = true, name = "dysk-sound-out") { pump() }
        return null
    }

    /** Collects the recording into whole chunks. Only the capture's thread calls it. */
    private fun accept(bytes: ByteArray, length: Int) {
        if (!running.get()) return
        var at = 0
        val end = length.coerceIn(0, bytes.size)
        while (at < end) {
            val count = minOf(CHUNK_BYTES - filled, end - at)
            bytes.copyInto(pending, filled, at, at + count)
            filled += count
            at += count
            if (filled == CHUNK_BYTES) {
                val chunk = pending
                pending = ByteArray(CHUNK_BYTES)
                filled = 0
                while (!queue.offer(chunk)) {
                    if (queue.poll() != null) dropped++
                }
            }
        }
    }

    private fun pump() {
        if (!send(Envelope(MessageType.AUDIO_STARTED, audioRate = RATE, audioChannels = CHANNELS))) return end("The phone isn't connected")
        var failures = 0
        while (running.get()) {
            val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (!running.get()) break
            if (binary() && !gate.shouldSend(chunk)) continue
            if (send(Envelope(MessageType.AUDIO_FRAME, raw = chunk))) {
                sent++
                failures = 0
            } else if (++failures >= MAX_SEND_FAILURES) {
                return end("The connection was lost")
            }
        }
    }

    private fun end(why: String) {
        if (halt() && ended.compareAndSet(false, true)) onEnded(why)
    }

    /** Stops recording and sending. [onEnded] doesn't run for a stop asked for here. */
    fun stop() {
        ended.set(true)
        halt()
    }

    /** True when it was running. */
    private fun halt(): Boolean {
        if (!running.getAndSet(false)) return false
        runCatching { capture.stop() }
        queue.clear()
        // The sending thread isn't interrupted, which could break a write in progress; it sees the stop within 200 ms.
        sender = null
        return true
    }

    companion object {
        const val RATE = 48_000
        const val CHANNELS = 2
        /** 40 ms of 48 kHz stereo 16-bit sound. */
        const val CHUNK_BYTES = 7_680
        /** As the Mac's six chunks in flight: about a quarter of a second. */
        const val MAX_QUEUED = 6
        const val MAX_SEND_FAILURES = 5
    }
}

// Commands

/** A command a PC lists for phones. Only [id] and [name] go to the phone; the command line stays on the PC. */
@Serializable
data class SavedCommand(val id: String, val name: String, val command: String)

/** Runs one command line, as the Mac's CommandRunner does: stopped after [TIMEOUT_MS], only the last of the output kept. */
object CommandRunner {
    const val TIMEOUT_MS = 60_000L
    /** As on the Mac: the last 4 KB of what it printed. */
    const val MAX_OUTPUT_BYTES = 4_096
    const val TIMED_OUT = -2

    /** [argv] is the shell and the command line, such as `cmd.exe /c ...` or `/bin/sh -c ...`. */
    fun run(
        id: String,
        argv: List<String>,
        timeoutMs: Long = TIMEOUT_MS,
        maxBytes: Int = MAX_OUTPUT_BYTES,
        directory: File? = null,
    ): CommandResult {
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(true).apply { directory?.let(::directory) }.start()
        } catch (error: Exception) {
            return CommandResult(id, -1, "The shell couldn't be started.")
        }
        // Nothing is typed into it: a command waiting for input gets the end at once.
        runCatching { process.outputStream.close() }
        val tail = TailBuffer(maxBytes)
        val reader = thread(isDaemon = true, name = "dysk-command") {
            runCatching {
                val buffer = ByteArray(8 * 1024)
                val input = process.inputStream
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    tail.write(buffer, count)
                }
            }
        }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { process.descendants().forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        }
        // Something it left running in the background can keep the output open; that isn't waited for.
        reader.join(1_000)
        if (reader.isAlive) runCatching { process.inputStream.close() }
        val text = tail.text()
        return if (finished) {
            CommandResult(id, process.exitValue(), text)
        } else {
            CommandResult(id, TIMED_OUT, (text.trimEnd() + "\nStopped after ${timeoutMs / 1000} seconds.").trimStart())
        }
    }

    /** Keeps the last [max] bytes written. */
    internal class TailBuffer(private val max: Int) {
        private val bytes = ByteArray(max.coerceAtLeast(1))
        private var start = 0
        private var size = 0

        @Synchronized
        fun write(data: ByteArray, count: Int) {
            for (i in 0 until count) {
                val at = (start + size) % bytes.size
                bytes[at] = data[i]
                if (size < bytes.size) size++ else start = (start + 1) % bytes.size
            }
        }

        @Synchronized
        fun text(): String {
            val out = ByteArray(size) { bytes[(start + it) % bytes.size] }
            return String(out, Charsets.UTF_8)
        }
    }
}

// Terminal

/** A shell on a pseudo terminal, started by the desktop app. */
interface PtySession {
    /** What the shell prints. Ends when the shell does. */
    val output: InputStream
    fun write(bytes: ByteArray)
    fun resize(cols: Int, rows: Int)
    /** Asks the shell to end, like closing a terminal window. */
    fun hangUp()
    fun kill()
    val isAlive: Boolean
    /** Waits until it ends, and returns its exit code. */
    fun waitFor(): Int
}

/** Starts a shell [cols] × [rows]. Throws when it can't. */
fun interface PtyFactory {
    fun open(cols: Int, rows: Int): PtySession
}

/**
 * Terminal sessions from phones, as on the Mac: each needs the phone's own switch ([allowed]), at most
 * [maxPerPhone] at a time, a notice on the PC for each one opened ([onOpened]), and all of a phone's sessions end
 * when it disconnects or its switch is turned off ([end]). Output goes out as fast as the phone takes it; while a
 * piece waits for the network the shell isn't read, so it waits too instead of filling memory.
 */
class PcTerminals(
    private val factory: () -> PtyFactory?,
    /** Waits like a direct write. */
    private val send: (phoneId: String, Envelope) -> Boolean,
    /** Queues without waiting. */
    private val post: (phoneId: String, Envelope) -> Boolean,
    private val onOpened: (phoneId: String) -> Unit = {},
    private val maxPerPhone: Int = MAX_PER_PHONE,
    private val killAfterMs: Long = KILL_AFTER_MS,
) {
    private class Session(val phoneId: String, val id: String, val pty: PtySession) {
        val input = LinkedBlockingQueue<ByteArray>()
        val queuedBytes = AtomicInteger(0)
        @Volatile var writer: Thread? = null
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val _open = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** How many terminals each phone has open now. */
    val open: StateFlow<Map<String, Int>> = _open.asStateFlow()

    private fun key(phoneId: String, id: String) = "$phoneId:$id"

    fun count(phoneId: String): Int = sessions.values.count { it.phoneId == phoneId }

    private fun publish() {
        _open.value = sessions.values.groupingBy { it.phoneId }.eachCount()
    }

    /** A `terminal.*` message from [phoneId]. [allowed]: that phone's "open a terminal" switch is on. */
    fun handle(phoneId: String, type: String, message: TerminalMessage, allowed: Boolean) {
        if (message.id.isEmpty() || message.id.length > 64) return
        val key = key(phoneId, message.id)
        when (type) {
            MessageType.TERMINAL_OPEN -> open(phoneId, message, allowed)
            MessageType.TERMINAL_INPUT -> {
                val session = sessions[key] ?: return
                val bytes = message.data?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() } ?: return
                // Input beyond what a stuck shell can ever take is dropped rather than held in memory.
                if (session.queuedBytes.get() + bytes.size > MAX_PENDING_INPUT) return
                session.queuedBytes.addAndGet(bytes.size)
                session.input.offer(bytes)
            }
            MessageType.TERMINAL_RESIZE -> {
                val session = sessions[key] ?: return
                val cols = message.cols ?: return
                val rows = message.rows ?: return
                runCatching { session.pty.resize(cols.coerceIn(10, 500), rows.coerceIn(2, 300)) }
            }
            MessageType.TERMINAL_CLOSE -> sessions[key]?.let { hangUp(it) }
        }
    }

    private fun closed(phoneId: String, id: String, error: String? = null, exitCode: Int? = null) =
        post(phoneId, Envelope(MessageType.TERMINAL_CLOSED, terminal = TerminalMessage(id, error = error, exitCode = exitCode)))

    private fun open(phoneId: String, message: TerminalMessage, allowed: Boolean) {
        val id = message.id
        if (!allowed) {
            closed(phoneId, id, "The terminal is turned off on this PC. Turn it on in DyskLink on the PC, on this phone's page.")
            return
        }
        val key = key(phoneId, id)
        val session = synchronized(sessions) {
            if (sessions.containsKey(key)) return
            if (count(phoneId) >= maxPerPhone) {
                closed(phoneId, id, "Close a terminal first. Up to $maxPerPhone can be open.")
                return
            }
            val pty = try {
                factory()?.open((message.cols ?: 80).coerceIn(10, 500), (message.rows ?: 24).coerceIn(2, 300))
            } catch (error: Exception) {
                CoreLog.w(TAG, "The shell couldn't start", error)
                closed(phoneId, id, "The shell couldn't start.")
                return
            }
            if (pty == null) {
                closed(phoneId, id, "This PC can't open a terminal.")
                return
            }
            Session(phoneId, id, pty).also { sessions[key] = it }
        }
        publish()
        post(phoneId, Envelope(MessageType.TERMINAL_OPENED, terminal = TerminalMessage(id)))
        runCatching { onOpened(phoneId) }
        session.writer = thread(isDaemon = true, name = "dysk-term-in") { writeLoop(session) }
        thread(isDaemon = true, name = "dysk-term-out") { readLoop(key, session) }
    }

    private fun writeLoop(session: Session) {
        try {
            while (true) {
                val bytes = session.input.take()
                session.queuedBytes.addAndGet(-bytes.size)
                session.pty.write(bytes)
            }
        } catch (_: InterruptedException) {
        } catch (error: Exception) {
            CoreLog.w(TAG, "Terminal input failed", error)
        }
    }

    private fun readLoop(key: String, session: Session) {
        val buffer = ByteArray(OUTPUT_BYTES)
        try {
            while (true) {
                val count = session.pty.output.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (sessions[key] !== session) break
                val data = java.util.Base64.getEncoder().encodeToString(buffer.copyOf(count))
                if (!send(session.phoneId, Envelope(MessageType.TERMINAL_OUTPUT, terminal = TerminalMessage(session.id, data = data)))) {
                    // The phone stopped reading: the shell goes, rather than running on for nobody.
                    hangUp(session)
                    break
                }
            }
        } catch (_: Exception) {
            // The pseudo terminal closes with the shell; on some systems that's an error instead of the end.
        }
        val code = runCatching { session.pty.waitFor() }.getOrDefault(-1)
        session.writer?.interrupt()
        // Ended here or from the phone: the phone hears once.
        if (sessions.remove(key, session)) {
            publish()
            closed(session.phoneId, session.id, exitCode = code)
        }
    }

    private fun hangUp(session: Session) {
        runCatching { session.pty.hangUp() }
        thread(isDaemon = true, name = "dysk-term-kill") {
            Thread.sleep(killAfterMs)
            if (session.pty.isAlive) runCatching { session.pty.kill() }
        }
    }

    /**
     * Ends [phoneId]'s sessions, or every one with null. With [reason], the phone is told why (it's still
     * connected); without, it's gone and hears nothing.
     */
    fun end(phoneId: String?, reason: String? = ENDED_HERE) {
        val ending = sessions.entries.filter { phoneId == null || it.value.phoneId == phoneId }
        for ((key, session) in ending) {
            if (!sessions.remove(key, session)) continue
            session.writer?.interrupt()
            hangUp(session)
            if (reason != null) closed(session.phoneId, session.id, reason)
        }
        if (ending.isNotEmpty()) publish()
    }

    companion object {
        private const val TAG = "DyskLink"
        const val MAX_PER_PHONE = 4
        const val KILL_AFTER_MS = 2_000L
        const val OUTPUT_BYTES = 32 * 1024
        const val MAX_PENDING_INPUT = 1 shl 20
        const val ENDED_HERE = "The terminal was ended on the PC."
    }
}

// One mouse and keyboard

/**
 * Numbers from the Mac are checked before they become pointer moves and key presses, as the Mac's RemoteLimits does:
 * key codes outside 0–127 and buttons outside 0–31 are dropped, movement is clamped to ±4000, and NaN or infinity
 * drops the event.
 */
object RemoteLimits {
    const val MAX_MOVE = 4000.0
    const val MAX_SCROLL = 4000.0

    fun keyCode(value: Int?): Int? = value?.takeIf { it in 0..127 }
    fun button(value: Int?): Int? = value?.takeIf { it in 0..31 }

    /** Missing is zero; NaN and infinity are refused. */
    fun delta(value: Double?, limit: Double): Double? {
        if (value == null) return 0.0
        if (!value.isFinite()) return null
        return value.coerceIn(-limit, limit)
    }

    /** A place along an edge, 0–1. Missing or broken values mean the middle. */
    fun fraction(value: Double?): Double = if (value == null || !value.isFinite()) 0.5 else value.coerceIn(0.0, 1.0)

    /** The event with every number made safe, or null when it can't be. */
    fun sanitize(event: RemoteEvent): RemoteEvent? = when (event.kind) {
        "move" -> {
            val dx = delta(event.dx, MAX_MOVE)
            val dy = delta(event.dy, MAX_MOVE)
            if (dx == null || dy == null) null else RemoteEvent("move", dx = dx, dy = dy, flags = event.flags)
        }
        "scroll" -> {
            val dx = delta(event.dx, MAX_SCROLL)
            val dy = delta(event.dy, MAX_SCROLL)
            if (dx == null || dy == null) null else RemoteEvent("scroll", dx = dx, dy = dy, flags = event.flags)
        }
        "down", "up" -> button(event.button ?: 0)?.let { RemoteEvent(event.kind, button = it, flags = event.flags) }
        "key", "flags" -> keyCode(event.keyCode ?: 0)?.let { RemoteEvent(event.kind, keyCode = it, keyDown = event.keyDown, flags = event.flags) }
        else -> null
    }
}
