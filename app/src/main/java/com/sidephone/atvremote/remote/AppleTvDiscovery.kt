package com.sidephone.atvremote.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers Apple TVs on the LAN by browsing for the Companion mDNS service
 * (`_companion-link._tcp`).
 *
 * NsdManager only allows one outstanding resolve at a time, so found services are
 * resolved through a small serial queue to avoid FAILED_ALREADY_ACTIVE errors.
 * All callbacks are delivered on the main thread.
 */
class AppleTvDiscovery(context: Context) {

    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)

    var onDeviceFound: ((AppleTvDevice) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun start() {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveQueue.add(serviceInfo)
                pumpResolveQueue()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                mainHandler.post { onError?.invoke("Discovery failed to start (code $errorCode)") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            discoveryListener = null
            mainHandler.post { onError?.invoke("Could not start discovery: ${it.message}") }
        }
    }

    fun stop() {
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
        }
        discoveryListener = null
        resolveQueue.clear()
        resolving.set(false)
    }

    private fun pumpResolveQueue() {
        if (!resolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll()
        if (next == null) {
            resolving.set(false)
            return
        }
        nsdManager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving.set(false)
                pumpResolveQueue()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = serviceInfo.host?.hostAddress
                if (host != null) {
                    val device = AppleTvDevice(
                        name = serviceInfo.serviceName ?: "Apple TV",
                        host = host,
                        port = serviceInfo.port,
                    )
                    mainHandler.post { onDeviceFound?.invoke(device) }
                }
                resolving.set(false)
                pumpResolveQueue()
            }
        })
    }

    companion object {
        const val SERVICE_TYPE = "_companion-link._tcp."
    }
}
