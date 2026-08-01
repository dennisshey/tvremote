package com.sidephone.atvremote.companion

import java.math.BigInteger
import java.util.UUID

/**
 * SRP-6a (SHA-512, 3072-bit group) plus the HAP key-derivation steps used by the
 * Companion pairing handshake.
 *
 * This is a byte-exact port of pyatv's `auth/hap_srp.py`, which itself delegates
 * the SRP math to the `srptools` library. The padding and hashing conventions
 * below (minimal-length big-endian for hashed integers, PAD-to-N-width for `u`
 * and `k`, raw salt bytes, `K = SHA512(S)`) match `srptools` exactly — getting
 * any of them wrong makes the Apple TV reject the proof, so they are reproduced
 * deliberately rather than "cleaned up".
 */
class SrpAuthHandler {

    /** Our pairing identifier (client id). Stable for the life of this handler. */
    val pairingId: ByteArray = UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)

    // Long-term identity (pair-setup only).
    private lateinit var signingSeed: ByteArray   // Ed25519 private seed == credentials.ltsk
    private lateinit var authPublic: ByteArray    // Ed25519 public key

    // Ephemeral X25519 key (pair-verify only).
    private lateinit var verifyKeyPair: Crypto.X25519KeyPair

    // SRP / derived secrets.
    private lateinit var srpSharedKey: ByteArray  // K = SHA512(S)
    private lateinit var psEncryptKey: ByteArray  // pair-setup session key
    private lateinit var verifyShared: ByteArray  // X25519 shared secret

    private val nBytes = N.toBytesUnsigned()
    private val padWidth = nBytes.size

    // ---------- Pair setup (SRP) ----------

    fun setupInitialize() {
        signingSeed = Crypto.randomBytes(32)
        authPublic = Crypto.ed25519PublicFromSeed(signingSeed)
    }

    /**
     * SRP step 2: given the Apple TV's public key B and salt, compute our public
     * key A and the client proof M1.
     */
    fun step2(atvPubKey: ByteArray, atvSalt: ByteArray, pin: String): Pair<ByteArray, ByteArray> {
        val a = BigInteger(1, signingSeed)
        val aPub = G.modPow(a, N)

        val b = BigInteger(1, atvPubKey)
        require(b.mod(N) != BigInteger.ZERO) { "Invalid server public key" }

        // x = H(salt | H(user | ":" | password))
        val inner = Crypto.sha512(("$USERNAME:$pin").toByteArray(Charsets.UTF_8))
        val x = BigInteger(1, Crypto.sha512(atvSalt, inner))

        // k = H(N | PAD(g))
        val k = BigInteger(1, Crypto.sha512(nBytes, pad(G)))

        // u = H(PAD(A) | PAD(B))
        val u = BigInteger(1, Crypto.sha512(pad(aPub), pad(b)))

        // S = (B - k * g^x) ^ (a + u * x) mod N ; K = H(S)
        val v = G.modPow(x, N)
        val base = b.subtract(k.multiply(v))
        val exp = a.add(u.multiply(x))
        val s = base.modPow(exp, N)
        srpSharedKey = Crypto.sha512(s.toBytesUnsigned())

        // M1 = H( H(N) XOR H(g) | H(user) | salt | A | B | K )
        val hn = BigInteger(1, Crypto.sha512(nBytes))
        val hg = BigInteger(1, Crypto.sha512(G.toBytesUnsigned()))
        val hUser = BigInteger(1, Crypto.sha512(USERNAME.toByteArray(Charsets.UTF_8)))
        val m1 = Crypto.sha512(
            hn.xor(hg).toBytesUnsigned(),
            hUser.toBytesUnsigned(),
            atvSalt,
            aPub.toBytesUnsigned(),
            b.toBytesUnsigned(),
            srpSharedKey,
        )

        return Pair(aPub.toBytesUnsigned(), m1)
    }

    /** SRP step 3: build the encrypted device-info payload sent in M5. */
    fun step3(displayName: String?): ByteArray {
        val iosDeviceX = Crypto.hkdfSha512(
            "Pair-Setup-Controller-Sign-Salt".toByteArray(),
            "Pair-Setup-Controller-Sign-Info".toByteArray(),
            srpSharedKey,
        )
        psEncryptKey = Crypto.hkdfSha512(
            "Pair-Setup-Encrypt-Salt".toByteArray(),
            "Pair-Setup-Encrypt-Info".toByteArray(),
            srpSharedKey,
        )

        val deviceInfo = iosDeviceX + pairingId + authPublic
        val deviceSignature = Crypto.ed25519Sign(signingSeed, deviceInfo)

        val tlv = linkedMapOf(
            Tlv8.IDENTIFIER to pairingId,
            Tlv8.PUBLIC_KEY to authPublic,
            Tlv8.SIGNATURE to deviceSignature,
        )
        if (displayName != null) {
            tlv[Tlv8.NAME] = Opack.pack(linkedMapOf("name" to displayName))
        }

        return Crypto.chachaEncrypt(psEncryptKey, paddedNonce("PS-Msg05"), Tlv8.write(tlv), null)
    }

    /** SRP step 4: decrypt the device's M6 payload and assemble credentials. */
    fun step4(encryptedData: ByteArray): HapCredentials {
        val decrypted = Crypto.chachaDecrypt(psEncryptKey, paddedNonce("PS-Msg06"), encryptedData, null)
        val tlv = Tlv8.read(decrypted)
        return HapCredentials(
            ltpk = tlv.getValue(Tlv8.PUBLIC_KEY),
            ltsk = signingSeed,
            atvId = tlv.getValue(Tlv8.IDENTIFIER),
            clientId = pairingId,
        )
    }

    // ---------- Pair verify (X25519) ----------

    /** Generate the ephemeral X25519 key and return our public key for M1. */
    fun verifyInitialize(): ByteArray {
        verifyKeyPair = Crypto.x25519Generate()
        return verifyKeyPair.publicKey
    }

    /**
     * Verify the device's identity proof and produce our own encrypted proof.
     * Throws [SecurityException] if the Apple TV's signature does not verify.
     */
    fun verify1(credentials: HapCredentials, serverPubKey: ByteArray, encrypted: ByteArray): ByteArray {
        verifyShared = Crypto.x25519Agree(verifyKeyPair.privateKey, serverPubKey)

        val sessionKey = Crypto.hkdfSha512(
            "Pair-Verify-Encrypt-Salt".toByteArray(),
            "Pair-Verify-Encrypt-Info".toByteArray(),
            verifyShared,
        )

        val decrypted = Crypto.chachaDecrypt(sessionKey, paddedNonce("PV-Msg02"), encrypted, null)
        val tlv = Tlv8.read(decrypted)
        val identifier = tlv.getValue(Tlv8.IDENTIFIER)
        val signature = tlv.getValue(Tlv8.SIGNATURE)

        if (!identifier.contentEquals(credentials.atvId)) {
            throw SecurityException("Apple TV identity mismatch")
        }

        val info = serverPubKey + identifier + verifyKeyPair.publicKey
        if (!Crypto.ed25519Verify(credentials.ltpk, info, signature)) {
            throw SecurityException("Apple TV signature verification failed")
        }

        val deviceInfo = verifyKeyPair.publicKey + credentials.clientId + serverPubKey
        val deviceSignature = Crypto.ed25519Sign(credentials.ltsk, deviceInfo)
        val responseTlv = Tlv8.write(
            linkedMapOf(
                Tlv8.IDENTIFIER to credentials.clientId,
                Tlv8.SIGNATURE to deviceSignature,
            )
        )
        return Crypto.chachaEncrypt(sessionKey, paddedNonce("PV-Msg03"), responseTlv, null)
    }

    /** Derive the (output, input) stream-cipher keys for the verified session. */
    fun verify2(salt: String, outputInfo: String, inputInfo: String): Pair<ByteArray, ByteArray> {
        val outputKey = Crypto.hkdfSha512(salt.toByteArray(), outputInfo.toByteArray(), verifyShared)
        val inputKey = Crypto.hkdfSha512(salt.toByteArray(), inputInfo.toByteArray(), verifyShared)
        return Pair(outputKey, inputKey)
    }

    // ---------- helpers ----------

    /** Right-justify an integer to N's byte width with leading zeros. */
    private fun pad(value: BigInteger): ByteArray {
        val raw = value.toBytesUnsigned()
        if (raw.size >= padWidth) return raw
        return ByteArray(padWidth - raw.size) + raw
    }

    private fun paddedNonce(label: String): ByteArray {
        val ascii = label.toByteArray(Charsets.US_ASCII)
        return ByteArray(12 - ascii.size) + ascii
    }

    companion object {
        private const val USERNAME = "Pair-Setup"

        private val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
                "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
                "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
                "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
                "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
                "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33" +
                "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864" +
                "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2" +
                "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
        private val G = BigInteger.valueOf(5)
    }
}

/** Unsigned, minimal-length big-endian encoding (matches Python's int_to_bytes). */
private fun BigInteger.toBytesUnsigned(): ByteArray {
    if (signum() == 0) return byteArrayOf(0)
    val raw = toByteArray()
    return if (raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
}
