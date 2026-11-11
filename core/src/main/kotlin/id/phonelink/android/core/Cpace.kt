package id.phonelink.android.core

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * CPace (draft-irtf-cfrg-cpace) with X25519, Elligator 2, and SHA-512: the "CPace255" cipher suite.
 * Field math uses BigInteger and X25519 is the RFC 7748 Montgomery ladder, since Android 8 has no XDH.
 */
object Cpace {
    val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A = BigInteger.valueOf(486662)
    private val A24 = BigInteger.valueOf(121665)
    /** Elligator 2's non-square for Curve25519. */
    private val Z = BigInteger.valueOf(2)
    private val P_MINUS_2 = P.subtract(BigInteger.valueOf(2))
    private val LEGENDRE = P.subtract(BigInteger.ONE).shiftRight(1)
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
        val u = decodeU(hash)
        hash.fill(0)
        return encodeU(elligator2(u))
    }

    /** RFC 9380 map_to_curve_elligator2 for Curve25519 (J = 486662, K = 1, Z = 2). Only x is needed. */
    fun elligator2(u: BigInteger): BigInteger {
        val r = u.mod(P)
        val denominator = Z.multiply(r).multiply(r).add(BigInteger.ONE).mod(P)
        var x1 = A.negate().multiply(inv0(denominator)).mod(P)
        if (x1.signum() == 0) x1 = A.negate().mod(P)
        val gx1 = x1.multiply(x1).mod(P).multiply(x1).add(A.multiply(x1).multiply(x1)).add(x1).mod(P)
        val x2 = x1.negate().subtract(A).mod(P)
        return if (isSquare(gx1)) x1 else x2
    }

    fun isSquare(x: BigInteger): Boolean {
        val legendre = x.mod(P).modPow(LEGENDRE, P)
        return legendre.signum() == 0 || legendre == BigInteger.ONE
    }

    /** 0 for 0, otherwise the inverse. */
    fun inv0(x: BigInteger): BigInteger = x.mod(P).modPow(P_MINUS_2, P)

    /** RFC 7748 decodeUCoordinate: little-endian, bit 255 ignored. Not reduced, as the ladder works mod p anyway. */
    fun decodeU(bytes: ByteArray): BigInteger {
        require(bytes.size == POINT_BYTES)
        val big = ByteArray(POINT_BYTES) { bytes[POINT_BYTES - 1 - it] }
        big[0] = (big[0].toInt() and 0x7f).toByte()
        return BigInteger(1, big)
    }

    fun encodeU(x: BigInteger): ByteArray {
        val value = x.mod(P).toByteArray()
        val out = ByteArray(POINT_BYTES)
        // toByteArray is big-endian and may carry a leading zero.
        for (i in 0 until minOf(POINT_BYTES, value.size)) out[i] = value[value.size - 1 - i]
        return out
    }

    /** RFC 7748 X25519. All zeros out means a low-order point, which callers must treat as failure. */
    fun x25519(scalar: ByteArray, point: ByteArray): ByteArray {
        require(scalar.size == POINT_BYTES && point.size == POINT_BYTES)
        val clamped = scalar.copyOf()
        clamped[0] = (clamped[0].toInt() and 248).toByte()
        clamped[31] = ((clamped[31].toInt() and 127) or 64).toByte()
        val k = BigInteger(1, ByteArray(POINT_BYTES) { clamped[POINT_BYTES - 1 - it] })
        clamped.fill(0)
        val x1 = decodeU(point).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val bit = if (k.testBit(t)) 1 else 0
            swap = swap xor bit
            if (swap == 1) {
                x2 = x3.also { x3 = x2 }
                z2 = z3.also { z3 = z2 }
            }
            swap = bit
            val a = x2.add(z2)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb)
            val c = x3.add(z3)
            val d = x3.subtract(z3)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val sum = da.add(cb)
            x3 = sum.multiply(sum).mod(P)
            val diff = da.subtract(cb)
            z3 = x1.multiply(diff.multiply(diff).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) {
            x2 = x3.also { x3 = x2 }
            z2 = z3.also { z3 = z2 }
        }
        return encodeU(x2.multiply(inv0(z2)))
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
