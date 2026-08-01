package com.sidephone.atvremote.remote

import com.sidephone.atvremote.companion.CompanionClient
import com.sidephone.atvremote.companion.HapCredentials
import com.sidephone.atvremote.companion.HidCommand
import com.sidephone.atvremote.companion.LocalDeviceInfo
import com.sidephone.atvremote.companion.MediaControlCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the live [CompanionClient] and turns [RemoteAction]s into commands.
 *
 * Actions flow through a single channel consumed by one coroutine, so rapid key
 * presses are sent to the Apple TV in order and never overlap on the wire.
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
    private val actions = Channel<RemoteAction>(capacity = Channel.BUFFERED)

    init {
        scope.launch {
            for (action in actions) runAction(action)
        }
    }

    val isConnected: Boolean get() = _state.value == ConnectionState.Connected

    fun connect(device: AppleTvDevice, credentials: HapCredentials) {
        if (_state.value == ConnectionState.Connecting) return
        _state.value = ConnectionState.Connecting
        val newClient = CompanionClient(device.host, device.port, deviceInfo).apply {
            onConnectionClosed = { _state.value = ConnectionState.Disconnected }
        }
        client = newClient
        scope.launch {
            try {
                newClient.connect(credentials)
                _state.value = ConnectionState.Connected
            } catch (t: Throwable) {
                _state.value = ConnectionState.Disconnected
                onError?.invoke(t.message ?: "Could not connect to Apple TV")
            }
        }
    }

    /** Queue an action for delivery. No-ops silently if not connected. */
    fun perform(action: RemoteAction) {
        actions.trySend(action)
    }

    private suspend fun runAction(action: RemoteAction) {
        val activeClient = client ?: return
        if (!activeClient.isConnected) return
        try {
            execute(action, activeClient)
        } catch (t: Throwable) {
            _state.value = ConnectionState.Disconnected
            onError?.invoke(t.message ?: "Lost connection to Apple TV")
        }
    }

    private suspend fun execute(action: RemoteAction, client: CompanionClient) {
        when (action) {
            RemoteAction.UP -> client.pressButton(HidCommand.Up)
            RemoteAction.DOWN -> client.pressButton(HidCommand.Down)
            RemoteAction.LEFT -> client.pressButton(HidCommand.Left)
            RemoteAction.RIGHT -> client.pressButton(HidCommand.Right)
            RemoteAction.SELECT -> client.pressButton(HidCommand.Select)
            RemoteAction.MENU -> client.pressButton(HidCommand.Menu)
            RemoteAction.HOME -> client.pressButton(HidCommand.Home)
            RemoteAction.PLAY_PAUSE -> client.pressButton(HidCommand.PlayPause)
            RemoteAction.VOLUME_UP -> client.pressButton(HidCommand.VolumeUp)
            RemoteAction.VOLUME_DOWN -> client.pressButton(HidCommand.VolumeDown)
            RemoteAction.SIRI -> client.pressButton(HidCommand.Siri, holdMs = 1500)
            RemoteAction.SKIP_FORWARD ->
                client.mediaControl(MediaControlCommand.SkipBy, mapOf("_skpS" to SKIP_SECONDS))
            RemoteAction.SKIP_BACKWARD ->
                client.mediaControl(MediaControlCommand.SkipBy, mapOf("_skpS" to -SKIP_SECONDS))
        }
    }

    fun disconnect() {
        client?.close()
        client = null
        _state.value = ConnectionState.Idle
    }

    companion object {
        private const val SKIP_SECONDS = 15.0
    }
}
