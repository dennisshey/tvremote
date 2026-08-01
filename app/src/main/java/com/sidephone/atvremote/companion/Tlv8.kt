package com.sidephone.atvremote.companion

import java.io.ByteArrayOutputStream

/**
 * TLV8 encoding used by the HomeKit (HAP) pairing process that the Companion
 * protocol is built on.
 *
 * A TLV item is `[tag:1][length:1][value:length]`. Values longer than 255 bytes
 * are split into multiple consecutive items with the same tag; readers must
 * concatenate them back together. This mirrors the reference behaviour in
 * pyatv's `hap_tlv8`.
 */
object Tlv8 {

    // Standard HAP TLV tags (see HomeKit Accessory Protocol specification).
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val SEQ_NO = 0x06
    const val ERROR = 0x07
    const val BACK_OFF = 0x08
    const val CERTIFICATE = 0x09
    const val SIGNATURE = 0x0A
    const val PERMISSIONS = 0x0B
    const val FRAGMENT_DATA = 0x0C
    const val FRAGMENT_LAST = 0x0D
    const val NAME = 0x11
    const val FLAGS = 0x13

    /** Encode a map of tag -> value into TLV8 bytes. */
    fun write(items: Map<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, value) in items) {
            if (value.isEmpty()) {
                // A zero-length item is still legal (e.g. some flags).
                out.write(tag)
                out.write(0)
                continue
            }
            var pos = 0
            while (pos < value.size) {
                val chunk = minOf(255, value.size - pos)
                out.write(tag)
                out.write(chunk)
                out.write(value, pos, chunk)
                pos += chunk
            }
        }
        return out.toByteArray()
    }

    /** Decode TLV8 bytes into a map of tag -> concatenated value. */
    fun read(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArrayOutputStream>()
        var pos = 0
        while (pos < data.size) {
            val tag = data[pos].toInt() and 0xFF
            val length = data[pos + 1].toInt() and 0xFF
            val value = data.copyOfRange(pos + 2, pos + 2 + length)
            result.getOrPut(tag) { ByteArrayOutputStream() }.write(value)
            pos += 2 + length
        }
        return result.mapValues { it.value.toByteArray() }
    }
}
