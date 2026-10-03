package com.sidephone.atvremote.remote

import com.sidephone.atvremote.companion.CompanionClient
import com.sidephone.atvremote.companion.CompanionDeviceException
import com.sidephone.atvremote.companion.CompanionProtocolException
import com.sidephone.atvremote.companion.HapCredentials
import com.sidephone.atvremote.companion.HidCommand
import com.sidephone.atvremote.companion.LocalDeviceInfo
import com.sidephone.atvremote.companion.MediaControlCommand
import com.sidephone.atvremote.companion.SystemStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the live [CompanionClient] and turns [RemoteAction]s into commands.
 *
 * Actions flow through a single channel consumed by one coroutine, so rapid key
 * presses are sent to the Apple TV in order and never overlap on the wire.
 *
 * The controller also keeps the session healthy on its own: a periodic keepalive
 * request stops Wi-Fi power-save / tvOS idle timeouts from silently killing the
 * connection, and when the link does drop it reconnects automatically (with
 * backoff) using the last device and credentials.
 *
 * All public entry points and callbacks run on [scope]'s dispatcher (the main
 * thread in the app), which is what makes the mutable state here safe.
 */
class RemoteController(
    private val scope: CoroutineScope,
    private val deviceInfo: LocalDeviceInfo = LocalDeviceInfo(),
) {
    enum class ConnectionState { Idle, Connecting, Connected, Disconnected }

    private val _state = MutableStateFlow(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    var onError: ((String) -> Unit)? = null

    private var client: CompanionClient? = null
    private var device: AppleTvDevice? = null
    private var credentials: HapCredentials? = null
    private var keepAliveJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    /** Back-to-back requests the Apple TV didn't answer; one slow reply isn't a dead link. */
    private var missedReplies = 0

    /** Holdable actions currently pressed (main thread only). */
    private val held = mutableSetOf<RemoteAction>()

    /** Auto-repeat loops for held [RemoteAction.autoRepeat] actions. */
    private val repeatJobs = mutableMapOf<RemoteAction, Job>()

    private sealed interface Input {
        /** [done] completes once the tap has been handled, so auto-repeat never outruns the wire. */
        data class Tap(val action: RemoteAction, val done: CompletableDeferred<Unit>? = null) : Input
        data class ButtonDown(val action: RemoteAction) : Input
        data class ButtonUp(val action: RemoteAction) : Input
    }

    private val inputs = Channel<Input>(capacity = Channel.BUFFERED)

    init {
        scope.launch {
            for (input in inputs) runInput(input)
        }
    }

    val isConnected: Boolean get() = _state.value == ConnectionState.Connected

    fun connect(device: AppleTvDevice, credentials: HapCredentials) {
        this.device = device
        this.credentials = credentials
        reconnectJob?.cancel()
        reconnectAttempts = 0
        openConnection(notifyError = true)
    }

    /**
     * Called when the remote screen comes back to the foreground. The link often
     * dies while the screen is off (Wi-Fi sleeps), and the backoff may have given
     * up by then, so start over immediately.
     */
    fun ensureConnected() {
        if (_state.value != ConnectionState.Disconnected) return
        reconnectJob?.cancel()
        reconnectAttempts = 0
        openConnection(notifyError = false)
    }

    private fun openConnection(notifyError: Boolean) {
        val device = device ?: return
        val credentials = credentials ?: return
        if (_state.value == ConnectionState.Connecting) return
        _state.value = ConnectionState.Connecting
        client?.close()
        val newClient = CompanionClient(device.host, device.port, deviceInfo)
        newClient.onConnectionClosed = { onClientClosed(newClient) }
        client = newClient
        scope.launch {
            try {
                newClient.connect(credentials)
                if (client !== newClient) {
                    newClient.close()
                    return@launch
                }
                // The socket can die right at the end of the handshake; catching it
                // here (rather than declaring Connected) keeps the reconnect loop alive.
                if (!newClient.isConnected) throw CompanionProtocolException("Connection closed")
                reconnectAttempts = 0
                missedReplies = 0
                _state.value = ConnectionState.Connected
                startKeepAlive(newClient)
            } catch (t: Throwable) {
                newClient.close()
                if (client === newClient) {
                    _state.value = ConnectionState.Disconnected
                    if (notifyError) onError?.invoke(t.message ?: "Could not connect to Apple TV")
                    scheduleReconnect()
                }
            }
        }
    }

    /** Invoked (from the reader thread) when a client's connection dies. */
    private fun onClientClosed(closedClient: CompanionClient) {
        scope.launch {
            if (client !== closedClient) return@launch // stale client; ignore
            keepAliveJob?.cancel()
            clearHeld()
            if (_state.value == ConnectionState.Connected) {
                _state.value = ConnectionState.Disconnected
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return
        if (reconnectJob?.isActive == true) return
        reconnectAttempts++
        val delayMs = (RECONNECT_BASE_DELAY_MS * reconnectAttempts).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (_state.value == ConnectionState.Disconnected) openConnection(notifyError = false)
        }
    }

    private fun startKeepAlive(activeClient: CompanionClient) {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (client === activeClient && activeClient.isConnected) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                if (client !== activeClient) break
                try {
                    activeClient.keepAlive()
                    missedReplies = 0
                } catch (t: Throwable) {
                    if (isFatal(t)) {
                        // Dead link; closing fires onClientClosed, which reconnects.
                        activeClient.close()
                        break
                    }
                }
            }
        }
    }

    /**
     * Whether a failed request means the session is gone. A device error reply
     * means the TV is listening; a single timeout is usually just a slow TV, so
     * only [MAX_MISSED_REPLIES] in a row (or a socket failure) count as dead.
     */
    private fun isFatal(t: Throwable): Boolean = when {
        t is CompanionDeviceException -> false
        t is CompanionProtocolException && t.cause is TimeoutCancellationException ->
            ++missedReplies >= MAX_MISSED_REPLIES
        else -> true
    }

    /** Queue a tap (press + release) for delivery. No-ops silently if not connected. */
    fun perform(action: RemoteAction) {
        inputs.trySend(Input.Tap(action))
    }

    /** Press and hold a [RemoteAction.holdable] action until [pressUp]. */
    fun pressDown(action: RemoteAction) {
        if (!action.holdable) {
            perform(action)
            return
        }
        if (!held.add(action)) return
        if (action.autoRepeat) {
            perform(action)
            repeatJobs[action] = scope.launch {
                delay(REPEAT_INITIAL_DELAY_MS)
                while (action in held) {
                    val done = CompletableDeferred<Unit>()
                    if (inputs.trySend(Input.Tap(action, done)).isFailure) break
                    done.await()
                    delay(REPEAT_INTERVAL_MS)
                }
            }
        } else {
            inputs.trySend(Input.ButtonDown(action))
        }
    }

    /** Release an action held with [pressDown]. No-op if it isn't held. */
    fun pressUp(action: RemoteAction) {
        if (!held.remove(action)) return
        if (action.autoRepeat) {
            repeatJobs.remove(action)?.cancel()
        } else {
            inputs.trySend(Input.ButtonUp(action))
        }
    }

    /** Release everything still held (e.g. when the remote screen loses focus). */
    fun releaseHeld() {
        held.toList().forEach { pressUp(it) }
    }

    /** Forget held buttons without sending releases (the session they were held on is gone). */
    private fun clearHeld() {
        held.clear()
        repeatJobs.values.forEach { it.cancel() }
        repeatJobs.clear()
    }

    private suspend fun runInput(input: Input) {
        try {
            val activeClient = client ?: return
            if (!activeClient.isConnected) return
            try {
                when (input) {
                    is Input.Tap -> execute(input.action, activeClient)
                    is Input.ButtonDown -> hidFor(input.action)?.let { activeClient.buttonDown(it) }
                    is Input.ButtonUp -> hidFor(input.action)?.let { activeClient.buttonUp(it) }
                }
                missedReplies = 0
            } catch (t: Throwable) {
                if (isFatal(t)) {
                    // Closing fires onClientClosed, which flips the state and reconnects.
                    activeClient.close()
                    onError?.invoke(t.message ?: "Lost connection to Apple TV")
                } else if (t is CompanionDeviceException) {
                    // E.g. skip with nothing playing — the session is fine, just say so.
                    onError?.invoke("Apple TV couldn't do that right now")
                }
            }
        } finally {
            if (input is Input.Tap) input.done?.complete(Unit)
        }
    }

    private fun hidFor(action: RemoteAction): HidCommand? = when (action) {
        RemoteAction.UP -> HidCommand.Up
        RemoteAction.DOWN -> HidCommand.Down
        RemoteAction.LEFT -> HidCommand.Left
        RemoteAction.RIGHT -> HidCommand.Right
        RemoteAction.SELECT -> HidCommand.Select
        else -> null
    }

    private suspend fun execute(action: RemoteAction, client: CompanionClient) {
        hidFor(action)?.let {
            client.pressButton(it)
            return
        }
        when (action) {
            RemoteAction.BACK -> client.pressButton(HidCommand.Menu)
            RemoteAction.HOME -> client.pressButton(HidCommand.Home)
            // A held Menu press is how the Siri Remote jumps to the Home Screen;
            // unlike the Home (TV) button it ignores the tvOS "TV Button" setting,
            // which by default opens the Apple TV app instead.
            RemoteAction.HOME_SCREEN -> client.pressButton(HidCommand.Menu, holdMs = 1000)
            RemoteAction.PLAY_PAUSE -> client.pressButton(HidCommand.PlayPause)
            RemoteAction.VOLUME_UP -> client.pressButton(HidCommand.VolumeUp)
            RemoteAction.VOLUME_DOWN -> client.pressButton(HidCommand.VolumeDown)
            RemoteAction.SIRI -> client.pressButton(HidCommand.Siri, holdMs = 1500)
            RemoteAction.SKIP_FORWARD ->
                client.mediaControl(MediaControlCommand.SkipBy, mapOf("_skpS" to SKIP_SECONDS))
            RemoteAction.SKIP_BACKWARD ->
                client.mediaControl(MediaControlCommand.SkipBy, mapOf("_skpS" to -SKIP_SECONDS))
            RemoteAction.POWER -> {
                // Toggle: wake a sleeping TV, put an awake one to sleep. If the TV
                // won't answer the state query, assume awake — Sleep is a no-op on
                // a sleeping TV, and any other button wakes it.
                val asleep = client.systemStatus() == SystemStatus.Asleep
                client.pressButton(if (asleep) HidCommand.Wake else HidCommand.Sleep)
            }
            else -> Unit // holdable actions handled above
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        keepAliveJob?.cancel()
        device = null
        credentials = null
        clearHeld()
        client?.close()
        client = null
        _state.value = ConnectionState.Idle
    }

    companion object {
        private const val SKIP_SECONDS = 15.0
        private const val KEEP_ALIVE_INTERVAL_MS = 10_000L
        private const val MAX_MISSED_REPLIES = 2
        private const val RECONNECT_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 10_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10

        // Matches the feel of a held arrow on a keyboard: a short pause, then steady steps.
        private const val REPEAT_INITIAL_DELAY_MS = 350L
        private const val REPEAT_INTERVAL_MS = 90L
    }
}
