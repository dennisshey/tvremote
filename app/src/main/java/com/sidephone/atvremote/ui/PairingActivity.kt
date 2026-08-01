package com.sidephone.atvremote.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sidephone.atvremote.R
import com.sidephone.atvremote.companion.CompanionClient
import com.sidephone.atvremote.databinding.ActivityPairingBinding
import com.sidephone.atvremote.remote.AppleTvDevice
import com.sidephone.atvremote.remote.CredentialStore
import kotlinx.coroutines.launch

/**
 * Drives HAP pair-setup: ask the Apple TV to display a PIN, collect it from the
 * SP-01 keypad, finish the exchange, and persist the resulting credentials.
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPairingBinding
    private lateinit var device: AppleTvDevice
    private lateinit var credentialStore: CredentialStore
    private var client: CompanionClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val parsed = IntentExtras.device(intent)
        if (parsed == null) {
            finish()
            return
        }
        device = parsed
        credentialStore = CredentialStore(this)
        binding.deviceLabel.text = device.id

        binding.pairButton.setOnClickListener { finishPairing() }

        requestPin()
    }

    private fun requestPin() {
        binding.instructions.setText(R.string.pairing_requesting)
        binding.progress.visibility = View.VISIBLE
        val newClient = CompanionClient(device.host, device.port)
        client = newClient
        lifecycleScope.launch {
            try {
                newClient.startPairing()
                binding.instructions.setText(R.string.pairing_instructions)
                binding.pinInput.isEnabled = true
                binding.pairButton.isEnabled = true
                binding.pinInput.requestFocus()
            } catch (t: Throwable) {
                showError(t.message ?: "unknown error")
            } finally {
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun finishPairing() {
        val pin = binding.pinInput.text.toString().trim()
        if (pin.length != 4) {
            binding.pinInput.error = getString(R.string.pairing_pin_hint)
            return
        }
        val activeClient = client ?: return

        setBusy(true)
        binding.instructions.setText(R.string.pairing_working)
        lifecycleScope.launch {
            try {
                val credentials = activeClient.finishPairing(pin)
                credentialStore.save(device, credentials)
                activeClient.close()
                binding.instructions.setText(R.string.pairing_success)
                startActivity(IntentExtras.intent(this@PairingActivity, RemoteActivity::class.java, device))
                finish()
            } catch (t: Throwable) {
                showError(t.message ?: "unknown error")
                setBusy(false)
                // A wrong PIN invalidates the setup session; restart it for a retry.
                activeClient.close()
                binding.pinInput.text?.clear()
                requestPin()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.pinInput.isEnabled = !busy
        binding.pairButton.isEnabled = !busy
    }

    private fun showError(reason: String) {
        binding.instructions.text = getString(R.string.pairing_failed, reason)
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.close()
    }
}
