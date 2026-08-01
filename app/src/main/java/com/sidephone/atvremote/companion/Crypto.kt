package com.sidephone.atvremote.companion

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.agreement.X25519Agreement
import java.security.SecureRandom

/**
 * Thin, well-audited wrappers over BouncyCastle's lightweight crypto API for the
 * exact primitives the Companion/HAP handshake needs. Using the lightweight API
 * (instead of JCA providers) keeps behaviour identical across Android versions.
 */
object Crypto {

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha512(vararg parts: ByteArray): ByteArray {
        val digest = SHA512Digest()
        for (p in parts) digest.update(p, 0, p.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    /** HKDF-SHA512. An empty [salt] is treated by HAP as "no salt" (hash-length zeros). */
    fun hkdfSha512(salt: ByteArray, info: ByteArray, ikm: ByteArray, length: Int = 32): ByteArray {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        generator.generateBytes(out, 0, length)
        return out
    }

    /**
     * ChaCha20-Poly1305 encrypt. Returns ciphertext followed by the 16-byte tag,
     * matching how tvOS and pyatv frame AEAD output.
     */
    fun chachaEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    /** ChaCha20-Poly1305 decrypt. Throws if the tag does not verify. */
    fun chachaDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        var len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    // ---- Ed25519 (long-term identity signing) ----

    /** Derive the 32-byte Ed25519 public key from a 32-byte seed. */
    fun ed25519PublicFromSeed(seed: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }

    // ---- X25519 (ephemeral session key agreement) ----

    class X25519KeyPair(val privateKey: X25519PrivateKeyParameters, val publicKey: ByteArray)

    fun x25519Generate(): X25519KeyPair {
        val priv = X25519PrivateKeyParameters(randomBytes(32), 0)
        return X25519KeyPair(priv, priv.generatePublicKey().encoded)
    }

    fun x25519Agree(privateKey: X25519PrivateKeyParameters, peerPublic: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(privateKey)
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), out, 0)
        return out
    }
}

/**
 * ChaCha20-Poly1305 stream cipher for a Companion connection: a 12-byte
 * little-endian counter nonce that increments independently for the send
 * ("out") and receive ("in") directions once encryption is enabled.
 */
class ChachaConnectionCipher(private val outKey: ByteArray, private val inKey: ByteArray) {
    private var outCounter = 0L
    private var inCounter = 0L

    fun encrypt(data: ByteArray, aad: ByteArray): ByteArray {
        val nonce = counterNonce(outCounter)
        outCounter++
        return Crypto.chachaEncrypt(outKey, nonce, data, aad)
    }

    fun decrypt(data: ByteArray, aad: ByteArray): ByteArray {
        val nonce = counterNonce(inCounter)
        inCounter++
        return Crypto.chachaDecrypt(inKey, nonce, data, aad)
    }

    private fun counterNonce(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        var v = counter
        for (i in 0 until 8) { nonce[i] = (v and 0xFF).toByte(); v = v ushr 8 }
        return nonce
    }
}
