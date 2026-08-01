package com.sidephone.atvremote.companion

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * OPACK serialization, the format Apple uses to carry Companion payloads.
 *
 * This is a port of pyatv's `support/opack.py`. Two deliberate choices:
 *  - [pack] does NOT emit the optional back-reference (UID) compression. Emitting
 *    every value in full produces a slightly larger but fully valid OPACK stream
 *    that tvOS decodes identically, and it removes a whole class of encoder bugs.
 *  - [unpack] DOES resolve back-references, because tvOS uses them in responses.
 */
object Opack {

    fun pack(data: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        packInto(data, out)
        return out.toByteArray()
    }

    private fun packInto(data: Any?, out: ByteArrayOutputStream) {
        when (data) {
            null -> out.write(0x04)
            is Boolean -> out.write(if (data) 0x01 else 0x02)
            is UUID -> {
                out.write(0x05)
                out.write(uuidToBytes(data))
            }
            is Int -> packLong(data.toLong(), out)
            is Long -> packLong(data, out)
            is Double -> {
                out.write(0x36)
                out.write(littleEndian(java.lang.Double.doubleToLongBits(data), 8))
            }
            is Float -> packInto(data.toDouble(), out)
            is String -> packString(data, out)
            is ByteArray -> packBytes(data, out)
            is List<*> -> {
                out.write(0xD0 + minOf(data.size, 0xF))
                data.forEach { packInto(it, out) }
                if (data.size >= 0xF) out.write(0x03)
            }
            is Map<*, *> -> {
                out.write(0xE0 + minOf(data.size, 0xF))
                for ((k, v) in data) {
                    packInto(k, out)
                    packInto(v, out)
                }
                if (data.size >= 0xF) out.write(0x03)
            }
            else -> throw IllegalArgumentException("Cannot OPACK-encode ${data.javaClass}")
        }
    }

    private fun packLong(value: Long, out: ByteArrayOutputStream) {
        when {
            value in 0 until 0x28 -> out.write((value + 8).toInt())
            value <= 0xFF -> { out.write(0x30); out.write(littleEndian(value, 1)) }
            value <= 0xFFFF -> { out.write(0x31); out.write(littleEndian(value, 2)) }
            value <= 0xFFFFFFFFL -> { out.write(0x32); out.write(littleEndian(value, 4)) }
            else -> { out.write(0x33); out.write(littleEndian(value, 8)) }
        }
    }

    private fun packString(value: String, out: ByteArrayOutputStream) {
        val enc = value.toByteArray(Charsets.UTF_8)
        when {
            enc.size <= 0x20 -> out.write(0x40 + enc.size)
            enc.size <= 0xFF -> { out.write(0x61); out.write(littleEndian(enc.size.toLong(), 1)) }
            enc.size <= 0xFFFF -> { out.write(0x62); out.write(littleEndian(enc.size.toLong(), 2)) }
            enc.size <= 0xFFFFFF -> { out.write(0x63); out.write(littleEndian(enc.size.toLong(), 3)) }
            else -> { out.write(0x64); out.write(littleEndian(enc.size.toLong(), 4)) }
        }
        out.write(enc)
    }

    private fun packBytes(value: ByteArray, out: ByteArrayOutputStream) {
        when {
            value.size <= 0x20 -> out.write(0x70 + value.size)
            value.size <= 0xFF -> { out.write(0x91); out.write(littleEndian(value.size.toLong(), 1)) }
            value.size <= 0xFFFF -> { out.write(0x92); out.write(littleEndian(value.size.toLong(), 2)) }
            value.size <= 0xFFFFFFFFL -> { out.write(0x93); out.write(littleEndian(value.size.toLong(), 4)) }
            else -> { out.write(0x94); out.write(littleEndian(value.size.toLong(), 8)) }
        }
        out.write(value)
    }

    /** Decode an OPACK payload. Only the value is returned; trailing bytes are ignored. */
    fun unpack(data: ByteArray): Any? {
        val cursor = Cursor(data, 0)
        return unpackValue(cursor, ArrayList())
    }

    private class Cursor(val data: ByteArray, var pos: Int) {
        fun u(offset: Int = 0): Int = data[pos + offset].toInt() and 0xFF
    }

    private fun unpackValue(c: Cursor, objects: MutableList<Any?>): Any? {
        val tag = c.u()
        var addToObjects = true
        val value: Any?

        when {
            tag == 0x01 -> { value = true; addToObjects = false; c.pos += 1 }
            tag == 0x02 -> { value = false; addToObjects = false; c.pos += 1 }
            tag == 0x04 -> { value = null; addToObjects = false; c.pos += 1 }
            tag == 0x05 -> { value = bytesToUuid(c.data, c.pos + 1); c.pos += 17 }
            tag == 0x06 -> { value = readLE(c.data, c.pos + 1, 8); c.pos += 9 }
            tag in 0x08..0x2F -> { value = (tag - 8).toLong(); addToObjects = false; c.pos += 1 }
            tag == 0x35 -> {
                value = java.lang.Float.intBitsToFloat(readLE(c.data, c.pos + 1, 4).toInt()).toDouble()
                c.pos += 5
            }
            tag == 0x36 -> {
                value = java.lang.Double.longBitsToDouble(readLE(c.data, c.pos + 1, 8))
                c.pos += 9
            }
            (tag and 0xF0) == 0x30 -> {
                val n = 1 shl (tag and 0xF)
                value = readLE(c.data, c.pos + 1, n)
                c.pos += 1 + n
            }
            tag in 0x40..0x60 -> {
                val len = tag - 0x40
                value = String(c.data, c.pos + 1, len, Charsets.UTF_8)
                c.pos += 1 + len
            }
            tag in 0x61..0x64 -> {
                val n = tag and 0xF
                val len = readLE(c.data, c.pos + 1, n).toInt()
                value = String(c.data, c.pos + 1 + n, len, Charsets.UTF_8)
                c.pos += 1 + n + len
            }
            tag in 0x70..0x90 -> {
                val len = tag - 0x70
                value = c.data.copyOfRange(c.pos + 1, c.pos + 1 + len)
                c.pos += 1 + len
            }
            tag in 0x91..0x94 -> {
                val n = 1 shl ((tag and 0xF) - 1)
                val len = readLE(c.data, c.pos + 1, n).toInt()
                value = c.data.copyOfRange(c.pos + 1 + n, c.pos + 1 + n + len)
                c.pos += 1 + n + len
            }
            (tag and 0xF0) == 0xD0 -> {
                val count = tag and 0xF
                val list = ArrayList<Any?>()
                c.pos += 1
                if (count == 0xF) {
                    while (c.u() != 0x03) list.add(unpackValue(c, objects))
                    c.pos += 1
                } else {
                    repeat(count) { list.add(unpackValue(c, objects)) }
                }
                value = list
                addToObjects = false
            }
            (tag and 0xE0) == 0xE0 -> {
                val count = tag and 0xF
                val map = LinkedHashMap<Any?, Any?>()
                c.pos += 1
                if (count == 0xF) {
                    while (c.u() != 0x03) {
                        val k = unpackValue(c, objects)
                        val v = unpackValue(c, objects)
                        map[k] = v
                    }
                    c.pos += 1
                } else {
                    repeat(count) {
                        val k = unpackValue(c, objects)
                        val v = unpackValue(c, objects)
                        map[k] = v
                    }
                }
                value = map
                addToObjects = false
            }
            tag in 0xA0..0xC0 -> { value = objects[tag - 0xA0]; addToObjects = false; c.pos += 1 }
            tag in 0xC1..0xC4 -> {
                val n = tag - 0xC0
                val uid = readLE(c.data, c.pos + 1, n).toInt()
                value = objects[uid]
                addToObjects = false
                c.pos += 1 + n
            }
            else -> throw IllegalArgumentException("Unknown OPACK tag 0x${tag.toString(16)}")
        }

        if (addToObjects && objects.none { valuesEqual(it, value) }) objects.add(value)
        return value
    }

    private fun valuesEqual(a: Any?, b: Any?): Boolean =
        if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b

    // ---- little-endian helpers ----

    private fun littleEndian(value: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        var v = value
        for (i in 0 until size) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return out
    }

    private fun readLE(data: ByteArray, offset: Int, size: Int): Long {
        var v = 0L
        for (i in size - 1 downTo 0) {
            v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return v
    }

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val out = ByteArray(16)
        var hi = uuid.mostSignificantBits
        var lo = uuid.leastSignificantBits
        for (i in 7 downTo 0) { out[i] = (hi and 0xFF).toByte(); hi = hi ushr 8 }
        for (i in 15 downTo 8) { out[i] = (lo and 0xFF).toByte(); lo = lo ushr 8 }
        return out
    }

    private fun bytesToUuid(data: ByteArray, offset: Int): UUID {
        var hi = 0L
        var lo = 0L
        for (i in 0 until 8) hi = (hi shl 8) or (data[offset + i].toLong() and 0xFF)
        for (i in 8 until 16) lo = (lo shl 8) or (data[offset + i].toLong() and 0xFF)
        return UUID(hi, lo)
    }
}
