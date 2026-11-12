package id.phonelink.android.core

import java.math.BigInteger
import java.security.MessageDigest

/** The former BigInteger CPace field math, kept only as a reference for [Field25519]. Not constant time. */
object CpaceReference {
    val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A = BigInteger.valueOf(486662)
    private val A24 = BigInteger.valueOf(121665)
    private val Z = BigInteger.valueOf(2)
    private val P_MINUS_2 = P.subtract(BigInteger.valueOf(2))
    private val LEGENDRE = P.subtract(BigInteger.ONE).shiftRight(1)

    fun calculateGenerator(prs: ByteArray, ci: ByteArray, sid: ByteArray): ByteArray {
        val genStr = Cpace.generatorString(Cpace.DSI, prs, ci, sid)
        val hash = MessageDigest.getInstance("SHA-512").digest(genStr).copyOf(Cpace.POINT_BYTES)
        return encodeU(elligator2(decodeU(hash)))
    }

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

    fun inv0(x: BigInteger): BigInteger = x.mod(P).modPow(P_MINUS_2, P)

    fun decodeU(bytes: ByteArray): BigInteger {
        require(bytes.size == Cpace.POINT_BYTES)
        val big = ByteArray(Cpace.POINT_BYTES) { bytes[Cpace.POINT_BYTES - 1 - it] }
        big[0] = (big[0].toInt() and 0x7f).toByte()
        return BigInteger(1, big)
    }

    fun encodeU(x: BigInteger): ByteArray {
        val value = x.mod(P).toByteArray()
        val out = ByteArray(Cpace.POINT_BYTES)
        for (i in 0 until minOf(Cpace.POINT_BYTES, value.size)) out[i] = value[value.size - 1 - i]
        return out
    }

    fun x25519(scalar: ByteArray, point: ByteArray): ByteArray {
        val clamped = scalar.copyOf()
        clamped[0] = (clamped[0].toInt() and 248).toByte()
        clamped[31] = ((clamped[31].toInt() and 127) or 64).toByte()
        val k = BigInteger(1, ByteArray(Cpace.POINT_BYTES) { clamped[Cpace.POINT_BYTES - 1 - it] })
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
}
