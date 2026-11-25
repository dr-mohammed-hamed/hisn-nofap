package com.drmohammed.antiporn.provisioning.engine

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * mDNS Network Service Discovery for Android 11+ Wireless Debugging.
 *
 * Android OS advertises two local services:
 * 1. `_adb-tls-pairing._tcp`: Active when user opens "Pair device with pairing code".
 * 2. `_adb-tls-connect._tcp`: Active whenever "Wireless debugging" toggle is ON.
 *
 * This radar eliminates friction for non-technical users by capturing the dynamic ports
 * automatically in ~200ms without requiring split-screen or manual port entry.
 */
class AdbMdnsDiscovery(
    context: Context,
    private val onPairingPortDiscovered: (port: Int) -> Unit,
    private val onConnectPortDiscovered: (port: Int) -> Unit,
    private val onPairingServiceLost: () -> Unit = {}
) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private var isPairingDiscoveryActive = false
    private var isConnectDiscoveryActive = false
    private var pendingStopPairing = false
    private var pendingStopConnect = false

    private val resolveQueue = ArrayDeque<Pair<NsdServiceInfo, (Int, String?) -> Unit>>()
    private var isResolving = false
    private val probeExecutor = Executors.newSingleThreadExecutor()
    private val serviceInfoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()

    private val pairingDiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "mDNS Pairing discovery started: $regType")
            isPairingDiscoveryActive = true
            if (pendingStopPairing) {
                pendingStopPairing = false
                try {
                    nsdManager?.stopServiceDiscovery(this)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping pairing discovery on pending stop", e)
                }
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "mDNS Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
            if (serviceInfo.serviceType.contains(SERVICE_TYPE_PAIRING)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    registerModernServiceInfoCallback(serviceInfo, isPairing = true)
                } else {
                    resolveService(serviceInfo) { port, host ->
                        handleDiscoveredPairingPort(port, host)
                    }
                }
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "mDNS Pairing service lost: ${serviceInfo.serviceName}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val serviceKey = "${serviceInfo.serviceName}_${serviceInfo.serviceType}"
                serviceInfoCallbacks.remove(serviceKey)?.let { cb ->
                    try {
                        nsdManager?.unregisterServiceInfoCallback(cb)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed unregistering ServiceInfoCallback on service lost: $serviceKey", e)
                    }
                }
            }
            onPairingServiceLost()
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "mDNS Pairing discovery stopped")
            isPairingDiscoveryActive = false
            pendingStopPairing = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "mDNS Pairing discovery start failed with error: $errorCode")
            isPairingDiscoveryActive = false
            pendingStopPairing = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "mDNS Pairing discovery stop failed with error: $errorCode")
            isPairingDiscoveryActive = false
            pendingStopPairing = false
        }
    }

    private val connectDiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "mDNS Connect discovery started: $regType")
            isConnectDiscoveryActive = true
            if (pendingStopConnect) {
                pendingStopConnect = false
                try {
                    nsdManager?.stopServiceDiscovery(this)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping connect discovery on pending stop", e)
                }
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType.contains(SERVICE_TYPE_CONNECT)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    registerModernServiceInfoCallback(serviceInfo, isPairing = false)
                } else {
                    resolveService(serviceInfo) { port, host ->
                        Log.i(TAG, "Captured dynamic Connect Port: $port (host=$host)")
                        lastDiscoveredHost = host
                        onConnectPortDiscovered(port)
                    }
                }
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val serviceKey = "${serviceInfo.serviceName}_${serviceInfo.serviceType}"
                serviceInfoCallbacks.remove(serviceKey)?.let { cb ->
                    try {
                        nsdManager?.unregisterServiceInfoCallback(cb)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed unregistering ServiceInfoCallback on service lost: $serviceKey", e)
                    }
                }
            }
            lastDiscoveredHost = null
        }

        override fun onDiscoveryStopped(serviceType: String) {
            isConnectDiscoveryActive = false
            pendingStopConnect = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "mDNS Connect discovery start failed with error: $errorCode")
            isConnectDiscoveryActive = false
            pendingStopConnect = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "mDNS Connect discovery stop failed with error: $errorCode")
            isConnectDiscoveryActive = false
            pendingStopConnect = false
        }
    }

    private fun handleDiscoveredPairingPort(port: Int, host: String?) {
        try {
            probeExecutor.execute {
                if (isPortActuallyListening(port)) {
                    Log.i(TAG, "Captured verified active Pairing Port: $port (host=$host)")
                    lastDiscoveredHost = host
                    onPairingPortDiscovered(port)
                } else {
                    Log.w(TAG, "Discarded stale cached Pairing Port: $port (nothing listening)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed submitting port probe task", e)
        }
    }

    private fun registerModernServiceInfoCallback(serviceInfo: NsdServiceInfo, isPairing: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val serviceKey = "${serviceInfo.serviceName}_${serviceInfo.serviceType}"
        serviceInfoCallbacks.remove(serviceKey)?.let { oldCallback ->
            try {
                nsdManager?.unregisterServiceInfoCallback(oldCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed unregistering superseded ServiceInfoCallback for $serviceKey", e)
            }
        }

        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(updatedInfo: NsdServiceInfo) {
                val port = updatedInfo.port
                val host = updatedInfo.hostAddresses.firstOrNull()?.hostAddress ?: updatedInfo.host?.hostAddress
                if (port > 0) {
                    if (isPairing) {
                        handleDiscoveredPairingPort(port, host)
                    } else {
                        Log.i(TAG, "Captured dynamic Connect Port (API 34+): $port (host=$host)")
                        lastDiscoveredHost = host
                        onConnectPortDiscovered(port)
                    }
                }
            }

            override fun onServiceLost() {
                if (isPairing) {
                    Log.d(TAG, "mDNS Pairing service lost (API 34+ callback): ${serviceInfo.serviceName}")
                    onPairingServiceLost()
                } else {
                    Log.d(TAG, "mDNS Connect service lost (API 34+ callback): ${serviceInfo.serviceName}")
                    lastDiscoveredHost = null
                }
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "registerServiceInfoCallback failed for ${serviceInfo.serviceName}: error $errorCode")
            }

            override fun onServiceInfoCallbackUnregistered() {
                Log.d(TAG, "registerServiceInfoCallback unregistered for ${serviceInfo.serviceName}")
            }
        }

        serviceInfoCallbacks[serviceKey] = callback
        try {
            nsdManager?.registerServiceInfoCallback(serviceInfo, probeExecutor, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Error registering ServiceInfoCallback for ${serviceInfo.serviceName}", e)
            serviceInfoCallbacks.remove(serviceKey)
        }
    }

    /**
     * Starts monitoring for both pairing and connect mDNS services.
     */
    fun startDiscovery() {
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager is not available on this device")
            return
        }

        // Acquire MulticastLock to prevent kernel/Wi-Fi chip mDNS packet dropping
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("Hisn_AdbMdnsLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    Log.d(TAG, "Acquired WifiManager.MulticastLock for mDNS discovery")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed acquiring WifiManager.MulticastLock", e)
        }

        pendingStopPairing = false
        pendingStopConnect = false
        try {
            if (!isPairingDiscoveryActive) {
                nsdManager.discoverServices(
                    "_adb-tls-pairing._tcp",
                    NsdManager.PROTOCOL_DNS_SD,
                    pairingDiscoveryListener
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error starting pairing discovery", e)
        }

        try {
            if (!isConnectDiscoveryActive) {
                nsdManager.discoverServices(
                    "_adb-tls-connect._tcp",
                    NsdManager.PROTOCOL_DNS_SD,
                    connectDiscoveryListener
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error starting connect discovery", e)
        }
    }

    /**
     * Stops all active mDNS discovery listeners.
     */
    fun stopDiscovery() {
        // Safely release MulticastLock
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "Released WifiManager.MulticastLock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed releasing WifiManager.MulticastLock", e)
        }

        if (nsdManager == null) return
        if (isPairingDiscoveryActive) {
            try {
                nsdManager.stopServiceDiscovery(pairingDiscoveryListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping pairing discovery", e)
            }
            isPairingDiscoveryActive = false
        } else {
            pendingStopPairing = true
        }

        if (isConnectDiscoveryActive) {
            try {
                nsdManager.stopServiceDiscovery(connectDiscoveryListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping connect discovery", e)
            }
            isConnectDiscoveryActive = false
        } else {
            pendingStopConnect = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfoCallbacks.values.forEach { cb ->
                try {
                    nsdManager.unregisterServiceInfoCallback(cb)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed unregistering ServiceInfoCallback on stopDiscovery", e)
                }
            }
            serviceInfoCallbacks.clear()
        }

        synchronized(this) {
            resolveQueue.clear()
            isResolving = false
            lastDiscoveredHost = null
        }
    }

    @Synchronized
    private fun resolveService(serviceInfo: NsdServiceInfo, onResolved: (Int, String?) -> Unit) {
        resolveQueue.add(serviceInfo to onResolved)
        processNextResolveLocked()
    }

    @Synchronized
    private fun processNextResolveLocked() {
        if (isResolving || resolveQueue.isEmpty()) return
        val item = resolveQueue.removeFirstOrNull() ?: return
        val serviceInfo = item.first
        val onResolved = item.second
        val manager = nsdManager ?: return

        isResolving = true
        try {
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Failed to resolve service ${serviceInfo.serviceName}: error $errorCode")
                    synchronized(this@AdbMdnsDiscovery) {
                        isResolving = false
                        processNextResolveLocked()
                    }
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    try {
                        val port = resolvedInfo.port
                        val host = resolvedInfo.host?.hostAddress
                        if (port > 0) {
                            onResolved(port, host)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Exception in onResolved callback for ${serviceInfo.serviceName}", e)
                    } finally {
                        synchronized(this@AdbMdnsDiscovery) {
                            isResolving = false
                            processNextResolveLocked()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Exception resolving service: ${serviceInfo.serviceName}", e)
            synchronized(this@AdbMdnsDiscovery) {
                isResolving = false
                processNextResolveLocked()
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_AdbMdns"
        private const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing"
        private const val SERVICE_TYPE_CONNECT = "_adb-tls-connect"

        @Volatile
        var lastDiscoveredHost: String? = null

        fun isPortActuallyListening(port: Int): Boolean {
            if (port <= 0 || port > 65535) return false
            return try {
                java.net.ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(java.net.InetSocketAddress("127.0.0.1", port))
                }
                false // Successfully bound => Nothing listening, stale dead cache!
            } catch (_: java.io.IOException) {
                true // EADDRINUSE => adbd is actively listening!
            } catch (_: Exception) {
                false
            }
        }
    }
}
