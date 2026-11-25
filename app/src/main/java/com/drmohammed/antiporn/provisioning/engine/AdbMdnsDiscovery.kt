package com.drmohammed.antiporn.provisioning.engine

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * mDNS Network Service Discovery for Android 11+ Wireless Debugging.
 *
 * Monitors `_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp` to capture dynamic ports
 * automatically without requiring split-screen or manual port entry.
 */
class AdbMdnsDiscovery(
    context: Context,
    private val onPairingPortDiscovered: (port: Int) -> Unit,
    private val onConnectPortDiscovered: (port: Int) -> Unit,
    private val onPairingServiceLost: () -> Unit = {},
    private val onConnectServiceLost: () -> Unit = {}
) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var isPairingDiscoveryActive = false
    @Volatile private var isConnectDiscoveryActive = false
    @Volatile private var pendingStopPairing = false
    @Volatile private var pendingStopConnect = false
    @Volatile private var isStopped = false

    private val resolveQueue = ArrayDeque<Pair<NsdServiceInfo, (Int, String?) -> Unit>>()
    private var isResolving = false
    private var probeExecutor: ExecutorService? = null
    private val serviceInfoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
    private val lostReportedFlags = ConcurrentHashMap<NsdManager.ServiceInfoCallback, AtomicBoolean>()

    private val pairingDiscoveryListener = createDiscoveryListener(isPairing = true)
    private val connectDiscoveryListener = createDiscoveryListener(isPairing = false)

    private inline fun safely(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, label)
        }
    }

    private fun updateChannel(isPairing: Boolean, active: Boolean, pending: Boolean) {
        if (isPairing) {
            isPairingDiscoveryActive = active
            pendingStopPairing = pending
        } else {
            isConnectDiscoveryActive = active
            pendingStopConnect = pending
        }
    }

    private fun createDiscoveryListener(isPairing: Boolean): NsdManager.DiscoveryListener {
        val label = if (isPairing) "Pairing" else "Connect"
        val marker = if (isPairing) SERVICE_TYPE_PAIRING else SERVICE_TYPE_CONNECT
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Timber.tag(TAG).d("mDNS $label discovery started: $regType")
                val pending = if (isPairing) pendingStopPairing else pendingStopConnect
                updateChannel(isPairing, active = true, pending = false)
                if (isStopped || pending) safely("Error stopping $label discovery") {
                    nsdManager?.stopServiceDiscovery(this)
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (isStopped || !serviceInfo.serviceType.contains(marker)) return
                if (isPairing) Timber.tag(TAG).d("mDNS Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    registerModernServiceInfoCallback(serviceInfo, isPairing)
                } else {
                    resolveService(serviceInfo) { port, host ->
                        if (isPairing) handleDiscoveredPairingPort(port, host)
                        else handleDiscoveredConnectPort(port, host, api34Plus = false)
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Timber.tag(TAG).d("mDNS $label service lost: ${serviceInfo.serviceName}")
                val alreadyReported = unregisterCallbackByKey("${serviceInfo.serviceName}_${serviceInfo.serviceType}")
                if (isStopped || alreadyReported) return
                if (isPairing) onPairingServiceLost() else {
                    lastDiscoveredHost = null
                    onConnectServiceLost()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                if (isPairing) Timber.tag(TAG).d("mDNS Pairing discovery stopped")
                updateChannel(isPairing, active = false, pending = false)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.tag(TAG).e("mDNS $label discovery start failed with error: $errorCode")
                updateChannel(isPairing, active = false, pending = false)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.tag(TAG).e("mDNS $label discovery stop failed with error: $errorCode")
                updateChannel(isPairing, active = false, pending = false)
            }
        }
    }

    @Synchronized
    private fun ensureProbeExecutor(): ExecutorService {
        val existing = probeExecutor
        if (existing != null && !existing.isShutdown) return existing
        return Executors.newSingleThreadExecutor().also { probeExecutor = it }
    }

    private fun handleDiscoveredPairingPort(port: Int, host: String?) {
        if (isStopped) return
        safely("Failed submitting port probe task") {
            val executor = synchronized(this) { if (isStopped) return; ensureProbeExecutor() }
            executor.execute {
                if (isStopped) return@execute
                if (isPortActuallyListening(port)) {
                    if (isStopped) return@execute
                    Timber.tag(TAG).i("Captured verified active Pairing Port: $port (host=$host)")
                    lastDiscoveredHost = host
                    onPairingPortDiscovered(port)
                } else {
                    Timber.tag(TAG).w("Discarded stale cached Pairing Port: $port (nothing listening)")
                }
            }
        }
    }

    private fun handleDiscoveredConnectPort(port: Int, host: String?, api34Plus: Boolean) {
        if (isStopped) return
        Timber.tag(TAG).i("Captured dynamic Connect Port${if (api34Plus) " (API 34+)" else ""}: $port (host=$host)")
        lastDiscoveredHost = host
        onConnectPortDiscovered(port)
    }

    private fun unregisterCallbackByKey(serviceKey: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val cb = serviceInfoCallbacks.remove(serviceKey) ?: return false
        val alreadyReported = lostReportedFlags.remove(cb)?.compareAndSet(false, true) == false
        safely("Failed unregistering ServiceInfoCallback: $serviceKey") {
            nsdManager?.unregisterServiceInfoCallback(cb)
        }
        return alreadyReported
    }

    private fun unregisterAllCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        serviceInfoCallbacks.keys.toList().forEach { unregisterCallbackByKey(it) }
        lostReportedFlags.clear()
    }

    private fun registerModernServiceInfoCallback(serviceInfo: NsdServiceInfo, isPairing: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || isStopped) return
        val serviceKey = "${serviceInfo.serviceName}_${serviceInfo.serviceType}"
        unregisterCallbackByKey(serviceKey)
        val executor = synchronized(this) { if (isStopped) return; ensureProbeExecutor() }
        val lostFlag = AtomicBoolean(false)

        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(updatedInfo: NsdServiceInfo) {
                if (isStopped) return
                val port = updatedInfo.port
                val host = updatedInfo.hostAddresses.firstOrNull()?.hostAddress ?: updatedInfo.host?.hostAddress
                if (port > 0) {
                    if (isPairing) handleDiscoveredPairingPort(port, host)
                    else handleDiscoveredConnectPort(port, host, api34Plus = true)
                }
            }

            override fun onServiceLost() {
                if (isStopped || !lostFlag.compareAndSet(false, true)) return
                Timber.tag(TAG).d("mDNS ${if (isPairing) "Pairing" else "Connect"} service lost (API 34+): ${serviceInfo.serviceName}")
                if (isPairing) onPairingServiceLost() else {
                    lastDiscoveredHost = null
                    onConnectServiceLost()
                }
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Timber.tag(TAG).w("registerServiceInfoCallback failed for ${serviceInfo.serviceName}: error $errorCode")
                detachCallback(serviceKey, this)
            }

            override fun onServiceInfoCallbackUnregistered() {
                Timber.tag(TAG).d("registerServiceInfoCallback unregistered for ${serviceInfo.serviceName}")
                detachCallback(serviceKey, this)
            }
        }

        serviceInfoCallbacks[serviceKey] = callback
        lostReportedFlags[callback] = lostFlag
        try {
            nsdManager?.registerServiceInfoCallback(serviceInfo, executor, callback)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error registering ServiceInfoCallback for ${serviceInfo.serviceName}")
            detachCallback(serviceKey, callback)
        }
    }

    private fun detachCallback(serviceKey: String, callback: NsdManager.ServiceInfoCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        serviceInfoCallbacks.remove(serviceKey, callback)
        lostReportedFlags.remove(callback)
    }

    /**
     * Starts monitoring for both pairing and connect mDNS services.
     */
    fun startDiscovery() {
        val manager = nsdManager ?: run {
            Timber.tag(TAG).e("NsdManager is not available on this device")
            return
        }
        isStopped = false
        ensureProbeExecutor()

        safely("Failed acquiring WifiManager.MulticastLock") {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("Hisn_AdbMdnsLock")?.apply { setReferenceCounted(false) }
            }
            multicastLock?.takeIf { !it.isHeld }?.let {
                it.acquire()
                Timber.tag(TAG).d("Acquired WifiManager.MulticastLock for mDNS discovery")
            }
        }

        pendingStopPairing = false
        pendingStopConnect = false
        safely("Error starting pairing discovery") {
            if (!isPairingDiscoveryActive) manager.discoverServices("$SERVICE_TYPE_PAIRING._tcp", NsdManager.PROTOCOL_DNS_SD, pairingDiscoveryListener)
        }
        safely("Error starting connect discovery") {
            if (!isConnectDiscoveryActive) manager.discoverServices("$SERVICE_TYPE_CONNECT._tcp", NsdManager.PROTOCOL_DNS_SD, connectDiscoveryListener)
        }
    }

    /**
     * Stops all active mDNS discovery listeners and shuts down background probing.
     */
    fun stopDiscovery() {
        isStopped = true
        safely("Failed releasing WifiManager.MulticastLock") {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Timber.tag(TAG).d("Released WifiManager.MulticastLock")
            }
        }

        if (nsdManager != null) {
            stopChannel(isPairingDiscoveryActive, pairingDiscoveryListener, isPairing = true, "pairing")
            stopChannel(isConnectDiscoveryActive, connectDiscoveryListener, isPairing = false, "connect")
            unregisterAllCallbacks()
        }

        synchronized(this) {
            resolveQueue.clear()
            isResolving = false
            lastDiscoveredHost = null
            probeExecutor?.shutdownNow()
            probeExecutor = null
        }
    }

    private fun stopChannel(isActive: Boolean, listener: NsdManager.DiscoveryListener, isPairing: Boolean, label: String) {
        if (isActive) {
            safely("Error stopping $label discovery") { nsdManager?.stopServiceDiscovery(listener) }
            updateChannel(isPairing, active = false, pending = false)
        } else {
            updateChannel(isPairing, active = false, pending = true)
        }
    }

    @Synchronized
    private fun resolveService(serviceInfo: NsdServiceInfo, onResolved: (Int, String?) -> Unit) {
        if (isStopped) return
        resolveQueue.add(serviceInfo to onResolved)
        processNextResolveLocked()
    }

    @Synchronized
    private fun processNextResolveLocked() {
        if (isStopped || isResolving || resolveQueue.isEmpty()) return
        val (serviceInfo, onResolved) = resolveQueue.removeFirstOrNull() ?: return
        val manager = nsdManager ?: return

        isResolving = true
        try {
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(failedInfo: NsdServiceInfo, errorCode: Int) {
                    Timber.tag(TAG).w("Failed to resolve service ${failedInfo.serviceName}: error $errorCode")
                    finishResolveStep()
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    try {
                        val port = resolvedInfo.port
                        val host = resolvedInfo.host?.hostAddress
                        if (!isStopped && port > 0) onResolved(port, host)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Exception in onResolved callback for ${serviceInfo.serviceName}")
                    } finally {
                        finishResolveStep()
                    }
                }
            })
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Exception resolving service: ${serviceInfo.serviceName}")
            finishResolveStep()
        }
    }

    private fun finishResolveStep() {
        synchronized(this@AdbMdnsDiscovery) {
            isResolving = false
            processNextResolveLocked()
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
                false
            } catch (_: java.io.IOException) {
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
