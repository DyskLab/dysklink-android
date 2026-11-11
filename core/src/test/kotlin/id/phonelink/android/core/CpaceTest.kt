package id.phonelink.android.core

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CPace255 against draft-irtf-cfrg-cpace-21 Appendix B.1, and pairing version 3 built on it. */
class CpaceTest {
    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun stringFunctionsMatchTheDraft() {
        assertEquals("00", Cpace.prependLen(ByteArray(0)).hex())
        assertEquals("0431323334", Cpace.prependLen("1234".toByteArray()).hex())
        assertEquals("7f", Cpace.prependLen(ByteArray(127) { it.toByte() }).copyOf(1).hex())
        assertEquals("8001", Cpace.prependLen(ByteArray(128) { it.toByte() }).copyOf(2).hex())
        assertEquals("043132333401350003363738", Cpace.lvCat("1234".toByteArray(), "5".toByteArray(), ByteArray(0), "678".toByteArray()).hex())
        assertEquals(
            "03313233065061727479410332333406506172747942",
            Cpace.transcriptIr("123".toByteArray(), "PartyA".toByteArray(), "234".toByteArray(), "PartyB".toByteArray()).hex(),
        )
    }

    @Test
    fun draftTestVectors() {
        val prs = "Password".toByteArray()
        val ci = hex("0b415f696e69746961746f720b425f726573706f6e646572")
        val sid = hex("7e4b4791d6a8ef019b936c79fb7f2c57")
        val genStr = Cpace.generatorString(Cpace.DSI, prs, ci, sid)
        assertEquals(170, genStr.size)
        assertEquals("0843506163653235350850617373776f72646d", genStr.copyOf(19).hex())
        val g = Cpace.calculateGenerator(prs, ci, sid)
        assertEquals("d04bf6d41f6a289632a2e929fa29bebd51092512a7829fdde7d314b62f05a73f", g.hex())
        val ya = hex("21b4f4bd9e64ed355c3eb676a28ebedaf6d8f17bdc365995b319097153044080")
        val yb = hex("848b0779ff415f0af4ea14df9dd1d3c29ac41d836c7808896c4eba19c51ac40a")
        val bigYa = Cpace.x25519(ya, g)
        val bigYb = Cpace.x25519(yb, g)
        assertEquals("1d13c89278cdadd826f6d8d7f887701430f8380ddc17611cdd6dc989ce0c9f32", bigYa.hex())
        assertEquals("248cccf6d5cdc3646f0ad593f9e6cef4e69d4945f8372e623512ecea32185623", bigYb.hex())
        val k = Cpace.x25519(ya, bigYb)
        assertContentEquals(k, Cpace.x25519(yb, bigYa))
        assertEquals("5b067effbdc0b2a0e1d907b21ebb25cfedb96a852179a847c37e43ee71322c6b", k.hex())
        assertEquals(
            "6e19b875f7a561d6b3ca3dbb9ef42ac55de3e717881018204b8922b4d5e53bb2" +
                "aa82c300bea7b65d2b671da71922ddf6472301b79bc270adfa8bf413285f2263",
            Cpace.isk(sid, k, bigYa, "ADa".toByteArray(), bigYb, "ADb".toByteArray()).hex(),
        )
    }

    @Test
    fun lowOrderPointsGiveTheNeutralElement() {
        val s = hex("af46e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449aff")
        val cases = listOf(
            "0000000000000000000000000000000000000000000000000000000000000000" to null,
            "0100000000000000000000000000000000000000000000000000000000000000" to null,
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f" to null,
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800" to null,
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157" to null,
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f" to null,
            "daffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to "d8e2c776bbacd510d09fd9278b7edcd25fc5ae9adfba3b6e040e8d3b71b21806",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f" to null,
            "dbffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to "c85c655ebe8be44ba9c0ffde69f2fe10194458d137f09bbff725ce58803cdb38",
            "d9ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to "db64dafa9b8fdd136914e61461935fe92aa372cb056314e1231bc4ec12417456",
            "cdeb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b880" to "e062dcd5376d58297be2618c7498f55baa07d7e03184e8aada20bca28888bf7a",
            "4c9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f11d7" to "993c6ad11c4c29da9a56f7691fd0ff8d732e49de6250b6c2e80003ff4629a175",
        )
        for ((u, q) in cases) {
            val out = Cpace.x25519(s, hex(u))
            if (q == null) assertTrue(Cpace.isIdentity(out), "u=$u") else assertEquals(q, out.hex(), "u=$u")
        }
    }

    @Test
    fun fieldArithmeticSanity() {
        val p = Cpace.P
        assertEquals(BigInteger.ZERO, Cpace.inv0(BigInteger.ZERO))
        val x = BigInteger("1234567890123456789012345678901234567890")
        assertEquals(BigInteger.ONE, x.multiply(Cpace.inv0(x)).mod(p))
        // 2 is Elligator's non-square for this field; 4 is a square, and so is -1 (p = 5 mod 8).
        assertFalse(Cpace.isSquare(BigInteger.valueOf(2)))
        assertTrue(Cpace.isSquare(BigInteger.valueOf(4)))
        assertTrue(Cpace.isSquare(p.subtract(BigInteger.ONE)))
        assertTrue(Cpace.isSquare(BigInteger.ZERO))
        // Every output of the map is on the curve: x^3 + A x^2 + x is a square.
        val a = BigInteger.valueOf(486662)
        for (i in 0 until 50) {
            val u = BigInteger(Cpace.randomBytes(32)).abs().mod(p)
            val xu = Cpace.elligator2(u)
            assertTrue(Cpace.isSquare(xu.pow(3).add(a.multiply(xu.pow(2))).add(xu).mod(p)))
        }
        // u = 0 and u = p (the same field element) map to the same point on the curve. 1 + 2u^2 is never 0 here,
        // since -1/2 isn't a square mod p.
        val xz = Cpace.elligator2(BigInteger.ZERO)
        assertEquals(xz, Cpace.elligator2(p))
        assertTrue(Cpace.isSquare(xz.pow(3).add(a.multiply(xz.pow(2))).add(xz).mod(p)))
        assertFalse(Cpace.isSquare(p.subtract(Cpace.inv0(BigInteger.valueOf(2)))))
        // Round trip, with bit 255 ignored on decoding.
        val bytes = Cpace.encodeU(x)
        assertEquals(x, Cpace.decodeU(bytes))
        bytes[31] = (bytes[31].toInt() or 0x80).toByte()
        assertEquals(x, Cpace.decodeU(bytes))
    }

    /** The same fixed inputs as `Cpace.selfTest` in the Mac app (and a Python reference); both must print these. */
    @Test
    fun matchesTheMacImplementation() {
        val nonceT = ByteArray(16) { it.toByte() }
        val nonceS = ByteArray(16) { (it + 16).toByte() }
        val ya = ByteArray(32) { (it + 32).toByte() }
        val yb = ByteArray(32) { (it + 64).toByte() }
        val ci = Pake.channel("ab".repeat(32), "cd".repeat(32))
        val shower = Pake.Shower("123456", nonceT, ci, nonceS, ya.copyOf())
        assertEquals(CROSS_YA, shower.yaPublic.hex())
        val typer = Pake.Typer(nonceT)
        assertTrue(typer.received(shower.message))
        val accept = assertNotNull(typer.answer("123456", ci, yb.copyOf()))
        assertEquals(CROSS_YB, Pake.decode(accept.y, 32)!!.hex())
        assertEquals(CROSS_CONFIRM_T, Pake.decode(accept.confirm, 32)!!.hex())
        val reply = assertNotNull(shower.check(accept))
        assertEquals(CROSS_CONFIRM_S, Pake.decode(reply.confirm, 32)!!.hex())
        assertTrue(typer.confirms(reply))

        // The pieces, for finding where two implementations part ways.
        val sid = nonceT + nonceS
        val g = Cpace.calculateGenerator("123456".toByteArray(), ci, sid)
        assertEquals(CROSS_G, g.hex())
        val k = Cpace.x25519(ya, Cpace.x25519(yb, g))
        assertEquals(CROSS_K, k.hex())
        assertEquals(CROSS_ISK, Cpace.isk(sid, k, hex(CROSS_YA), ByteArray(0), hex(CROSS_YB), ByteArray(0)).hex())
    }

    private fun exchange(showCode: String, typeCode: String, typerCi: ByteArray, showerCi: ByteArray): Boolean {
        val typer = Pake.Typer()
        val nonceT = assertNotNull(Pake.requestNonce(typer.request))
        val shower = Pake.Shower(showCode, nonceT, showerCi)
        assertTrue(typer.received(shower.message))
        val accept = assertNotNull(typer.answer(typeCode, typerCi))
        val reply = shower.check(accept) ?: return false
        return typer.confirms(reply)
    }

    @Test
    fun rightCodePairsAndWrongCodeDoesNot() {
        val ci = Pake.channel("aa", "bb")
        assertTrue(exchange("123456", "123456", ci, ci))
        assertFalse(exchange("123456", "123457", ci, ci))
    }

    @Test
    fun aDeviceInTheMiddleCantRelay() {
        // Each leg sees the middle's certificate, so the channels differ even with the right code.
        assertFalse(exchange("123456", "123456", Pake.channel("phone", "attacker"), Pake.channel("attacker", "mac")))
        assertFalse(exchange("123456", "123456", Pake.channel("aa", "bb"), Pake.channel("bb", "aa")))
    }

    @Test
    fun theSameCodeCanBeTypedAgain() {
        val ci = Pake.channel("aa", "bb")
        val typer = Pake.Typer()
        val shower = Pake.Shower("654321", Pake.requestNonce(typer.request)!!, ci)
        assertTrue(typer.received(shower.message))
        assertNull(shower.check(typer.answer("111111", ci)!!))
        typer.wrongCode()
        val first = typer.answer("654321", ci)!!
        val second = typer.answer("654321", ci)!!
        // A fresh secret each try.
        assertFalse(first.y == second.y)
        val reply = assertNotNull(shower.check(second))
        assertTrue(typer.confirms(reply))
        assertNotNull(shower.check(first))
    }

    @Test
    fun lowOrderSharesAreRefused() {
        val ci = Pake.channel("aa", "bb")
        val typer = Pake.Typer()
        val shower = Pake.Shower("123456", Pake.requestNonce(typer.request)!!, ci)
        val zero = Pake.encode(ByteArray(32))
        val one = Pake.encode(hex("0100000000000000000000000000000000000000000000000000000000000000"))
        // From T: Yb of low order.
        val confirm = Pake.encode(ByteArray(32))
        assertNull(shower.check(PakeMessage(Pake.VERSION, y = zero, confirm = confirm)))
        assertNull(shower.check(PakeMessage(Pake.VERSION, y = one, confirm = confirm)))
        // From S: Ya of low order, so T has nothing to send.
        assertTrue(typer.received(PakeMessage(Pake.VERSION, nonce = Pake.encode(ByteArray(16)), y = zero)))
        assertNull(typer.answer("123456", ci))
        // Wrong sizes and versions.
        assertFalse(typer.received(PakeMessage(Pake.VERSION, nonce = Pake.encode(ByteArray(15)), y = zero)))
        assertFalse(typer.received(PakeMessage(2, nonce = Pake.encode(ByteArray(16)), y = zero)))
        assertNull(Pake.requestNonce(null))
        assertNull(Pake.requestNonce(PakeMessage(Pake.VERSION)))
    }

    @Test
    fun aConfirmationThatDoesntMatchIsRefused() {
        val ci = Pake.channel("aa", "bb")
        val typer = Pake.Typer()
        val shower = Pake.Shower("123456", Pake.requestNonce(typer.request)!!, ci)
        typer.received(shower.message)
        val accept = typer.answer("123456", ci)!!
        assertFalse(typer.confirms(PakeMessage(Pake.VERSION, confirm = Pake.encode(ByteArray(32)))))
        assertFalse(typer.confirms(null))
        // S's confirmation isn't T's: reflecting T's own value back fails.
        assertFalse(typer.confirms(PakeMessage(Pake.VERSION, confirm = accept.confirm)))
        assertTrue(typer.confirms(shower.check(accept)))
    }

    @Test
    fun pakeTravelsInTheEnvelope() {
        val envelope = Envelope(MessageType.PAIR_PAKE, pake = PakeMessage(1, nonce = "bm9uY2U=", y = "eQ=="))
        val json = protocolJson.encodeToString(Envelope.serializer(), envelope)
        assertEquals("""{"type":"pair.pake","pake":{"v":1,"nonce":"bm9uY2U=","y":"eQ=="}}""", json)
        val parsed = protocolJson.decodeFromString(Envelope.serializer(), """{"type":"pair.accept","pake":{"v":1,"y":"eQ==","confirm":"Yw==","later":true}}""")
        assertEquals("Yw==", parsed.pake?.confirm)
    }

    private companion object {
        const val CROSS_G = "596bf2b3635255f8ddad24bcca56d5466c83731d14569819aa6c55757f612937"
        const val CROSS_YA = "8c989051b56d84665212c5560f098d75bc34f4a6ae2b9f6cf234c867ddc4884a"
        const val CROSS_YB = "f13ef81a083b9ac532e99f3d6b4f3ffd3066b569b583f14fd2ddeceb8acd9a58"
        const val CROSS_K = "54d8ecaaafded0a9aac8db60d07cd9e1fc04a3985fbe96abc8b82af420f2ef59"
        const val CROSS_ISK = "823079120c1016676b1861386fb7a0946f212de3f515773cc16c326690c368d4" +
            "75fb4c887ec5536518b2e453b44120febc634298df19b5200f5fd7fbce990c5e"
        const val CROSS_CONFIRM_T = "1ef6ce4bac0cc89dade05defb21f9327de2966f9d1dcf42f6e7c9e96223d6a7c"
        const val CROSS_CONFIRM_S = "98e16d6a01ecef6c8cb0a650ca813027d4d405bc23b0af5a1b2efdf541a3b39f"
    }
}
