package com.drmohammed.antiporn.watchdog

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.core.network.DnsFilteringValidator
import com.drmohammed.antiporn.core.network.PhysicalNetworkResolver
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import timber.log.Timber
import java.util.TimeZone

/**
 * Performs serialized protection shield, `Settings.Global` Private DNS, and Time/Timezone
 * anti-tamper audits for [ProtectionWatchdogRuntime].
 */
internal object ProtectionAuditEngine {

    private const val TAG = "AntiPorn_WatchAudit"

    fun runAudit(appContext: Context, scheduleCheck: (Long) -> Unit) {
        if (!ProtectionEngine.isDeviceOwner(appContext)) {
            Timber.tag(TAG).d("Not Device Owner, skipping watchdog enforcement")
            return
        }
        if (ProtectionTransitionGuard.isWithinSettleWindow()) {
            Timber.tag(TAG).d("System within transition/settle window. Postponing watchdog audit...")
            scheduleCheck(3500L)
            return
        }
        if (!ProtectionTransitionGuard.hasCompletedInitialEnforcement(appContext)) {
            Timber.tag(TAG).i("Initial Device Owner baseline not yet established. Applying shields silently...")
            val results = ProtectionEngine.applyAllSync(appContext)
            if (results.any { it.success }) {
                ProtectionTransitionGuard.markInitialEnforcementCompleted(appContext)
            }
            return
        }

        val shouldBlockBeActive = CurfewEnforcer.isEffectiveBlockActive(appContext)
        val wasBlockEnforced = CurfewConfig.isLastBlockEnforced(appContext)
        if (shouldBlockBeActive != wasBlockEnforced) {
            Timber.tag(TAG).i("Scheduled block transition pending ($wasBlockEnforced -> $shouldBlockBeActive). Reconciling.")
            CurfewEnforcer.syncBlockState(appContext)
            if (ProtectionTransitionGuard.isWithinSettleWindow()) {
                Timber.tag(TAG).d("Curfew reconciliation triggered internal transition. Postponing watchdog audit...")
                scheduleCheck(3500L)
                return
            }
        }

        val desyncReasons = mutableListOf<String>()
        val isPendingOfflineDpmSync = CurfewConfig.isPendingDpmPrivateDnsSync(appContext) &&
            !PhysicalNetworkResolver.isDevicePhysicallyOffline(appContext)

        inspectShields(appContext, isPendingOfflineDpmSync, desyncReasons)
        inspectSettingsGlobalDns(appContext, desyncReasons)
        inspectDateTimeLock(appContext, desyncReasons)

        if (desyncReasons.isNotEmpty()) {
            reEnforceDesyncedProtection(appContext, desyncReasons)
        }
        ApprovalRepository.getInstance(appContext).syncZeroTrustBaselineAndReconcileAsync()
    }

    private fun inspectShields(
        appContext: Context,
        isPendingOfflineDpmSync: Boolean,
        desyncReasons: MutableList<String>
    ) {
        val shieldStatuses = ProtectionEngine.evaluateSystemStatus(appContext)
        for ((shieldId, status) in shieldStatuses) {
            when (status) {
                is ShieldStatus.Active -> Unit
                is ShieldStatus.Unsupported ->
                    Timber.tag(TAG).d("Shield [$shieldId] unsupported on API (${status.minSdkRequired})")
                is ShieldStatus.NeedsEnforcement -> {
                    if (shieldId == DnsShield.id && isPendingOfflineDpmSync) {
                        Timber.tag(TAG).i("Pending offline DPM Private DNS convergence on newly connected network; syncing silently.")
                        desyncReasons.add("[DNS_PENDING_DPM_SYNC: ${status.reason}]")
                    } else {
                        desyncReasons.add("[$shieldId: ${status.reason}]")
                    }
                }
                is ShieldStatus.Disabled -> desyncReasons.add("[$shieldId: ${status.warning}]")
            }
        }
    }

    private fun inspectSettingsGlobalDns(appContext: Context, desyncReasons: MutableList<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val mode = Settings.Global.getString(appContext.contentResolver, "private_dns_mode")
            val specifier = Settings.Global.getString(appContext.contentResolver, "private_dns_specifier")
            val requiredMode = CurfewBlackholeVpnService.evaluateRequiredMode(appContext)
            val isVpnActive = CurfewVpnDnsBridge.isVpnConsentGranted(appContext) &&
                requiredMode != CurfewBlackholeVpnService.VpnMode.IDLE

            if (isVpnActive) {
                inspectVpnDnsSettings(appContext, mode, specifier, requiredMode, desyncReasons)
            } else {
                inspectStandaloneDnsSettings(appContext, mode, specifier, desyncReasons)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Could not read Settings.Global directly")
        }
    }

    @Suppress("DEPRECATION")
    private fun inspectVpnDnsSettings(
        appContext: Context,
        mode: String?,
        specifier: String?,
        requiredMode: CurfewBlackholeVpnService.VpnMode,
        desyncReasons: MutableList<String>
    ) {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isExternalVpnRunning = cm?.allNetworks?.any { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } == true && !CurfewBlackholeVpnService.isServiceOrTunnelActive()

        val policy = try {
            PolicyRepository.getInstance(appContext).getCurrentPolicy()
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Failed reading current policy during VPN audit")
            null
        }
        val allowExternalVpn = policy?.isBlockExternalVpnEnabled == false
        if (allowExternalVpn && isExternalVpnRunning) {
            Timber.tag(TAG).d("External VPN active; yielding local VPN service and preserving DNS protection.")
            return
        }

        val isAppropriate = mode == "opportunistic" || mode == "off" || mode == null
        val isUnaffiliatedFallback = CurfewVpnDnsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(appContext) &&
            mode == "hostname" && !specifier.isNullOrBlank() && DnsFilteringValidator.isKnownFamilyPreset(specifier)

        if (!isAppropriate && !isUnaffiliatedFallback) {
            val reason = "Settings.Global DNS mismatch: VPN required, expected mode='opportunistic' but found mode='$mode', specifier='$specifier'"
            Timber.tag(TAG).w(reason)
            desyncReasons.add("[DNS_GLOBAL: $reason]")
        }
        if (!CurfewBlackholeVpnService.isTunnelHealthy(requiredMode)) {
            Timber.tag(TAG).i("VPN service or DNS worker not healthy while required. Enqueuing silent sync.")
            CurfewBlackholeVpnService.syncPolicy(appContext)
        }
    }

    private fun inspectStandaloneDnsSettings(
        appContext: Context,
        mode: String?,
        specifier: String?,
        desyncReasons: MutableList<String>
    ) {
        val expectedHost = DnsShield.getEffectiveDnsHost(appContext)
        val isHostMatching = specifier?.equals(expectedHost, ignoreCase = true) == true
        if (mode != "hostname" || !isHostMatching) {
            val isUnaffiliatedFallback = CurfewVpnDnsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(appContext) &&
                mode == "hostname" && !specifier.isNullOrBlank() && DnsFilteringValidator.isKnownFamilyPreset(specifier)
            val isPendingCurfewRelease = (mode == "opportunistic" || mode.isNullOrEmpty()) &&
                specifier.isNullOrEmpty() && CurfewConfig.isDnsRestorePending(appContext)

            if (isUnaffiliatedFallback) {
                Timber.tag(TAG).i("Settings.Global DNS running verified fallback (%s) due to unaffiliated users.", specifier)
            } else if (isPendingCurfewRelease) {
                val reason = "Settings.Global DNS pending post-curfew restoration (mode=$mode, expected=$expectedHost)"
                Timber.tag(TAG).i(reason)
                desyncReasons.add("[DNS_PENDING_RESTORE: $reason]")
                CurfewVpnDnsBridge.restoreDnsShieldOnCurfewRelease(appContext)
            } else {
                val reason = "Settings.Global DNS mismatch (mode=$mode, specifier=$specifier, expected=$expectedHost)"
                Timber.tag(TAG).w(reason)
                desyncReasons.add("[DNS_GLOBAL: $reason]")
            }
        } else if (CurfewConfig.isDnsRestorePending(appContext)) {
            CurfewConfig.setDnsRestorePending(appContext, false)
        }
    }

    private fun inspectDateTimeLock(appContext: Context, desyncReasons: MutableList<String>) {
        val isTimeLocked = try {
            PolicyRepository.getInstance(appContext).getCurrentPolicy().isDateTimeLocked
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed reading dateTimeLocked policy")
            false
        }
        if (!isTimeLocked) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val targetTzId = CurfewConfig.getTargetTimeZoneId(appContext)
                val targetTz = TimeZone.getTimeZone(targetTzId)
                TimeZone.setDefault(null)
                val currentTz = TimeZone.getDefault()
                val trustedTime = TrustedTimeProvider.currentTimeMillis(appContext)
                val isTargetTz = currentTz.id == targetTzId || currentTz.hasSameRules(targetTz) ||
                    currentTz.getOffset(trustedTime) == targetTz.getOffset(trustedTime)
                if (!isTargetTz) {
                    val tzReason = "Timezone mismatch (current=${currentTz.id}, expected=$targetTzId)"
                    Timber.tag(TAG).w(tzReason)
                    desyncReasons.add("[TIMEZONE: $tzReason]")
                }
            }
            val autoTime = Settings.Global.getInt(appContext.contentResolver, Settings.Global.AUTO_TIME, 1)
            if (autoTime != 1) {
                val autoTimeReason = "Auto time disabled (autoTime=$autoTime)"
                Timber.tag(TAG).w(autoTimeReason)
                desyncReasons.add("[AUTO_TIME: $autoTimeReason]")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not verify Timezone or Auto Time directly in watchdog")
        }
    }

    private fun reEnforceDesyncedProtection(appContext: Context, desyncReasons: List<String>) {
        val isOnlySilentDpmSync = desyncReasons.all {
            it.startsWith("[DNS_PENDING_DPM_SYNC:") || it.startsWith("[DNS_PENDING_RESTORE:")
        }
        val detailedReasons = desyncReasons.joinToString(" | ")
        if (isOnlySilentDpmSync) {
            Timber.tag(TAG).i("Silent post-offline/curfew DNS convergence pending: $detailedReasons")
        } else {
            Timber.tag(TAG).i("Tampering or desync detected: $detailedReasons. Re-enforcing...")
            BlackBoxRecorder.record(
                context = appContext,
                tag = BlackBoxRecorder.TAG_SHIELD,
                actionName = "WATCHDOG_TAMPER_DETECTED",
                isSuccess = false,
                errorDetails = detailedReasons,
                metadata = "failedCount=${desyncReasons.size}"
            )
        }

        if (ProtectionTransitionGuard.isTeardownInProgress) {
            Timber.tag(TAG).d("Aborting watchdog re-enforcement because teardown is in progress")
            return
        }
        val isPurePendingDpmSync = desyncReasons.all { it.startsWith("[DNS_PENDING_DPM_SYNC:") }
        if (isPurePendingDpmSync) {
            ProtectionEngine.applyDnsShieldFastAsync(appContext)
        } else {
            ProtectionEngine.applyAllAsync(appContext)
        }

        val isDirectTamper = desyncReasons.any {
            it.startsWith("[TIMEZONE:") || it.startsWith("[AUTO_TIME:") || it.startsWith("[system_tamper:")
        }
        val isCurfewSynchronized = CurfewEnforcer.isEffectiveBlockActive(appContext) == CurfewConfig.isLastBlockEnforced(appContext)
        val isTransitionSafe = !ProtectionTransitionGuard.isWithinSettleWindow() &&
            !ProtectionTransitionGuard.isTeardownInProgress &&
            isCurfewSynchronized &&
            !CurfewConfig.isDnsRestorePending(appContext)

        if (isDirectTamper && isTransitionSafe) {
            try {
                AppNotificationManager.getInstance(appContext).postEventNotification(
                    category = NotificationCategory.SHIELDS,
                    title = appContext.getString(R.string.notif_tamper_alert_title),
                    message = appContext.getString(R.string.notif_tamper_alert_msg),
                    dedupKey = "tamper_alert_enforce",
                    cooldownMs = 15 * 60 * 1000L,
                    actionType = NotificationActionType.NONE,
                    systemNotificationId = NotificationRegistry.EVENT_SYSTEM_TAMPER_ID,
                    channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                    postSystemNotification = true,
                    importance = 3
                )
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error posting tamper alert notification")
            }
        }
    }
}
