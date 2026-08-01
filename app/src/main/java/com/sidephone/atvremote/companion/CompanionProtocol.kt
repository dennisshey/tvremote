package com.sidephone.atvremote.companion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A decoded OPACK dictionary keyed by its (usually String) keys. */
typealias OpackDict = Map<Any?, Any?>

class CompanionProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Request/response layer on top of [CompanionConnection]. Auth frames (PS / PV)
 * are matched by frame type; regular OPACK requests are matched by an "_x"
 * transaction id so responses can arrive out of order.
 */
class CompanionProtocol(
    private val connection: CompanionConnection,
) : CompanionConnectionListener {

    fun interface EventListener {
        fun onEvent(name: String, content: OpackDict)
    }

    @Volatile var eventListener: EventListener? = null
    @Volatile var onConnectionClosed: ((Throwable?) -> Unit)? = null

    private val xid = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Any, CompletableDeferred<OpackDict>>()

    init {
        connection.listener = this
    }

    fun enableEncryption(outputKey: ByteArray, inputKey: ByteArray) =
        connection.enableEncryption(outputKey, inputKey)

    suspend fun exchangeAuth(
        frameType: FrameType,
        data: Map<String, Any?>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): OpackDict {
        val identifier = when (frameType) {
            FrameType.PS_Start -> FrameType.PS_Next
            FrameType.PV_Start -> FrameType.PV_Next
            else -> frameType
        }
        return exchange(frameType, data, identifier, timeoutMs)
    }

    suspend fun exchangeOpack(
        frameType: FrameType,
        data: Map<String, Any?>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): OpackDict {
        val id = xid.getAndIncrement()
        val payload = LinkedHashMap<String, Any?>(data).apply { put("_x", id) }
        return exchange(frameType, payload, id, timeoutMs)
    }

    /** Fire-and-forget OPACK message (used for event (un)subscription). */
    fun sendOpackEvent(frameType: FrameType, data: Map<String, Any?>) {
        val payload = LinkedHashMap<String, Any?>(data).apply { put("_x", xid.getAndIncrement()) }
        connection.send(frameType, Opack.pack(payload))
    }

    private suspend fun exchange(
        frameType: FrameType,
        data: Map<String, Any?>,
        identifier: Any,
        timeoutMs: Long,
    ): OpackDict {
        val deferred = CompletableDeferred<OpackDict>()
        pending[identifier] = deferred
        try {
            connection.send(frameType, Opack.pack(data))
            val response = withTimeout(timeoutMs) { deferred.await() }
            (response["_em"])?.let { throw CompanionProtocolException("Device error: $it") }
            return response
        } catch (e: TimeoutCancellationException) {
            throw CompanionProtocolException("Timed out waiting for $identifier", e)
        } finally {
            pending.remove(identifier)
        }
    }

    override fun onFrame(frameType: FrameType, payload: ByteArray) {
        val decoded = try {
            Opack.unpack(payload)
        } catch (t: Throwable) {
            return
        }
        val dict = decoded as? OpackDict ?: return

        when (frameType) {
            FrameType.PS_Start, FrameType.PS_Next, FrameType.PV_Start, FrameType.PV_Next ->
                pending.remove(frameType)?.complete(dict)

            FrameType.U_OPACK, FrameType.E_OPACK, FrameType.P_OPACK -> handleOpack(dict)

            else -> Unit
        }
    }

    private fun handleOpack(dict: OpackDict) {
        when ((dict["_t"] as? Number)?.toInt()) {
            MESSAGE_EVENT -> {
                val name = dict["_i"] as? String ?: return
                @Suppress("UNCHECKED_CAST")
                val content = (dict["_c"] as? OpackDict) ?: emptyMap<Any?, Any?>()
                eventListener?.onEvent(name, content)
            }
            MESSAGE_RESPONSE -> {
                val id = (dict["_x"] as? Number)?.toInt() ?: return
                pending.remove(id)?.complete(dict)
            }
        }
    }

    override fun onClosed(error: Throwable?) {
        pending.values.forEach { it.completeExceptionally(error ?: CompanionProtocolException("Connection closed")) }
        pending.clear()
        onConnectionClosed?.invoke(error)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
        private const val MESSAGE_EVENT = 1
        private const val MESSAGE_RESPONSE = 3
    }
}
