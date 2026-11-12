package id.phonelink.android.core

/**
 * Stops the 6-digit code from being guessed. After [Pairing.MAX_ATTEMPTS] wrong codes from one device, that device
 * waits 60 seconds before it can ask again, doubling each time up to an hour. All devices together have the same
 * limit, because a new certificate is a new device ID and costs nothing. Strikes are forgotten after a quiet day.
 */
class PairingGuard(private val clock: () -> Long = System::currentTimeMillis) {
    private class Strikes {
        var wrong = 0
        var lockouts = 0
        var until = 0L
        var last = 0L
    }

    private val peers = HashMap<String, Strikes>()
    private val everyone = Strikes()

    /** How long [peerId] has to wait before pairing, or 0 when it may pair now. */
    @Synchronized
    fun waitMs(peerId: String): Long {
        val now = clock()
        val until = maxOf(peers[peerId]?.until ?: 0L, everyone.until)
        return (until - now).coerceAtLeast(0L)
    }

    /** Counts a wrong code. Returns true when pairing is now locked, so the code on screen must be closed. */
    @Synchronized
    fun wrong(peerId: String): Boolean {
        val now = clock()
        if (peers.size > MAX_PEERS) peers.entries.removeIf { now - it.value.last > FORGET_MS || it.value.until < now }
        val peer = strike(peers.getOrPut(peerId) { Strikes() }, now)
        val all = strike(everyone, now)
        return peer || all
    }

    /** The right code was typed: that device starts over. */
    @Synchronized
    fun succeeded(peerId: String) {
        peers.remove(peerId)
        everyone.wrong = 0
    }

    private fun strike(strikes: Strikes, now: Long): Boolean {
        if (now - strikes.last > FORGET_MS) {
            strikes.wrong = 0
            strikes.lockouts = 0
        }
        strikes.last = now
        strikes.wrong++
        if (strikes.wrong < Pairing.MAX_ATTEMPTS) return false
        strikes.wrong = 0
        strikes.until = now + cooldownMs(strikes.lockouts)
        strikes.lockouts++
        return true
    }

    companion object {
        const val FIRST_COOLDOWN_MS = 60_000L
        const val MAX_COOLDOWN_MS = 60 * 60_000L
        private const val FORGET_MS = 24 * 60 * 60_000L
        private const val MAX_PEERS = 1_000

        /** 60 seconds for the first lockout, doubling each time, never more than an hour. */
        fun cooldownMs(lockouts: Int): Long = (FIRST_COOLDOWN_MS shl lockouts.coerceIn(0, 10)).coerceAtMost(MAX_COOLDOWN_MS)

        /** What the other device is told while it has to wait. */
        fun waitMessage(waitMs: Long): String {
            val minutes = ((waitMs + 59_999) / 60_000).coerceAtLeast(1)
            return "Too many wrong codes. Try pairing again in $minutes minute${if (minutes == 1L) "" else "s"}."
        }
    }
}

/** Messages decided while a lock is held, sent once it's released, so a peer that stops reading can't block the lock. */
internal class Outgoing {
    private val sends = ArrayList<Pair<PeerConnection, Envelope>>()
    private val closes = ArrayList<PeerConnection>()

    fun send(connection: PeerConnection?, envelope: Envelope) {
        if (connection != null) sends += connection to envelope
    }

    fun close(connection: PeerConnection?) {
        if (connection != null) closes += connection
    }

    private val afterwards = ArrayList<() -> Unit>()

    /** Work for after the lock is released, such as CPace math, run by [flush] once the sends are queued. */
    fun afterwards(block: () -> Unit) {
        afterwards += block
    }

    val isEmpty: Boolean get() = sends.isEmpty() && closes.isEmpty() && afterwards.isEmpty()

    fun flush() {
        // Queued, not waited on: a peer that stopped reading must not hold up whoever held the lock.
        sends.forEach { (connection, envelope) -> connection.post(envelope) }
        closes.forEach { it.close() }
        afterwards.forEach { it() }
    }
}

/**
 * The showing side's CPace state for one code on screen: a single [Pake.Shower]. A `pair.request` with a new nonce
 * only gets a new one after a counted wrong code from that peer, or once every [RENEW_MS]; other requests are
 * ignored, so a peer can't make this side do the math (or hold its lock) for every request it sends. Building
 * happens outside the lock: [start] and [renew] reserve, [install] puts the result in place. Not thread-safe;
 * callers hold their lock.
 */
internal class ShowerSlot(private val clock: () -> Long = System::currentTimeMillis) {
    class Plan internal constructor(internal val generation: Long, val code: String, val nonceT: ByteArray, val ci: ByteArray) {
        fun build(): Pake.Shower = Pake.Shower(code, nonceT, ci)
    }

    sealed interface Renewal {
        /** The same request again: resend this. */
        class Resend(val message: PakeMessage) : Renewal
        /** Build this outside the lock, then [install] it and send its message. */
        class Build(val plan: Plan) : Renewal
        /** Too soon, or that nonce is already being answered. */
        data object Ignore : Renewal
    }

    var shower: Pake.Shower? = null
        private set
    private var nonceT: ByteArray? = null
    private var reservedAt = 0L
    private var renewable = false
    private var generation = 0L

    /** The first Shower for a new code. Drops whatever was there. */
    fun start(code: String, nonce: ByteArray, ci: ByteArray): Plan {
        clear()
        return reserve(code, nonce, ci)
    }

    /** A `pair.request` with [nonce] while [code] shows. */
    fun renew(code: String, nonce: ByteArray, ci: ByteArray): Renewal {
        val current = nonceT
        if (current != null && java.security.MessageDigest.isEqual(current, nonce)) {
            return shower?.takeIf { it.sameRequest(nonce) }?.let { Renewal.Resend(it.message) } ?: Renewal.Ignore
        }
        if (!renewable && clock() - reservedAt < RENEW_MS) return Renewal.Ignore
        return Renewal.Build(reserve(code, nonce, ci))
    }

    /** Puts a Shower built from [plan] in place. False (and [built] is closed) when the prompt moved on meanwhile. */
    fun install(plan: Plan, built: Pake.Shower): Boolean {
        if (plan.generation != generation) {
            built.close()
            return false
        }
        shower?.close()
        shower = built
        return true
    }

    /** A wrong code was counted: the next new nonce may have a fresh Shower. */
    fun wrongCode() {
        renewable = true
    }

    fun clear() {
        shower?.close()
        shower = null
        nonceT = null
        renewable = false
        generation++
    }

    private fun reserve(code: String, nonce: ByteArray, ci: ByteArray): Plan {
        generation++
        nonceT = nonce.copyOf()
        reservedAt = clock()
        renewable = false
        return Plan(generation, code, nonce.copyOf(), ci)
    }

    companion object {
        const val RENEW_MS = 2_000L
    }
}
