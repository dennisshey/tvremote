package com.sidephone.atvremote.remote

import android.content.Context
import com.sidephone.atvremote.companion.HapCredentials

/**
 * Persists pairing credentials per Apple TV.
 *
 * Credentials are stored as the pyatv-compatible `ltpk:ltsk:atvId:clientId` hex
 * string. For a shipping build these should live in EncryptedSharedPreferences
 * (androidx.security); plain prefs are used here to keep the dependency surface
 * minimal — see README "Security notes".
 */
class CredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("atv_credentials", Context.MODE_PRIVATE)

    fun save(device: AppleTvDevice, credentials: HapCredentials) {
        prefs.edit()
            .putString(credKey(device.id), credentials.serialize())
            .putString(nameKey(device.id), device.name)
            .apply()
    }

    fun load(deviceId: String): HapCredentials? =
        prefs.getString(credKey(deviceId), null)?.let {
            runCatching { HapCredentials.parse(it) }.getOrNull()
        }

    fun isPaired(deviceId: String): Boolean = prefs.contains(credKey(deviceId))

    fun forget(deviceId: String) {
        prefs.edit().remove(credKey(deviceId)).remove(nameKey(deviceId)).apply()
    }

    /** All paired devices, reconstructed from their stored id (`host:port`) + name. */
    fun pairedDevices(): List<AppleTvDevice> =
        prefs.all.keys
            .filter { it.startsWith(CRED_PREFIX) }
            .mapNotNull { key ->
                val id = key.removePrefix(CRED_PREFIX)
                val host = id.substringBeforeLast(":", "")
                val port = id.substringAfterLast(":", "").toIntOrNull()
                if (host.isEmpty() || port == null) return@mapNotNull null
                AppleTvDevice(prefs.getString(nameKey(id), null) ?: "Apple TV", host, port)
            }

    private fun credKey(id: String) = "$CRED_PREFIX$id"
    private fun nameKey(id: String) = "$NAME_PREFIX$id"

    companion object {
        private const val CRED_PREFIX = "cred:"
        private const val NAME_PREFIX = "name:"
    }
}
