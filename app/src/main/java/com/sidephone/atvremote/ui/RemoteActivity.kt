package com.sidephone.atvremote.ui

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.sidephone.atvremote.R
import com.sidephone.atvremote.databinding.ActivityRemoteBinding
import com.sidephone.atvremote.remote.AppleTvDevice
import com.sidephone.atvremote.remote.CredentialStore
import com.sidephone.atvremote.remote.KeyMapper
import com.sidephone.atvremote.remote.RemoteAction
import com.sidephone.atvremote.remote.RemoteController
import kotlinx.coroutines.launch

/**
 * The remote itself. While this screen is in the foreground it owns the SP-01
 * keypad: physical keys are translated by [KeyMapper] and dispatched through
 * [RemoteController]. On-screen buttons provide a touch fallback, and the keypad
 * map is shown so the layout is discoverable.
 */
class RemoteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemoteBinding
    private lateinit var device: AppleTvDevice
    private lateinit var controller: RemoteController
    private lateinit var credentialStore: CredentialStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val parsed = IntentExtras.device(intent)
        credentialStore = CredentialStore(this)
        if (parsed == null || !credentialStore.isPaired(parsed.id)) {
            // Not paired (or launched without a device) — bounce back to discovery.
            startActivity(android.content.Intent(this, DiscoveryActivity::class.java))
            finish()
            return
        }
        device = parsed
        title = device.name

        controller = RemoteController(lifecycleScope).apply {
            onError = { message -> Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show() }
        }

        wireOnScreenButtons()
        buildHelp()

        lifecycleScope.launch {
            controller.state.collect { render(it) }
        }

        connect()
    }

    private fun connect() {
        val credentials = credentialStore.load(device.id) ?: run {
            startActivity(IntentExtras.intent(this, PairingActivity::class.java, device))
            finish()
            return
        }
        controller.connect(device, credentials)
    }

    private fun render(state: RemoteController.ConnectionState) {
        val (textRes, color) = when (state) {
            RemoteController.ConnectionState.Connected ->
                R.string.remote_connected to R.color.accent_green
            RemoteController.ConnectionState.Disconnected ->
                R.string.remote_disconnected to R.color.accent_red
            else ->
                R.string.remote_connecting to R.color.on_surface_muted
        }
        binding.statusText.setText(textRes)
        binding.statusText.setTextColor(getColor(color))
    }

    // ---------- Input dispatch ----------

    /** Central entry point: reconnect if the link dropped, otherwise send the action. */
    private fun handleAction(action: RemoteAction) {
        if (controller.state.value != RemoteController.ConnectionState.Connected) {
            connect()
            return
        }
        controller.perform(action)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when {
            keyCode == KeyEvent.KEYCODE_BACK -> {
                event.startTracking() // enables the long-press-to-exit callback
                return true
            }
            KeyMapper.mapLongPress(keyCode) != null -> {
                if (event.repeatCount == 0) event.startTracking()
                return true
            }
            KeyMapper.handles(keyCode) -> {
                if (event.repeatCount == 0) KeyMapper.map(keyCode)?.let { handleAction(it) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish() // hold Back to leave the remote and release the keypad
            return true
        }
        KeyMapper.mapLongPress(keyCode)?.let {
            handleAction(it)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.isTracking && !event.isCanceled) handleAction(RemoteAction.MENU)
            return true
        }
        if (KeyMapper.mapLongPress(keyCode) != null) {
            // Long-press already fired the alternate action; a clean release fires the short one.
            if (event.isTracking && !event.isCanceled) KeyMapper.map(keyCode)?.let { handleAction(it) }
            return true
        }
        if (KeyMapper.handles(keyCode)) return true // consumed on key-down
        return super.onKeyUp(keyCode, event)
    }

    // ---------- On-screen fallback + help ----------

    private fun wireOnScreenButtons() {
        binding.btnUp.setOnClickListener { handleAction(RemoteAction.UP) }
        binding.btnDown.setOnClickListener { handleAction(RemoteAction.DOWN) }
        binding.btnLeft.setOnClickListener { handleAction(RemoteAction.LEFT) }
        binding.btnRight.setOnClickListener { handleAction(RemoteAction.RIGHT) }
        binding.btnOk.setOnClickListener { handleAction(RemoteAction.SELECT) }
        binding.btnMenu.setOnClickListener { handleAction(RemoteAction.MENU) }
        binding.btnHome.setOnClickListener { handleAction(RemoteAction.HOME) }
        binding.btnPlay.setOnClickListener { handleAction(RemoteAction.PLAY_PAUSE) }
    }

    private fun buildHelp() {
        val density = resources.displayMetrics.density
        val vPad = (6 * density).toInt()
        for ((key, action) in KeyMapper.cheatSheet) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, vPad, 0, vPad)
            }
            val keyView = TextView(this).apply {
                text = key
                setTextColor(getColor(R.color.primary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val actionView = TextView(this).apply {
                text = action
                setTextColor(getColor(R.color.on_surface_muted))
                textSize = 13f
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(keyView)
            row.addView(actionView)
            binding.helpContainer.addView(row)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::controller.isInitialized) controller.disconnect()
    }
}
