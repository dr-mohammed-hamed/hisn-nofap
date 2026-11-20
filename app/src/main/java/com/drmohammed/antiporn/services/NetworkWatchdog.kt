package com.drmohammed.antiporn.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Encapsulates network connectivity monitoring to close the "Boot-without-Network Loophole"
 * and record deduplicated physical network transitions to [BlackBoxRecorder].
 * Cleanly registers a [ConnectivityManager.NetworkCallback] and safely unregisters to prevent leaks.
 */
class NetworkWatchdog {

    companion object {
        private const val TAG = "AntiPorn_NetWatchdog"

        internal data class NetworkStateSnapshot(
            val transport: String,
            val isValidated: Boolean,
            val isCaptivePortal: Boolean
        )

        internal data class NetworkTransitionEvent(
            val action: String,
            val isSuccess: Boolean,
            val details: String?,
            val error: String?
        )

        /**
         * Resolves a human-readable transport label from [NetworkCapabilities].
         */
        internal fun resolveTransportLabel(caps: NetworkCapabilities?): String {
            if (caps == null) return "OTHER"
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "OTHER"
            }
        }

        /**
         * Builds an immutable [NetworkStateSnapshot] from [NetworkCapabilities].
         */
        internal fun buildSnapshot(caps: NetworkCapabilities?): NetworkStateSnapshot {
            return NetworkStateSnapshot(
                transport = resolveTransportLabel(caps),
                isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                isCaptivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
            )
        }

        /**
         * Computes a deterministic signature from a physical network's interface name, local link
         * addresses, and DNS servers so genuine IP/subnet roaming and DNS changes are detected
         * while ignoring identical callbacks.
         */
        internal fun buildLinkSignature(linkProperties: LinkProperties?): String {
            if (linkProperties == null) return ""
            return buildLinkSignature(
                interfaceName = linkProperties.interfaceName,
                linkAddresses = linkProperties.linkAddresses.orEmpty().mapNotNull { it?.address?.hostAddress },
                dnsServers = linkProperties.dnsServers.orEmpty().mapNotNull { it?.hostAddress }
            )
        }

        internal fun buildLinkSignature(
            interfaceName: String?,
            linkAddresses: Collection<String>,
            dnsServers: Collection<String>
        ): String {
            val iface = interfaceName.orEmpty()
            val sortedAddresses = linkAddresses.filter { it.isNotBlank() }.sorted().joinToString(",")
            val sortedDns = dnsServers.filter { it.isNotBlank() }.sorted().joinToString(",")
            return "$iface|$sortedAddresses|$sortedDns"
        }

        /**
         * Classifies meaningful capability transitions and suppresses identical snapshots
         * to prevent RSSI/bandwidth callback flapping floods.
         */
        internal fun classifyCapabilityTransition(
            previous: NetworkStateSnapshot?,
            current: NetworkStateSnapshot,
            networkId: String
        ): NetworkTransitionEvent? {
            if (previous == null) {
                return if (current.isCaptivePortal) {
                    NetworkTransitionEvent(
                        action = "NET_CAPTIVE_PORTAL",
                        isSuccess = false,
                        details = "transport=${current.transport}, net=$networkId",
                        error = "Captive portal / ISP login redirect detected"
                    )
                } else {
                    null
                }
            }

            if (previous == current) return null

            return when {
                !previous.isCaptivePortal && current.isCaptivePortal -> NetworkTransitionEvent(
                    action = "NET_CAPTIVE_PORTAL",
                    isSuccess = false,
                    details = "transport=${current.transport}, net=$networkId",
                    error = "Captive portal / ISP login redirect detected"
                )
                !previous.isValidated && current.isValidated -> NetworkTransitionEvent(
                    action = "NET_VALIDATED",
                    isSuccess = true,
                    details = "transport=${current.transport}, net=$networkId",
                    error = null
                )
                previous.isValidated && !current.isValidated -> NetworkTransitionEvent(
                    action = "NET_UNVALIDATED",
                    isSuccess = false,
                    details = "transport=${current.transport}, net=$networkId",
                    error = "Connected without internet validation (!NET_CAPABILITY_VALIDATED)"
                )
                previous.transport != current.transport -> NetworkTransitionEvent(
                    action = "NET_TRANSPORT_CHANGED",
                    isSuccess = true,
                    details = "${previous.transport}->${current.transport}, validated=${current.isValidated}, net=$networkId",
                    error = null
                )
                else -> null
            }
        }
    }

    private val callbackLock = Any()
    @Volatile
    private var isRegistered = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val statesByNetwork = ConcurrentHashMap<String, NetworkStateSnapshot>()
    private val linkSignaturesByNetwork = ConcurrentHashMap<String, String>()
    private val privateDnsStatesByNetwork = ConcurrentHashMap<String, Pair<Boolean, String?>>()

    /**
     * Registers a network callback that notifies when Internet connectivity is restored
     * and records physical network lifecycle and capability transitions.
     */
    fun register(context: Context, onNetworkAvailable: () -> Unit) {
        register(
            context = context,
            onNetworkChanged = null,
            onNetworkAvailable = onNetworkAvailable
        )
    }

    /**
     * Registers a network callback that notifies [onNetworkAvailable] when Internet connectivity
     * is restored and invokes [onNetworkChanged] on genuine physical network transitions
     * (excluding VPN interfaces and RSSI-only updates).
     */
    fun register(
        context: Context,
        onNetworkChanged: ((reason: String) -> Unit)?,
        onNetworkAvailable: () -> Unit
    ) {
        register(
            context = context,
            onNetworkChanged = onNetworkChanged,
            onDetailedNetworkTransition = null,
            onNetworkAvailable = onNetworkAvailable
        )
    }

    /**
     * Extended registration overload passing the target [Network] and transport label
     * to support fine-grained per-network DNS resilience and anti-flapping guards.
     */
    fun register(
        context: Context,
        onNetworkChanged: ((reason: String) -> Unit)? = null,
        onDetailedNetworkTransition: ((reason: String, network: Network?, transport: String?) -> Unit)? = null,
        onNetworkAvailable: () -> Unit
    ) {
        synchronized(callbackLock) {
            if (networkCallback != null) return

            try {
                val appContext = context.applicationContext
                val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (manager == null) {
                    Timber.tag(TAG).w("ConnectivityManager unavailable; skipping NetworkWatchdog registration.")
                    return
                }

                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // Rely on onCapabilitiesChanged (guaranteed immediately after onAvailable)
                        // instead of synchronous ConnectivityManager.getNetworkCapabilities() IPC.
                        if (!isRegistered) return
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        if (!isRegistered) return
                        try {
                            val netKey = network.toString()
                            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                                statesByNetwork.remove(netKey)
                                linkSignaturesByNetwork.remove(netKey)
                                return
                            }

                            val current = buildSnapshot(networkCapabilities)
                            val previous = statesByNetwork.put(netKey, current)
                            if (previous == null) {
                                recordNetworkAvailable(appContext, current, netKey)
                                if (current.isCaptivePortal) {
                                    classifyCapabilityTransition(null, current, netKey)?.let { event ->
                                        recordTransitionEvent(appContext, event)
                                    }
                                    onNetworkChanged?.invoke("net_captive_portal:${current.transport}")
                                    onDetailedNetworkTransition?.invoke("net_captive_portal:${current.transport}", network, current.transport)
                                } else {
                                    onNetworkChanged?.invoke("net_available:${current.transport}")
                                    onDetailedNetworkTransition?.invoke("net_available:${current.transport}", network, current.transport)
                                }
                                onNetworkAvailable.invoke()
                                return
                            }

                            val event = classifyCapabilityTransition(previous, current, netKey) ?: return
                            recordTransitionEvent(appContext, event)
                            when (event.action) {
                                "NET_VALIDATED" -> {
                                    onNetworkChanged?.invoke("net_validated:${current.transport}")
                                    onDetailedNetworkTransition?.invoke("net_validated:${current.transport}", network, current.transport)
                                    onNetworkAvailable.invoke()
                                }
                                "NET_UNVALIDATED" -> {
                                    onNetworkChanged?.invoke("net_unvalidated:${current.transport}")
                                    onDetailedNetworkTransition?.invoke("net_unvalidated:${current.transport}", network, current.transport)
                                }
                                "NET_CAPTIVE_PORTAL" -> {
                                    onNetworkChanged?.invoke("net_captive_portal:${current.transport}")
                                    onDetailedNetworkTransition?.invoke("net_captive_portal:${current.transport}", network, current.transport)
                                }
                                "NET_TRANSPORT_CHANGED" -> {
                                    onNetworkChanged?.invoke("net_transport_changed:${previous.transport}->${current.transport}")
                                    onDetailedNetworkTransition?.invoke("net_transport_changed:${previous.transport}->${current.transport}", network, current.transport)
                                }
                            }
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Error handling onCapabilitiesChanged for network=$network")
                        }
                    }

                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                        if (!isRegistered) return
                        try {
                            val netKey = network.toString()
                            // Only tracked non-VPN physical networks appear in statesByNetwork.
                            if (!statesByNetwork.containsKey(netKey)) {
                                return
                            }
                            val signature = buildLinkSignature(linkProperties)
                            val previousSignature = linkSignaturesByNetwork.put(netKey, signature)
                            if (previousSignature != null && previousSignature != signature) {
                                Timber.tag(TAG).i("Physical network link properties changed: net=%s", netKey)
                                onNetworkChanged?.invoke("net_link_changed")
                                onDetailedNetworkTransition?.invoke("net_link_changed", network, statesByNetwork[netKey]?.transport)
                            }
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                val isPrivateDns = linkProperties.isPrivateDnsActive
                                val dnsServerName = linkProperties.privateDnsServerName
                                val currentDnsState = isPrivateDns to dnsServerName
                                val previousDnsState = privateDnsStatesByNetwork.put(netKey, currentDnsState)
                                if (previousDnsState == null || previousDnsState != currentDnsState) {
                                    BlackBoxRecorder.recordNetwork(
                                        context = appContext,
                                        action = "NET_LINK_PROPERTIES",
                                        isSuccess = true,
                                        details = "net=$netKey, privateDnsActive=$isPrivateDns, server=$dnsServerName"
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Error handling onLinkPropertiesChanged for network=$network")
                        }
                    }

                    override fun onLost(network: Network) {
                        if (!isRegistered) return
                        try {
                            val netKey = network.toString()
                            linkSignaturesByNetwork.remove(netKey)
                            privateDnsStatesByNetwork.remove(netKey)
                            val removed = statesByNetwork.remove(netKey) ?: return

                            Timber.tag(TAG).i(
                                "Network connection lost: transport=%s, wasValidated=%s, net=%s",
                                removed.transport,
                                removed.isValidated,
                                netKey
                            )
                            BlackBoxRecorder.recordNetwork(
                                context = appContext,
                                action = "NET_LOST",
                                isSuccess = true,
                                details = "transport=${removed.transport}, wasValidated=${removed.isValidated}, net=$netKey"
                            )
                            onNetworkChanged?.invoke("net_lost:${removed.transport}")
                            onDetailedNetworkTransition?.invoke("net_lost:${removed.transport}", network, removed.transport)
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Error handling onLost for network=$network")
                        }
                    }
                }

                connectivityManager = manager
                networkCallback = callback
                isRegistered = true
                manager.registerNetworkCallback(request, callback)
                Timber.tag(TAG).i("NetworkWatchdog registered successfully.")
            } catch (e: Exception) {
                isRegistered = false
                statesByNetwork.clear()
                linkSignaturesByNetwork.clear()
                networkCallback = null
                connectivityManager = null
                Timber.tag(TAG).e(e, "Failed to register NetworkWatchdog callback")
            }
        }
    }

    /**
     * Unregisters the callback cleanly to prevent memory or context leaks.
     */
    fun unregister() {
        synchronized(callbackLock) {
            isRegistered = false
            statesByNetwork.clear()
            linkSignaturesByNetwork.clear()
            privateDnsStatesByNetwork.clear()
            networkCallback?.let {
                try {
                    connectivityManager?.unregisterNetworkCallback(it)
                    Timber.tag(TAG).i("NetworkWatchdog unregistered.")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to unregister NetworkWatchdog callback")
                }
                networkCallback = null
                connectivityManager = null
            }
        }
    }

    private fun recordNetworkAvailable(appContext: Context, snapshot: NetworkStateSnapshot, netKey: String) {
        Timber.tag(TAG).i(
            "Internet connection detected as available: transport=%s, validated=%s, captive=%s, net=%s",
            snapshot.transport,
            snapshot.isValidated,
            snapshot.isCaptivePortal,
            netKey
        )
        BlackBoxRecorder.recordNetwork(
            context = appContext,
            action = "NET_AVAILABLE",
            isSuccess = true,
            details = "transport=${snapshot.transport}, validated=${snapshot.isValidated}, captive=${snapshot.isCaptivePortal}, net=$netKey"
        )
    }

    private fun recordTransitionEvent(appContext: Context, event: NetworkTransitionEvent) {
        if (event.isSuccess) {
            Timber.tag(TAG).i("Network transition [%s]: %s", event.action, event.details)
        } else {
            Timber.tag(TAG).w("Network transition [%s]: %s (%s)", event.action, event.error, event.details)
        }
        BlackBoxRecorder.recordNetwork(
            context = appContext,
            action = event.action,
            isSuccess = event.isSuccess,
            details = event.details,
            error = event.error
        )
    }
}
