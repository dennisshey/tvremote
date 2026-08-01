package com.sidephone.atvremote.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Companion pairing/session crypto.
 *
 * Every expected value below is a reference vector produced by the authoritative
 * Python implementations the wire format comes from — pyatv (OPACK/TLV8),
 * `srptools` (SRP-6a) and `cryptography` (HKDF/ChaCha20-Poly1305/Ed25519) — and
 * cross-checked against this Kotlin port. If a change breaks byte-compatibility
 * with a real Apple TV, one of these will fail.
 */
class CompanionCryptoTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun setPrivate(obj: Any, name: String, value: Any) {
        val f = obj.javaClass.getDeclaredField(name).apply { isAccessible = true }
        f.set(obj, value)
    }

    @Test
    fun srpStep2_matchesReference() {
        val seed = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val salt = unhex("11223344112233441122334411223344")
        val b = unhex("abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd")

        val srp = SrpAuthHandler()
        setPrivate(srp, "signingSeed", seed)
        setPrivate(srp, "authPublic", Crypto.ed25519PublicFromSeed(seed))
        val (a, m1) = srp.step2(b, salt, "3149")

        assertEquals(
            "203e5bc55133e8d2917aac4b7d78b82c725d9085efc46dd33c449ebfb09816be" +
                "e2ae6e218c9873a99a1e01bd0e7ca47edca05889319bfebb30bdea7701cba20c" +
                "6419a59b7c6ee0f80c59fcb9ec9e43da57885775e6d343288c7bdca766361e51" +
                "1c8d7f1ac4c521c2fbdd22e145fb83fcf0722949ab5520de4963661c5d27658a" +
                "010cb50514a6d8ff10a8448117545c4a9cd12002b1bf0f81775f0426ad609d2e" +
                "aaee21736fc6d766a9e8977a4b467c1a67a4de79e6c185b555028633cd028b39" +
                "17fc6f0d8e07871b2d531ea6e4f9f5bbed5cf6aaee6f1f429126c6bac9644df2" +
                "64bf61fcf5a8c5a42c99d42f1bf87c89488649991be2bcdce6c1d87a6a463a79" +
                "98150a20b33e7fbbcaa366e8a77c5529421dadf8132ef97e09d30eadae3149b8" +
                "f15025354b7c6148fbd754c9ca6caead032f4e322fc93049805f5ea415bf535d" +
                "eab62f603340cc2d9bd99874c9bdf2c6a07d1963bfb79ee527fc252db63696b0" +
                "0ec434bdc69e9158ba02ac9dbe0ff85e5263c57f178dd4d529eaa23940ed2e47",
            hex(a),
        )
        assertEquals(
            "a36cd2038c8eef750d98da7eea7feab94d6a790ba4787632f096c5caca27fcee" +
                "53a5d1a4eaeb10c7d98aa3c6250d5e04539e465c93e107d12306cc3d3e32d851",
            hex(m1),
        )
        val sharedKey = srp.javaClass.getDeclaredField("srpSharedKey").apply { isAccessible = true }.get(srp) as ByteArray
        assertEquals(
            "628b2a705e155bfa2072f7e063d921f1056f758f41600199f583bbd781fe0e4d" +
                "cc8b746364ae11f006404a98e76e78dc67ed373c0b7b12f619ba2a516ebfdc36",
            hex(sharedKey),
        )
    }

    @Test
    fun ed25519_matchesReference() {
        val seed = unhex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f")
        val msg = unhex("6465766963652d696e666f2d746f2d7369676e")
        assertEquals("2543b92ff1095511476adc8369db6ddc933665a11978dda1404ee1066ca9559d",
            hex(Crypto.ed25519PublicFromSeed(seed)))
        val sig = Crypto.ed25519Sign(seed, msg)
        assertEquals(
            "0bc5b7bdbbd28ec09513934b9c5745634c992dd15ad33bf5afc8d9229a8bacb3" +
                "ffa859220bc664a949412a4a9b80e7c414edbd8c4ef9faa859969303e1328405",
            hex(sig),
        )
        assertTrue(Crypto.ed25519Verify(Crypto.ed25519PublicFromSeed(seed), msg, sig))
    }

    @Test
    fun chacha20Poly1305_matchesReference() {
        val key = unhex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
        val nonce = unhex("0000000050532d4d73673035") // 4 zero bytes + "PS-Msg05"
        val plaintext = unhex("68656c6c6f206170706c6520747620636f6d70616e696f6e")
        val aad = unhex("0800002a")
        val expected = "70e374a9f1112644e7e3919d7ec2ac2ea7cf7bda8469a4c903b5b2514f3f3359fba83f5dc8a91bab"
        val ct = Crypto.chachaEncrypt(key, nonce, plaintext, aad)
        assertEquals(expected, hex(ct))
        assertArrayEquals(plaintext, Crypto.chachaDecrypt(key, nonce, ct, aad))
    }

    @Test
    fun hkdfSha512_matchesReference() {
        val ikm = unhex(
            "f53379377908557fd72bc451851b756a34b2d6b6baeeb19c85a46a6dcc303677" +
                "6ccd1cab2bead6014d59bec2aac2f2b04b7b6ec2b7623697b742ac515125ef4f"
        )
        val out = Crypto.hkdfSha512(
            "Pair-Setup-Encrypt-Salt".toByteArray(),
            "Pair-Setup-Encrypt-Info".toByteArray(),
            ikm,
        )
        assertEquals("e647ba4e5e42b527959db689f7e0bac2d1fd25055765d197a9d0d0b0fe30593b", hex(out))
    }

    @Test
    fun tlv8_write_fragmentsLongValues() {
        val items = linkedMapOf(
            Tlv8.SEQ_NO to byteArrayOf(0x03),
            Tlv8.PUBLIC_KEY to ByteArray(10) { it.toByte() },
            Tlv8.PROOF to ByteArray(300) { 0xFF.toByte() }, // > 255 -> two fragments
        )
        val written = Tlv8.write(items)
        // SeqNo (1B), PublicKey (10B), Proof split 255 + 45 with the tag repeated.
        assertTrue(written.size == 3 + 12 + (2 + 255) + (2 + 45))
        val readBack = Tlv8.read(written)
        assertArrayEquals(ByteArray(300) { 0xFF.toByte() }, readBack.getValue(Tlv8.PROOF))
    }

    @Test
    fun opack_roundtrip_and_decode() {
        // Decode a payload packed by pyatv.
        val decoded = Opack.unpack(unhex("e4425f69455f68696443425f740a425f63e2455f6842745309a10e425f78302a"))
        @Suppress("UNCHECKED_CAST")
        val map = decoded as Map<Any?, Any?>
        assertEquals("_hidC", map["_i"])
        assertEquals(2L, map["_t"])
        assertEquals(42L, map["_x"])

        // Our encoder output must round-trip.
        val original = linkedMapOf<String, Any?>(
            "_i" to "_hidC", "_t" to 2, "_c" to linkedMapOf("_hBtS" to 1, "_hidC" to 6),
        )
        val repacked = Opack.unpack(Opack.pack(original)) as Map<*, *>
        assertEquals("_hidC", repacked["_i"])
        assertEquals(6L, (repacked["_c"] as Map<*, *>)["_hidC"])
    }
}
