package com.sidephone.atvremote.ui

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.sidephone.atvremote.databinding.ActivityDiscoveryBinding
import com.sidephone.atvremote.remote.AppleTvDevice
import com.sidephone.atvremote.remote.AppleTvDiscovery
import com.sidephone.atvremote.remote.CredentialStore

/**
 * Landing screen: browse for Apple TVs on the LAN (plus any already-paired ones),
 * and route the chosen device to pairing or straight to the remote.
 */
class DiscoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscoveryBinding
    private lateinit var discovery: AppleTvDiscovery
    private lateinit var credentialStore: CredentialStore
    private lateinit var adapter: DeviceAdapter

    private val discovered = LinkedHashMap<String, AppleTvDevice>()
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiscoveryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        credentialStore = CredentialStore(this)
        adapter = DeviceAdapter(::onDeviceClicked)
        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.adapter = adapter

        discovery = AppleTvDiscovery(this).apply {
            onDeviceFound = { device ->
                discovered[device.id] = device
                refreshList()
            }
            onError = { message -> showMessage(message) }
        }

        binding.rescanButton.setOnClickListener { restartDiscovery() }
        binding.manualButton.setOnClickListener { showManualAddDialog() }

        // Skip straight to the last-used remote on a fresh launch; holding Back on
        // the remote screen returns here to pick a different Apple TV.
        if (savedInstanceState == null) {
            credentialStore.lastDevice()?.let { device ->
                startActivity(IntentExtras.intent(this, RemoteActivity::class.java, device))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        restartDiscovery()
    }

    override fun onPause() {
        super.onPause()
        discovery.stop()
        releaseMulticastLock()
    }

    private fun restartDiscovery() {
        discovered.clear()
        refreshList()
        acquireMulticastLock()
        discovery.stop()
        discovery.start()
    }

    private fun refreshList() {
        val paired = credentialStore.pairedDevices().associateBy { it.id }
        val rows = mutableListOf<DeviceAdapter.Row>()
        // Paired devices first, then freshly discovered ones not already listed.
        paired.values.forEach { rows.add(DeviceAdapter.Row(it, paired = true)) }
        discovered.values.forEach { device ->
            if (!paired.containsKey(device.id)) {
                rows.add(DeviceAdapter.Row(device, paired = false))
            }
        }
        adapter.submit(rows)

        val empty = rows.isEmpty()
        binding.emptyText.visibility = if (empty) android.view.View.VISIBLE else android.view.View.GONE
        binding.statusText.setText(
            if (empty) com.sidephone.atvremote.R.string.discovery_scanning
            else com.sidephone.atvremote.R.string.discovery_title
        )
    }

    private fun onDeviceClicked(device: AppleTvDevice) {
        val target = if (credentialStore.isPaired(device.id)) {
            RemoteActivity::class.java
        } else {
            PairingActivity::class.java
        }
        startActivity(IntentExtras.intent(this, target, device))
    }

    private fun showManualAddDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val hostInput = EditText(this).apply {
            hint = getString(com.sidephone.atvremote.R.string.manual_host_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val portInput = EditText(this).apply {
            hint = getString(com.sidephone.atvremote.R.string.manual_port_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        container.addView(hostInput)
        container.addView(portInput)

        AlertDialog.Builder(this)
            .setTitle(com.sidephone.atvremote.R.string.manual_title)
            .setView(container)
            .setPositiveButton(com.sidephone.atvremote.R.string.manual_add) { _, _ ->
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().trim().toIntOrNull() ?: DEFAULT_COMPANION_PORT
                if (host.isNotEmpty()) {
                    onDeviceClicked(AppleTvDevice("Apple TV ($host)", host, port))
                }
            }
            .setNegativeButton(com.sidephone.atvremote.R.string.cancel, null)
            .show()
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("atv-discovery").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) runCatching { it.release() } }
        multicastLock = null
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        const val DEFAULT_COMPANION_PORT = 49152
    }
}
