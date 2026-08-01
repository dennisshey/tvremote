package com.sidephone.atvremote.companion

/**
 * Long-term pairing credentials produced by pair-setup and consumed by
 * pair-verify on every later connection.
 *
 *  - [ltpk]     Apple TV's long-term public key (Ed25519).
 *  - [ltsk]     Our long-term secret seed (Ed25519 private seed, 32 bytes).
 *  - [atvId]    Apple TV's pairing identifier.
 *  - [clientId] Our pairing identifier (a UUID string, as bytes).
 *
 * The `ltpk:ltsk:atvId:clientId` hex string produced by [serialize] is wire
 * compatible with the credentials string used by pyatv, so a pairing done in
 * either tool can be reused by the other.
 */
data class HapCredentials(
    val ltpk: ByteArray,
    val ltsk: ByteArray,
    val atvId: ByteArray,
    val clientId: ByteArray,
) {
    fun serialize(): String = listOf(ltpk, ltsk, atvId, clientId).joinToString(":") { it.toHex() }

    companion object {
        fun parse(value: String): HapCredentials {
            val parts = value.split(":")
            require(parts.size == 4) { "Malformed credentials string" }
            return HapCredentials(
                ltpk = parts[0].hexToBytes(),
                ltsk = parts[1].hexToBytes(),
                atvId = parts[2].hexToBytes(),
                clientId = parts[3].hexToBytes(),
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is HapCredentials && serialize() == other.serialize()

    override fun hashCode(): Int = serialize().hashCode()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Odd-length hex string" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
