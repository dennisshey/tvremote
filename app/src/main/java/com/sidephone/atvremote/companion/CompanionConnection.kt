package com.sidephone.atvremote.companion

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Companion frame types (see pyatv `companion/connection.py`). */
enum class FrameType(val value: Int) {
    Unknown(0),
    NoOp(1),
    PS_Start(3),
    PS_Next(4),
    PV_Start(5),
    PV_Next(6),
    U_OPACK(7),
    E_OPACK(8),
    P_OPACK(9),
    PA_Req(10),
    PA_Rsp(11),
    SessionStartRequest(16),
    SessionStartResponse(17),
    SessionData(18),
    FamilyIdentityRequest(32),
    FamilyIdentityResponse(33),
    FamilyIdentityUpdate(34);

    companion object {
        fun from(value: Int): FrameType = entries.firstOrNull { it.value == value } ?: Unknown
    }
}

interface CompanionConnectionListener {
    fun onFrame(frameType: FrameType, payload: ByteArray)
    fun onClosed(error: Throwable?)
}

/**
 * A raw TCP connection to an Apple TV's Companion port.
 *
 * Wire framing: `[frameType:1][length:3 big-endian][payload]`. Once encryption is
 * enabled the payload is ChaCha20-Poly1305 sealed (a 16-byte tag is appended and
 * included in `length`) with the 4-byte header as additional authenticated data.
 */
class CompanionConnection(
    private val host: String,
    private val port: Int,
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: java.io.OutputStream? = null
    private var readerThread: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile private var cipher: ChachaConnectionCipher? = null
    @Volatile var listener: CompanionConnectionListener? = null

    val isConnected: Boolean get() = socket?.isConnected == true && running.get()

    fun connect(timeoutMs: Int = 5000) {
        val sock = Socket()
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(host, port), timeoutMs)
        socket = sock
        input = DataInputStream(sock.getInputStream())
        output = sock.getOutputStream()
        running.set(true)
        readerThread = Thread({ readLoop() }, "companion-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun enableEncryption(outputKey: ByteArray, inputKey: ByteArray) {
        cipher = ChachaConnectionCipher(outputKey, inputKey)
    }

    @Synchronized
    fun send(frameType: FrameType, data: ByteArray) {
        val out = output ?: throw IOException("Not connected")
        var payloadLength = data.size
        val activeCipher = cipher
        if (activeCipher != null && data.isNotEmpty()) payloadLength += AUTH_TAG_LENGTH

        val header = byteArrayOf(
            frameType.value.toByte(),
            (payloadLength ushr 16 and 0xFF).toByte(),
            (payloadLength ushr 8 and 0xFF).toByte(),
            (payloadLength and 0xFF).toByte(),
        )

        val body = if (activeCipher != null && data.isNotEmpty()) {
            activeCipher.encrypt(data, header)
        } else {
            data
        }
        out.write(header)
        out.write(body)
        out.flush()
    }

    fun close() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    private fun readLoop() {
        val stream = input ?: return
        try {
            while (running.get()) {
                val header = ByteArray(HEADER_LENGTH)
                stream.readFully(header)
                val length = ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                val payload = ByteArray(length)
                if (length > 0) stream.readFully(payload)

                val decoded = try {
                    val activeCipher = cipher
                    if (activeCipher != null && payload.isNotEmpty()) {
                        activeCipher.decrypt(payload, header)
                    } else {
                        payload
                    }
                } catch (t: Throwable) {
                    // A decryption failure is fatal for the session's key stream.
                    throw IOException("Failed to decrypt frame", t)
                }

                listener?.onFrame(FrameType.from(header[0].toInt() and 0xFF), decoded)
            }
        } catch (t: Throwable) {
            if (running.get()) {
                running.set(false)
                listener?.onClosed(t)
            } else {
                listener?.onClosed(null)
            }
        }
    }

    companion object {
        private const val HEADER_LENGTH = 4
        private const val AUTH_TAG_LENGTH = 16
    }
}
