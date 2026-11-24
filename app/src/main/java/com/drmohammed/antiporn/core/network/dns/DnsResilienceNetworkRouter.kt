package com.drmohammed.antiporn.core.network.dns

import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

/**
 * Constants and state tracking for same-network primary DoT recovery attempts.
 */
object DnsResilienceNetworkRouter {

    const val MAX_SAME_NET_RECOVERY_ATTEMPTS = 2
    const val CAPTIVE_PORTAL_RECOVERY_DELAY_MS = 3_000L
    val SAME_NET_RECOVERY_DELAYS_MS = longArrayOf(10L * 60_000L, 30L * 60_000L)

    val recoveryAttemptCountByNet = ConcurrentHashMap<String, Int>()

    @Volatile
    var activeRecoveryJob: Job? = null

    @Volatile
    var activeCaptivePortalRecoveryJob: Job? = null

    @VisibleForTesting
    internal var recoveryDelayOverride: (suspend (Long) -> Unit)? = null

    fun reset() {
        recoveryAttemptCountByNet.clear()
        activeRecoveryJob?.cancel()
        activeRecoveryJob = null
        activeCaptivePortalRecoveryJob?.cancel()
        activeCaptivePortalRecoveryJob = null
        recoveryDelayOverride = null
    }

    fun onNetworkLost(netId: String) {
        recoveryAttemptCountByNet.remove(netId)
        activeRecoveryJob?.cancel()
        activeRecoveryJob = null
        activeCaptivePortalRecoveryJob?.cancel()
        activeCaptivePortalRecoveryJob = null
    }
}
