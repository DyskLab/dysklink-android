package id.phonelink.android.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * CPace (draft-irtf-cfrg-cpace) with X25519, Elligator 2, and SHA-512: the "CPace255" cipher suite.
 * Field math is [Field25519], constant time, and X25519 is its Montgomery ladder, since Android 8 has no XDH.
 */
object Cpace {
    val DSI = "CPace255".toByteArray()
    /** SHA-512's input block size. */
    private const val S_IN_BYTES = 128
    const val POINT_BYTES = 32

    /** The length in LEB128, then the bytes. */
    fun prependLen(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size + 3)
        var length = data.size
        while (true) {
            out.write(if (length < 128) length else (length and 0x7f) or 0x80)
            length = length ushr 7
            if (length == 0) break
        }
        out.write(data)
        return out.toByteArray()
    }

    fun lvCat(vararg parts: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        parts.forEach { out.write(prependLen(it)) }
        return out.toByteArray()
    }

    fun generatorString(dsi: ByteArray, prs: ByteArray, ci: ByteArray, sid: ByteArray, sInBytes: Int = S_IN_BYTES): ByteArray {
        val zpad = maxOf(0, sInBytes - 1 - prependLen(prs).size - prependLen(dsi).size)
        return lvCat(dsi, prs, ByteArray(zpad), ci, sid)
    }

    /** The generator for this code, channel, and session, as a u coordinate. */
    fun calculateGenerator(prs: ByteArray, ci: ByteArray, sid: ByteArray): ByteArray {
        val genStr = generatorString(DSI, prs, ci, sid)
        val hash = MessageDigest.getInstance("SHA-512").digest(genStr).copyOf(POINT_BYTES)
        genStr.fill(0)
        val u = Field25519.unpack(hash)
        hash.fill(0)
        val x = Field25519.elligator2(u)
        u.fill(0)
        return Field25519.pack(x).also { x.fill(0) }
    }

    /** RFC 9380 map_to_curve_elligator2 on a u decoded as RFC 7748 does (bit 255 ignored), encoded canonically. */
    fun elligator2(u: ByteArray): ByteArray = Field25519.pack(Field25519.elligator2(Field25519.unpack(u)))

    /** RFC 7748 X25519. All zeros out means a low-order point, which callers must treat as failure. */
    fun x25519(scalar: ByteArray, point: ByteArray): ByteArray {
        require(scalar.size == POINT_BYTES && point.size == POINT_BYTES)
        return Field25519.scalarMult(scalar, point)
    }

    fun isIdentity(point: ByteArray): Boolean = point.fold(0) { acc, byte -> acc or byte.toInt() } == 0

    fun transcriptIr(ya: ByteArray, adA: ByteArray, yb: ByteArray, adB: ByteArray): ByteArray = lvCat(ya, adA) + lvCat(yb, adB)

    /** ISK = SHA-512(lv_cat(DSI || "_ISK", sid, K) || transcript_ir(Ya, ADa, Yb, ADb)). */
    fun isk(sid: ByteArray, k: ByteArray, ya: ByteArray, adA: ByteArray, yb: ByteArray, adB: ByteArray): ByteArray {
        val input = lvCat(DSI + "_ISK".toByteArray(), sid, k) + transcriptIr(ya, adA, yb, adB)
        return MessageDigest.getInstance("SHA-512").digest(input).also { input.fill(0) }
    }

    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    fun sampleScalar(): ByteArray = randomBytes(POINT_BYTES)
}

/**
 * Pairing version 3: CPace on the typed code, bound to both TLS certificates. T types the code and sent
 * `pair.request`, S shows it. S is CPace's A (sends Ya first), T is B. ADa and ADb are empty.
 */
object Pake {
    const val VERSION = 1
    const val NONCE_BYTES = 16
    const val CONFIRM_BYTES = 32
    private val CHANNEL_LABEL = "dysklink-pair-v3".toByteArray()
    private val CONFIRM_T = "dysklink-confirm-T".toByteArray()
    private val CONFIRM_S = "dysklink-confirm-S".toByteArray()
    private val EMPTY = ByteArray(0)

    /** CI from the fingerprints (lowercase hex, as device IDs are) each side sees on this connection. */
    fun channel(typingFingerprint: String, showingFingerprint: String): ByteArray =
        Cpace.lvCat(CHANNEL_LABEL, typingFingerprint.toByteArray(), showingFingerprint.toByteArray())

    fun confirm(isk: ByteArray, label: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(isk, "HmacSHA512"))
        return mac.doFinal(label).copyOf(CONFIRM_BYTES)
    }

    fun confirmT(isk: ByteArray) = confirm(isk, CONFIRM_T)
    fun confirmS(isk: ByteArray) = confirm(isk, CONFIRM_S)

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Null unless it's base64 of exactly [length] bytes. */
    fun decode(text: String?, length: Int): ByteArray? {
        text ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
        return bytes.takeIf { it.size == length }
    }

    /** What `pair.request` must carry; anything else is an older app. */
    fun requestNonce(pake: PakeMessage?): ByteArray? = pake?.takeIf { it.v == VERSION }?.let { decode(it.nonce, NONCE_BYTES) }

    private fun sessionId(nonceT: ByteArray, nonceS: ByteArray) = nonceT + nonceS

    /** The showing side. Keeps ya and Ya for as long as its code shows, so the same code can be typed again. */
    class Shower(
        private val code: String,
        private val nonceT: ByteArray,
        private val ci: ByteArray,
        val nonceS: ByteArray = Cpace.randomBytes(NONCE_BYTES),
        private val ya: ByteArray = Cpace.sampleScalar(),
    ) {
        private val sid = sessionId(nonceT, nonceS)
        val yaPublic: ByteArray = run {
            val g = Cpace.calculateGenerator(code.toByteArray(), ci, sid)
            Cpace.x25519(ya, g).also { g.fill(0) }
        }

        fun sameRequest(nonce: ByteArray) = MessageDigest.isEqual(nonce, nonceT)

        /** `pair.pake`: nonceS and Ya. */
        val message: PakeMessage get() = PakeMessage(VERSION, nonce = encode(nonceS), y = encode(yaPublic))

        /** Checks T's `pair.accept`. The reply to send when confirmT is right, null for a wrong code or bad point. */
        fun check(accept: PakeMessage): PakeMessage? {
            if (accept.v != VERSION) return null
            val yb = decode(accept.y, Cpace.POINT_BYTES) ?: return null
            val confirm = decode(accept.confirm, CONFIRM_BYTES) ?: return null
            val k = Cpace.x25519(ya, yb)
            if (Cpace.isIdentity(k)) return null
            val isk = Cpace.isk(sid, k, yaPublic, EMPTY, yb, EMPTY)
            k.fill(0)
            val expected = confirmT(isk)
            val right = MessageDigest.isEqual(expected, confirm)
            val reply = if (right) PakeMessage(VERSION, confirm = encode(confirmS(isk))) else null
            isk.fill(0)
            expected.fill(0)
            return reply
        }

        fun close() = ya.fill(0)
    }

    /** The typing side. A fresh yb for every code typed. */
    class Typer(val nonceT: ByteArray = Cpace.randomBytes(NONCE_BYTES)) {
        private var nonceS: ByteArray? = null
        private var ya: ByteArray? = null
        private var expectedConfirm: ByteArray? = null

        /** For `pair.request`. */
        val request: PakeMessage get() = PakeMessage(VERSION, nonce = encode(nonceT))

        val ready: Boolean get() = ya != null

        /** Takes S's `pair.pake`. False if it isn't valid. */
        fun received(pake: PakeMessage?): Boolean {
            if (pake == null || pake.v != VERSION) return false
            val nonce = decode(pake.nonce, NONCE_BYTES) ?: return false
            val point = decode(pake.y, Cpace.POINT_BYTES) ?: return false
            nonceS = nonce
            ya = point
            expectedConfirm?.fill(0)
            expectedConfirm = null
            return true
        }

        /** The `pair.accept` for this code, or null when S's Ya is missing or a low-order point. */
        fun answer(code: String, ci: ByteArray, yb: ByteArray = Cpace.sampleScalar()): PakeMessage? {
            val nonce = nonceS ?: return null
            val ya = ya ?: return null
            val sid = sessionId(nonceT, nonce)
            try {
                val g = Cpace.calculateGenerator(code.toByteArray(), ci, sid)
                val ybPublic = Cpace.x25519(yb, g)
                g.fill(0)
                val k = Cpace.x25519(yb, ya)
                if (Cpace.isIdentity(k)) return null
                val isk = Cpace.isk(sid, k, ya, EMPTY, ybPublic, EMPTY)
                k.fill(0)
                expectedConfirm?.fill(0)
                expectedConfirm = confirmS(isk)
                val message = PakeMessage(VERSION, y = encode(ybPublic), confirm = encode(confirmT(isk)))
                isk.fill(0)
                return message
            } finally {
                yb.fill(0)
            }
        }

        /** True only for S's `pair.accept` with the right confirmS for the last code sent. */
        fun confirms(accept: PakeMessage?): Boolean {
            val expected = expectedConfirm ?: return false
            if (accept == null || accept.v != VERSION) return false
            val confirm = decode(accept.confirm, CONFIRM_BYTES) ?: return false
            return MessageDigest.isEqual(expected, confirm)
        }

        /** A wrong code: the next try starts again. */
        fun wrongCode() {
            expectedConfirm?.fill(0)
            expectedConfirm = null
        }

        fun close() = wrongCode()
    }
}

/**
 * GF(2^255 - 19) in 16 signed limbs of 16 bits, the TweetNaCl layout (as the Mac app's FieldElement). No branch or
 * memory index depends on a secret value: selection is by mask, and the only branches are on loop counters and
 * public exponents.
 */
internal object Field25519 {
    const val LIMBS = 16
    private val A24 = longArrayOf(0xdb41, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private val CURVE_A = small(486662)
    private val ONE = small(1)
    /** (p - 1) / 2 = 2^254 - 10, little-endian. A public constant. */
    private val LEGENDRE = ByteArray(32) { 0xff.toByte() }.also {
        it[0] = 0xf6.toByte()
        it[31] = 0x3f
    }

    fun small(value: Int): LongArray = LongArray(LIMBS).also {
        it[0] = (value and 0xffff).toLong()
        it[1] = ((value ushr 16) and 0xffff).toLong()
    }

    /** RFC 7748 decoding: little-endian, bit 255 ignored. Values from p up reduce later. */
    fun unpack(bytes: ByteArray): LongArray {
        require(bytes.size == 32)
        val o = LongArray(LIMBS)
        for (i in 0 until LIMBS) o[i] = (bytes[2 * i].toLong() and 0xff) + ((bytes[2 * i + 1].toLong() and 0xff) shl 8)
        o[15] = o[15] and 0x7fff
        return o
    }

    fun carry(o: LongArray) {
        for (i in 0 until LIMBS) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            // 2^256 = 38 mod p. The branch is on the index, not the value.
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    /** Swaps p and q when bit is 1, leaves them when it's 0, without branching on it. */
    fun swap(p: LongArray, q: LongArray, bit: Int) {
        val mask = -(bit.toLong() and 1)
        for (i in 0 until LIMBS) {
            val t = mask and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    /** b when bit is 1, otherwise a, without branching on it. */
    fun select(a: LongArray, b: LongArray, bit: Int): LongArray {
        val mask = -(bit.toLong() and 1)
        return LongArray(LIMBS) { a[it] xor (mask and (a[it] xor b[it])) }
    }

    /** The canonical encoding, fully reduced mod p. */
    fun pack(n: LongArray): ByteArray {
        val t = n.copyOf()
        val m = LongArray(LIMBS)
        carry(t)
        carry(t)
        carry(t)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val borrow = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            // No borrow: t was at least p, so keep t - p.
            swap(t, m, 1 - borrow)
        }
        val out = ByteArray(32)
        for (i in 0 until LIMBS) {
            out[2 * i] = (t[i] and 0xff).toByte()
            out[2 * i + 1] = ((t[i] shr 8) and 0xff).toByte()
        }
        t.fill(0)
        m.fill(0)
        return out
    }

    fun add(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until LIMBS) o[i] = a[i] + b[i]
    }

    fun sub(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until LIMBS) o[i] = a[i] - b[i]
    }

    /** o = a * b; o may be a or b. */
    fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until LIMBS) {
            val ai = a[i]
            for (j in 0 until LIMBS) t[i + j] += ai * b[j]
        }
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until LIMBS) o[i] = t[i]
        carry(o)
        carry(o)
        t.fill(0)
    }

    fun square(o: LongArray, a: LongArray) = mul(o, a, a)

    /** x to a public exponent, 32 little-endian bytes; branching on its bits reveals nothing secret. */
    fun power(x: LongArray, exponent: ByteArray, topBit: Int = 255): LongArray {
        val result = ONE.copyOf()
        for (bit in topBit downTo 0) {
            square(result, result)
            if ((exponent[bit / 8].toInt() shr (bit % 8)) and 1 == 1) mul(result, result, x)
        }
        return result
    }

    /** The inverse, and 0 for 0 (inv0 in RFC 9380): x^(p - 2). */
    fun invert(x: LongArray): LongArray {
        val c = x.copyOf()
        for (a in 253 downTo 0) {
            square(c, c)
            if (a != 2 && a != 4) mul(c, c, x)
        }
        return c
    }

    /** 1 for zero, else 0, without branching. */
    fun isZero(x: LongArray): Int {
        val bytes = pack(x)
        var d = 0
        for (b in bytes) d = d or (b.toInt() and 0xff)
        return ((d - 1) ushr 31) and 1
    }

    /** 1 when the two have the same value mod p, else 0. */
    fun equal(a: LongArray, b: LongArray): Int {
        val x = pack(a)
        val y = pack(b)
        var d = 0
        for (i in x.indices) d = d or ((x[i].toInt() xor y[i].toInt()) and 0xff)
        return ((d - 1) ushr 31) and 1
    }

    /** 1 when x is a square in the field, 0 included (is_square in RFC 9380), else 0. */
    fun isSquare(x: LongArray): Int {
        val legendre = power(x, LEGENDRE, 253)
        return isZero(legendre) or equal(legendre, ONE)
    }

    /** RFC 9380 map_to_curve_elligator2 for Curve25519 (J = 486662, K = 1, Z = 2), x only, branch-free. */
    fun elligator2(u: LongArray): LongArray {
        val zero = LongArray(LIMBS)
        val t = LongArray(LIMBS)
        square(t, u)
        add(t, t, t) // Z u^2 with Z = 2
        add(t, t, ONE)
        carry(t)
        val x1 = invert(t)
        val minusA = LongArray(LIMBS)
        sub(minusA, zero, CURVE_A)
        carry(minusA)
        mul(x1, x1, minusA)
        val chosen = select(x1, minusA, isZero(x1))
        // x^3 + A x^2 + x = ((x + A) x + 1) x
        val gx = LongArray(LIMBS)
        add(gx, chosen, CURVE_A)
        mul(gx, gx, chosen)
        add(gx, gx, ONE)
        mul(gx, gx, chosen)
        val x2 = LongArray(LIMBS)
        sub(x2, zero, chosen)
        sub(x2, x2, CURVE_A)
        carry(x2)
        carry(x2)
        val result = select(x2, chosen, isSquare(gx))
        listOf(t, x1, gx, x2, chosen).forEach { it.fill(0) }
        return result
    }

    /** RFC 7748 X25519 with a constant-time ladder (TweetNaCl's crypto_scalarmult). */
    fun scalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
        val z = scalar.copyOf()
        z[0] = (z[0].toInt() and 248).toByte()
        z[31] = ((z[31].toInt() and 127) or 64).toByte()
        val x = unpack(point)
        val a = ONE.copyOf()
        val b = x.copyOf()
        val c = LongArray(LIMBS)
        val d = ONE.copyOf()
        val e = LongArray(LIMBS)
        val f = LongArray(LIMBS)
        for (i in 254 downTo 0) {
            val r = (z[i ushr 3].toInt() ushr (i and 7)) and 1
            swap(a, b, r)
            swap(c, d, r)
            add(e, a, c)
            sub(a, a, c)
            add(c, b, d)
            sub(b, b, d)
            square(d, e)
            square(f, a)
            mul(a, c, a)
            mul(c, b, e)
            add(e, a, c)
            sub(a, a, c)
            square(b, a)
            sub(c, d, f)
            mul(a, c, A24)
            add(a, a, d)
            mul(c, c, a)
            mul(a, d, f)
            mul(d, b, x)
            square(b, e)
            swap(a, b, r)
            swap(c, d, r)
        }
        val inverse = invert(c)
        mul(a, a, inverse)
        val out = pack(a)
        listOf(x, a, b, c, d, e, f, inverse).forEach { it.fill(0) }
        z.fill(0)
        return out
    }
}
