package com.drmohammed.antiporn.provisioning.service

internal data class PortPromptSnapshot(
    val pairingPort: Int,
    val connectPort: Int,
    val shouldPrompt: Boolean
)

internal data class ServiceLostSnapshot(
    val pairingPort: Int,
    val connectPort: Int,
    val shouldCancelPrompt: Boolean,
    val shouldBroadcast: Boolean
)

internal data class ClearStickySnapshot(
    val cleared: Boolean,
    val shouldRefreshPrompt: Boolean,
    val pairingPort: Int,
    val connectPort: Int
)

/**
 * Thread-safe state holder for mDNS pairing/connect port discovery, heads-up prompt
 * deduplication, active promotion guards, and sticky outcome lifecycle.
 */
internal class AdbPairingPromptState {

    private val lock = Any()

    @Volatile
    var capturedPairingPort: Int = 0
        private set

    @Volatile
    var capturedConnectPort: Int = 0
        private set

    @Volatile
    var lastPromptedPairingPort: Int = 0
        private set

    @Volatile
    var isPromoting: Boolean = false
        private set

    @Volatile
    var isShowingStickyOutcome: Boolean = false
        private set

    @Volatile
    var stickyOutcomePairingPort: Int? = null
        private set

    fun currentPorts(): Pair<Int, Int> = synchronized(lock) {
        capturedPairingPort to capturedConnectPort
    }

    fun currentPortSnapshot(): PortPromptSnapshot = synchronized(lock) {
        buildPortPromptSnapshotLocked()
    }

    fun onPairingPortDiscovered(port: Int): PortPromptSnapshot = synchronized(lock) {
        if (port <= 0) {
            return@synchronized PortPromptSnapshot(capturedPairingPort, capturedConnectPort, shouldPrompt = false)
        }
        capturedPairingPort = port
        // A different ephemeral pairing port means the user opened a fresh pairing dialog,
        // so an earlier failure/blocking-users sticky notice must no longer suppress prompts.
        if (isShowingStickyOutcome && port != stickyOutcomePairingPort) {
            isShowingStickyOutcome = false
            stickyOutcomePairingPort = null
        }
        buildPortPromptSnapshotLocked()
    }

    fun onConnectPortDiscovered(port: Int): PortPromptSnapshot = synchronized(lock) {
        if (port <= 0) {
            return@synchronized PortPromptSnapshot(capturedPairingPort, capturedConnectPort, shouldPrompt = false)
        }
        capturedConnectPort = port
        buildPortPromptSnapshotLocked()
    }

    fun refreshPromptPorts(isExternalPromotionActive: Boolean): Pair<Int, Int>? = synchronized(lock) {
        if (capturedPairingPort > 0 && !isPromoting && !isShowingStickyOutcome && !isExternalPromotionActive) {
            capturedPairingPort to capturedConnectPort
        } else {
            null
        }
    }

    fun onPairingServiceLost(): ServiceLostSnapshot = synchronized(lock) {
        if (isPromoting) {
            return@synchronized ServiceLostSnapshot(
                pairingPort = capturedPairingPort,
                connectPort = capturedConnectPort,
                shouldCancelPrompt = false,
                shouldBroadcast = false
            )
        }
        capturedPairingPort = 0
        lastPromptedPairingPort = 0
        ServiceLostSnapshot(
            pairingPort = 0,
            connectPort = capturedConnectPort,
            shouldCancelPrompt = !isShowingStickyOutcome,
            shouldBroadcast = true
        )
    }

    fun onConnectServiceLost(): ServiceLostSnapshot = synchronized(lock) {
        if (isPromoting) {
            return@synchronized ServiceLostSnapshot(
                pairingPort = capturedPairingPort,
                connectPort = capturedConnectPort,
                shouldCancelPrompt = false,
                shouldBroadcast = false
            )
        }
        capturedConnectPort = 0
        ServiceLostSnapshot(
            pairingPort = capturedPairingPort,
            connectPort = 0,
            shouldCancelPrompt = false,
            shouldBroadcast = true
        )
    }

    fun tryBeginPromotion(isExternalPromotionActive: Boolean = false): Boolean = synchronized(lock) {
        if (isPromoting || isExternalPromotionActive) {
            return@synchronized false
        }
        isPromoting = true
        isShowingStickyOutcome = false
        stickyOutcomePairingPort = null
        true
    }

    fun markPromotionSuccess() = synchronized(lock) {
        isPromoting = false
        isShowingStickyOutcome = false
        stickyOutcomePairingPort = null
    }

    fun markStickyOutcome(pairingPort: Int?) = synchronized(lock) {
        isPromoting = false
        isShowingStickyOutcome = true
        stickyOutcomePairingPort = pairingPort?.takeIf { it > 0 } ?: capturedPairingPort.takeIf { it > 0 }
    }

    fun cancelPromotionWithoutStickyOutcome() = synchronized(lock) {
        isPromoting = false
    }

    fun clearStickyOutcome(isExternalPromotionActive: Boolean = false): ClearStickySnapshot = synchronized(lock) {
        if (isPromoting || isExternalPromotionActive) {
            return@synchronized ClearStickySnapshot(
                cleared = false,
                shouldRefreshPrompt = false,
                pairingPort = capturedPairingPort,
                connectPort = capturedConnectPort
            )
        }
        isShowingStickyOutcome = false
        stickyOutcomePairingPort = null
        val shouldRefresh = capturedPairingPort > 0
        lastPromptedPairingPort = if (shouldRefresh) capturedPairingPort else 0
        ClearStickySnapshot(
            cleared = true,
            shouldRefreshPrompt = shouldRefresh,
            pairingPort = capturedPairingPort,
            connectPort = capturedConnectPort
        )
    }

    fun reset() = synchronized(lock) {
        capturedPairingPort = 0
        capturedConnectPort = 0
        lastPromptedPairingPort = 0
        isPromoting = false
        isShowingStickyOutcome = false
        stickyOutcomePairingPort = null
    }

    private fun buildPortPromptSnapshotLocked(): PortPromptSnapshot {
        val shouldPrompt = capturedPairingPort > 0 &&
            capturedPairingPort != lastPromptedPairingPort &&
            !isPromoting &&
            !isShowingStickyOutcome
        if (shouldPrompt) {
            lastPromptedPairingPort = capturedPairingPort
        }
        return PortPromptSnapshot(
            pairingPort = capturedPairingPort,
            connectPort = capturedConnectPort,
            shouldPrompt = shouldPrompt
        )
    }
}
