package com.sidephone.atvremote.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Identity this device announces to the Apple TV. Values are cosmetic (they show
 * up in the TV's list of connected remotes) except [clientId] handling, which is
 * taken from the pairing credentials at connect time.
 */
data class LocalDeviceInfo(
    val name: String = "SidePhone Remote",
    val model: String = "SidePhone SP-01",
    val uniqueId: String = "sidephone-sp01",
)

/**
 * High-level Companion client: connect + verify, run the session handshake tvOS
 * needs before it will accept input, and expose button/media commands.
 *
 * All network work is confined to [Dispatchers.IO]. One instance owns one TCP
 * connection; create a fresh instance to reconnect.
 */
class CompanionClient(
    private val host: String,
    private val port: Int,
    private val deviceInfo: LocalDeviceInfo = LocalDeviceInfo(),
) {
    private val connection = CompanionConnection(host, port)
    private val protocol = CompanionProtocol(connection)
    private var sid: Long = 0

    var onConnectionClosed: ((Throwable?) -> Unit)?
        get() = protocol.onConnectionClosed
        set(value) { protocol.onConnectionClosed = value }

    val isConnected: Boolean get() = connection.isConnected

    // ---------- Pairing ----------

    private var pairSetup: CompanionPairSetup? = null

    /** Open a connection and ask the Apple TV to show its pairing PIN. */
    suspend fun startPairing() = withContext(Dispatchers.IO) {
        connection.connect()
        val setup = CompanionPairSetup(protocol, SrpAuthHandler())
        setup.start()
        pairSetup = setup
    }

    /** Complete pairing with the PIN the user read off the TV; returns credentials. */
    suspend fun finishPairing(pin: String): HapCredentials = withContext(Dispatchers.IO) {
        val setup = pairSetup ?: throw CompanionProtocolException("Pairing was not started")
        setup.finish(pin, deviceInfo.name)
    }

    // ---------- Control session ----------

    /** Connect, verify stored credentials, and complete the input session handshake. */
    suspend fun connect(credentials: HapCredentials) = withContext(Dispatchers.IO) {
        connection.connect()
        CompanionPairVerify(protocol, SrpAuthHandler(), credentials).verifyAndEnableEncryption()
        systemInfo(credentials)
        sessionStart()
        tvRemoteControlSessionStart()
    }

    private suspend fun systemInfo(credentials: HapCredentials) {
        sendCommand(
            "_systemInfo",
            linkedMapOf(
                "_bf" to 0,
                "_cf" to 512,
                "_clFl" to 128,
                "_i" to deviceInfo.uniqueId,
                "_idsID" to credentials.clientId,
                "_pubID" to deviceInfo.uniqueId,
                "_sf" to 256,
                "_sv" to "170.18",
                "model" to deviceInfo.model,
                "name" to deviceInfo.name,
            ),
        )
    }

    private suspend fun sessionStart() {
        val localSid = Random.nextLong(0, 1L shl 32)
        val response = sendCommand(
            "_sessionStart",
            linkedMapOf("_srvT" to "com.apple.tvremoteservices", "_sid" to localSid),
        )
        val content = response["_c"] as? OpackDict
        val remoteSid = (content?.get("_sid") as? Number)?.toLong() ?: 0L
        sid = (remoteSid shl 32) or localSid
    }

    private suspend fun tvRemoteControlSessionStart() {
        // Not supported on every tvOS build; failure here is non-fatal.
        runCatching {
            sendCommand("TVRCSessionStart", linkedMapOf("ProtocolVersionKey" to "1.2"))
        }
    }

    // ---------- Input ----------

    /** Press and release a button (optionally holding it for [holdMs]). */
    suspend fun pressButton(command: HidCommand, holdMs: Long = 0) {
        hid(command, down = true)
        if (holdMs > 0) delay(holdMs)
        hid(command, down = false)
    }

    private suspend fun hid(command: HidCommand, down: Boolean) {
        sendCommand("_hidC", linkedMapOf("_hBtS" to if (down) 1 else 2, "_hidC" to command.value))
    }

    /** Current attention (power) state, or null if the Apple TV doesn't answer the query. */
    suspend fun systemStatus(): SystemStatus? = runCatching {
        val response = sendCommand("FetchAttentionState", linkedMapOf())
        val content = response["_c"] as? OpackDict
        SystemStatus.from((content?.get("state") as? Number)?.toInt())
    }.getOrNull()

    suspend fun mediaControl(command: MediaControlCommand, args: Map<String, Any?> = emptyMap()) {
        val content = LinkedHashMap<String, Any?>().apply {
            put("_mcc", command.value)
            putAll(args)
        }
        sendCommand("_mcc", content)
    }

    private suspend fun sendCommand(identifier: String, content: Map<String, Any?>): OpackDict =
        withContext(Dispatchers.IO) {
            protocol.exchangeOpack(
                FrameType.E_OPACK,
                linkedMapOf("_i" to identifier, "_t" to 2, "_c" to content),
            )
        }

    fun close() = connection.close()
}
