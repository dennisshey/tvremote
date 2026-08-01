package com.sidephone.atvremote.ui

import android.content.Context
import android.content.Intent
import com.sidephone.atvremote.remote.AppleTvDevice

/** Helpers for passing an [AppleTvDevice] between activities via Intent extras. */
object IntentExtras {
    private const val EXTRA_NAME = "device_name"
    private const val EXTRA_HOST = "device_host"
    private const val EXTRA_PORT = "device_port"

    fun intent(context: Context, target: Class<*>, device: AppleTvDevice): Intent =
        Intent(context, target).apply {
            putExtra(EXTRA_NAME, device.name)
            putExtra(EXTRA_HOST, device.host)
            putExtra(EXTRA_PORT, device.port)
        }

    fun device(intent: Intent): AppleTvDevice? {
        val host = intent.getStringExtra(EXTRA_HOST) ?: return null
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        if (port < 0) return null
        val name = intent.getStringExtra(EXTRA_NAME) ?: "Apple TV"
        return AppleTvDevice(name, host, port)
    }
}
