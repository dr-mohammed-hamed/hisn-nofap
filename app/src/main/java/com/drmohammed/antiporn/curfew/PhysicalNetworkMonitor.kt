package com.drmohammed.antiporn.curfew

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.drmohammed.antiporn.services.NetworkWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe physical network transition tracker that classifies genuine physical
 * network changes (new network, transport change, validation recovery, DNS/IP link change, loss)
 * while strictly filtering out VPN networks and RSSI/bandwidth-only capability flapping.
 */
internal class PhysicalNetworkChangeTracker {
    private data class TrackedNetworkState(
        val transport: String,
        val isValidated: Boolean,
        val linkSignature: String? = null
    )

    private val lock = Any()
    private val trackedNetworks = HashMap<String, TrackedNetworkState>()

    fun onNetworkObserved(
        networkId: String,
        transport: String,
        isValidated: Boolean,
        isVpn: Boolean = false
    ): String? = synchronized(lock) {
        if (isVpn || transport == "VPN") {
            trackedNetworks.remove(networkId)
            return null
        }
        val previous = trackedNetworks[networkId]
        if (previous == null) {
            trackedNetworks[networkId] = TrackedNetworkState(
                transport = transport,
                isValidated = isValidated
            )
            return "physical_net_available:$transport"
        }
        if (previous.transport == transport && previous.isValidated == isValidated) {
            return null
        }
        trackedNetworks[networkId] = previous.copy(
            transport = transport,
            isValidated = isValidated
        )
        return when {
            previous.transport != transport ->
                "physical_net_transport_changed:${previous.transport}->$transport"
            !previous.isValidated && isValidated ->
                "physical_net_validated:$transport"
            previous.isValidated && !isValidated ->
                "physical_net_unvalidated:$transport"
            else -> null
        }
    }

    fun onLinkPropertiesChanged(
        networkId: String,
        linkSignature: String,
        isVpn: Boolean = false
    ): String? = synchronized(lock) {
        if (isVpn) {
            trackedNetworks.remove(networkId)
            return null
        }
        val previous = trackedNetworks[networkId] ?: return null
        if (previous.linkSignature == null) {
            trackedNetworks[networkId] = previous.copy(linkSignature = linkSignature)
            return null
        }
        if (previous.linkSignature == linkSignature) {
            return null
        }
        trackedNetworks[networkId] = previous.copy(linkSignature = linkSignature)
        return "physical_net_link_changed:${previous.transport}"
    }

    fun onNetworkLost(networkId: String): String? = synchronized(lock) {
        val removed = trackedNetworks.remove(networkId) ?: return null
        "physical_net_lost:${removed.transport}"
    }

    fun clear() = synchronized(lock) {
        trackedNetworks.clear()
    }
}

/**
 * Leading + trailing-edge coalescer for burst network transitions.
 * Fires immediately on the first transition, and if additional transitions arrive within
 * [coalesceMs] (e.g. make-before-break `onAvailable` -> `onLost` or `onAvailable` -> `onValidated`),
 * schedules a single trailing flush so recovery events are never dropped.
 */
internal class NetworkChangeCoalescer(
    private val scope: CoroutineScope,
    private val clockMs: () -> Long,
    private val coalesceMs: Long = DEFAULT_COALESCE_MS,
    private val isDestroyed: () -> Boolean,
    private val onFlush: suspend (reason: String) -> Unit
) {
    private val lastFlushMs = AtomicLong(UNSET_TIMESTAMP_MS)
    private val trailingScheduled = AtomicBoolean(false)
    private val pendingTrailingReason = AtomicReference<String?>(null)

    fun notify(reason: String) {
        if (isDestroyed()) return
        val now = clockMs()
        while (true) {
            val prev = lastFlushMs.get()
            val delta = if (prev == UNSET_TIMESTAMP_MS) Long.MAX_VALUE else now - prev
            if (delta < coalesceMs) {
                pendingTrailingReason.set(reason)
                if (trailingScheduled.compareAndSet(false, true)) {
                    val waitMs = (coalesceMs - delta).coerceAtLeast(1L)
                    scope.launch {
                        try {
                            delay(waitMs)
                            trailingScheduled.set(false)
                            val trailingReason = pendingTrailingReason.getAndSet(null) ?: reason
                            if (!isDestroyed()) {
                                lastFlushMs.set(clockMs())
                                onFlush(trailingReason)
                            }
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            trailingScheduled.set(false)
                        } catch (e: Exception) {
                            trailingScheduled.set(false)
                            Timber.tag(TAG).w(e, "Failed trailing network change flush ($reason)")
                        }
                    }
                } else {
                    Timber.tag(TAG).d("Coalescing duplicate network change into pending trailing flush ($reason, delta=${delta}ms)")
                }
                return
            }
            if (lastFlushMs.compareAndSet(prev, now)) {
                break
            }
        }

        scope.launch {
            try {
                onFlush(reason)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed handling network change flush ($reason)")
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_NetMonitor"
        internal const val DEFAULT_COALESCE_MS = 300L
        private const val UNSET_TIMESTAMP_MS = Long.MIN_VALUE
    }
}

/**
 * Encapsulates [ConnectivityManager.NetworkCallback] registration for physical (non-VPN)
 * networks used by [CurfewBlackholeVpnService] to flush stale DNS/DoT sockets on handoff.
 */
internal class PhysicalNetworkMonitor(
    private val appContext: Context,
    private val isDestroyed: () -> Boolean,
    private val onNetworkChanged: (reason: String) -> Unit
) {
    private val lock = Any()
    @Volatile
    private var isRegistered = false
    private val tracker = PhysicalNetworkChangeTracker()
    private var connectivityManager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun register() {
        synchronized(lock) {
            if (callback != null || isDestroyed()) return
            try {
                val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (manager == null) {
                    Timber.tag(TAG).w("ConnectivityManager unavailable; skipping VPN physical network monitor")
                    return
                }
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()

                val netCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // Rely on onCapabilitiesChanged (delivered immediately after onAvailable)
                        // instead of synchronous ConnectivityManager.getNetworkCapabilities() IPC.
                        if (!isRegistered || isDestroyed()) return
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        if (!isRegistered || isDestroyed()) return
                        try {
                            val reason = tracker.onNetworkObserved(
                                networkId = network.toString(),
                                transport = NetworkWatchdog.resolveTransportLabel(networkCapabilities),
                                isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                                isVpn = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                            )
                            if (reason != null) onNetworkChanged(reason)
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Error handling physical network onCapabilitiesChanged")
                        }
                    }

                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                        if (!isRegistered || isDestroyed()) return
                        try {
                            // Request specifies NET_CAPABILITY_NOT_VPN and tracker ignores untracked/VPN networks.
                            val signature = NetworkWatchdog.buildLinkSignature(linkProperties)
                            val reason = tracker.onLinkPropertiesChanged(
                                networkId = network.toString(),
                                linkSignature = signature,
                                isVpn = false
                            )
                            if (reason != null) onNetworkChanged(reason)
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Error handling physical network onLinkPropertiesChanged")
                        }
                    }

                    override fun onLost(network: Network) {
                        if (!isRegistered || isDestroyed()) return
                        try {
                            val reason = tracker.onNetworkLost(network.toString())
                            if (reason != null) onNetworkChanged(reason)
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Error handling physical network onLost")
                        }
                    }
                }

                connectivityManager = manager
                callback = netCallback
                isRegistered = true
                manager.registerNetworkCallback(request, netCallback)
                Timber.tag(TAG).i("Registered VPN physical network change monitor")
            } catch (e: Exception) {
                isRegistered = false
                callback = null
                connectivityManager = null
                tracker.clear()
                Timber.tag(TAG).w(e, "Failed to register VPN physical network change monitor")
            }
        }
    }

    fun unregister() {
        synchronized(lock) {
            isRegistered = false
            tracker.clear()
            val registeredCallback = callback ?: return
            val manager = connectivityManager
            callback = null
            connectivityManager = null
            try {
                manager?.unregisterNetworkCallback(registeredCallback)
                Timber.tag(TAG).i("Unregistered VPN physical network change monitor")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to unregister VPN physical network change monitor")
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_NetMonitor"
    }
}
