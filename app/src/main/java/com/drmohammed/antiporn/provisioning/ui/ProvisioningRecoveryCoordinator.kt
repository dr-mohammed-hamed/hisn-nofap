package com.drmohammed.antiporn.provisioning.ui

import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.provisioning.engine.AdbShellValidation
import com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter
import com.drmohammed.antiporn.provisioning.engine.HealingOutcome
import com.drmohammed.antiporn.provisioning.engine.ProvisioningFreezeRecoveryStore
import com.drmohammed.antiporn.provisioning.service.AdbPairingNotificationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Coordinates freeze-recovery state, dialogs, and quick Wireless ADB healing for [ProvisioningWizardActivity].
 */
internal class ProvisioningRecoveryCoordinator {

    private val recoveryDialogHelper = ProvisioningRecoveryDialogHelper()

    var isHealing: Boolean = false
        private set

    var hasPendingFreezeRecovery: Boolean = false
        private set

    var recoveryDialogDismissedByUser: Boolean = false

    private var pendingRecoveryHealingHint: String? = null

    fun isShowing(): Boolean = recoveryDialogHelper.isShowing()

    fun dismiss() = recoveryDialogHelper.dismiss()

    fun markRequestedFromIntent() {
        hasPendingFreezeRecovery = true
        recoveryDialogDismissedByUser = false
    }

    fun restoreState(savedInstanceState: Bundle) {
        hasPendingFreezeRecovery = savedInstanceState.getBoolean(KEY_HAS_PENDING_RECOVERY, false)
        recoveryDialogDismissedByUser = savedInstanceState.getBoolean(KEY_RECOVERY_DISMISSED, false)
        pendingRecoveryHealingHint = savedInstanceState.getString(KEY_RECOVERY_HEALING_HINT)
    }

    fun saveState(outState: Bundle) {
        outState.putBoolean(KEY_HAS_PENDING_RECOVERY, hasPendingFreezeRecovery)
        outState.putBoolean(KEY_RECOVERY_DISMISSED, recoveryDialogDismissedByUser)
        (recoveryDialogHelper.currentHealingHint() ?: pendingRecoveryHealingHint)?.let {
            outState.putString(KEY_RECOVERY_HEALING_HINT, it)
        }
    }

    fun reconcileOnDeviceOwnerResume(
        activity: AppCompatActivity,
        canShowDialog: () -> Boolean,
        discoveredConnectPort: () -> Int,
        onConnectPortDiscovered: (Int) -> Unit,
        onBusyChanged: (Int?) -> Unit,
        onValidationNeeded: () -> Unit,
        onDeviceOwnerConfirmed: () -> Unit
    ) {
        val appContext = activity.applicationContext
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val stranded = ProvisioningFreezeRecoveryStore.findStillDisabledTrackedPackages(appContext)
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                if (stranded.isEmpty()) {
                    onDeviceOwnerConfirmed()
                } else {
                    hasPendingFreezeRecovery = true
                    recoveryDialogDismissedByUser = false
                    val svcConnectPort = AdbPairingNotificationService.capturedConnectPort
                    if (svcConnectPort > 0) onConnectPortDiscovered(svcConnectPort)
                    checkAndShowIfNeeded(
                        activity = activity,
                        canShowDialog = canShowDialog,
                        discoveredConnectPort = discoveredConnectPort,
                        onBusyChanged = onBusyChanged,
                        onValidationNeeded = onValidationNeeded,
                        onDeviceOwnerConfirmed = onDeviceOwnerConfirmed
                    )
                }
            }
        }
    }

    fun checkAndShowIfNeeded(
        activity: AppCompatActivity,
        canShowDialog: () -> Boolean,
        discoveredConnectPort: () -> Int,
        onBusyChanged: (Int?) -> Unit,
        onValidationNeeded: () -> Unit,
        onDeviceOwnerConfirmed: () -> Unit,
        healingHint: String? = pendingRecoveryHealingHint
    ) {
        if (activity.isFinishing || activity.isDestroyed || !canShowDialog() || recoveryDialogDismissedByUser) return
        val appContext = activity.applicationContext
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val stranded = ProvisioningFreezeRecoveryStore.findStillDisabledTrackedPackages(appContext)
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                if (stranded.isEmpty()) {
                    hasPendingFreezeRecovery = false
                    pendingRecoveryHealingHint = null
                    AdbShellValidation.cancelFreezeRecoveryNotification(appContext)
                    if (ProtectionEngine.isDeviceOwner(activity)) {
                        onDeviceOwnerConfirmed()
                    }
                    return@withContext
                }
                hasPendingFreezeRecovery = true
                if (canShowDialog() && !recoveryDialogDismissedByUser) {
                    pendingRecoveryHealingHint = healingHint
                    recoveryDialogHelper.show(
                        activity = activity,
                        packages = stranded,
                        healingHint = healingHint,
                        onQuickAdbHeal = {
                            executeQuickAdbHeal(
                                activity = activity,
                                canShowDialog = canShowDialog,
                                discoveredConnectPort = discoveredConnectPort,
                                onBusyChanged = onBusyChanged,
                                onValidationNeeded = onValidationNeeded,
                                onDeviceOwnerConfirmed = onDeviceOwnerConfirmed
                            )
                        },
                        onDismissedByUser = { recoveryDialogDismissedByUser = true }
                    )
                }
            }
        }
    }

    private fun executeQuickAdbHeal(
        activity: AppCompatActivity,
        canShowDialog: () -> Boolean,
        discoveredConnectPort: () -> Int,
        onBusyChanged: (Int?) -> Unit,
        onValidationNeeded: () -> Unit,
        onDeviceOwnerConfirmed: () -> Unit
    ) {
        if (isHealing || DeviceOwnerPromoter.isPromotionActive || !canShowDialog()) {
            Toast.makeText(activity, R.string.provisioning_error_already_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        val connPort = AdbPairingNotificationService.capturedConnectPort.takeIf { it > 0 } ?: discoveredConnectPort()
        val isWifiAdbEnabled = runCatching {
            Settings.Global.getInt(activity.contentResolver, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false)
        if (connPort <= 0 && !isWifiAdbEnabled) {
            val hint = activity.getString(R.string.provisioning_recovery_adb_unavailable_hint)
            pendingRecoveryHealingHint = hint
            recoveryDialogDismissedByUser = false
            Toast.makeText(activity, R.string.provisioning_error_heal_failed, Toast.LENGTH_LONG).show()
            checkAndShowIfNeeded(
                activity, canShowDialog, discoveredConnectPort, onBusyChanged, onValidationNeeded, onDeviceOwnerConfirmed, hint
            )
            return
        }

        isHealing = true
        onBusyChanged(R.string.provisioning_in_progress_title)
        val appContext = activity.applicationContext
        activity.lifecycleScope.launch {
            try {
                val outcome = withContext(NonCancellable + Dispatchers.IO) {
                    DeviceOwnerPromoter.healStrandedPackagesViaAdb(appContext, connPort)
                }
                isHealing = false
                if (!activity.isFinishing && !activity.isDestroyed) {
                    onBusyChanged(null)
                    handleHealingOutcome(
                        activity, outcome, canShowDialog, discoveredConnectPort, onBusyChanged, onValidationNeeded, onDeviceOwnerConfirmed
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Timber.tag(TAG).e(t, "Unexpected error during quick ADB heal")
                isHealing = false
                if (!activity.isFinishing && !activity.isDestroyed) {
                    onBusyChanged(null)
                    val hint = activity.getString(R.string.provisioning_recovery_adb_unavailable_hint)
                    pendingRecoveryHealingHint = hint
                    recoveryDialogDismissedByUser = false
                    checkAndShowIfNeeded(
                        activity, canShowDialog, discoveredConnectPort, onBusyChanged, onValidationNeeded, onDeviceOwnerConfirmed, hint
                    )
                }
            } finally {
                isHealing = false
                if (!activity.isFinishing && !activity.isDestroyed) onValidationNeeded()
            }
        }
    }

    private fun handleHealingOutcome(
        activity: AppCompatActivity,
        outcome: HealingOutcome,
        canShowDialog: () -> Boolean,
        discoveredConnectPort: () -> Int,
        onBusyChanged: (Int?) -> Unit,
        onValidationNeeded: () -> Unit,
        onDeviceOwnerConfirmed: () -> Unit
    ) {
        when (outcome) {
            is HealingOutcome.Healed -> {
                hasPendingFreezeRecovery = false
                pendingRecoveryHealingHint = null
                AdbShellValidation.cancelFreezeRecoveryNotification(activity)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.provisioning_recovery_toast_healed, outcome.healedCount),
                    Toast.LENGTH_LONG
                ).show()
                if (ProtectionEngine.isDeviceOwner(activity)) onDeviceOwnerConfirmed()
            }
            is HealingOutcome.NothingToHeal -> {
                hasPendingFreezeRecovery = false
                pendingRecoveryHealingHint = null
                AdbShellValidation.cancelFreezeRecoveryNotification(activity)
                Toast.makeText(activity, R.string.provisioning_recovery_toast_already_restored, Toast.LENGTH_SHORT).show()
                if (ProtectionEngine.isDeviceOwner(activity)) onDeviceOwnerConfirmed()
            }
            is HealingOutcome.NotPaired -> {
                Toast.makeText(activity, R.string.provisioning_recovery_toast_not_paired, Toast.LENGTH_LONG).show()
                val hint = activity.getString(R.string.provisioning_recovery_adb_unavailable_hint)
                pendingRecoveryHealingHint = hint
                recoveryDialogDismissedByUser = false
                checkAndShowIfNeeded(
                    activity, canShowDialog, discoveredConnectPort, onBusyChanged, onValidationNeeded, onDeviceOwnerConfirmed, hint
                )
            }
            is HealingOutcome.Failed -> {
                Toast.makeText(activity, outcome.message, Toast.LENGTH_LONG).show()
                val hint = activity.getString(R.string.provisioning_recovery_adb_unavailable_hint)
                pendingRecoveryHealingHint = hint
                recoveryDialogDismissedByUser = false
                checkAndShowIfNeeded(
                    activity, canShowDialog, discoveredConnectPort, onBusyChanged, onValidationNeeded, onDeviceOwnerConfirmed, hint
                )
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_WizRecCoord"
        private const val KEY_RECOVERY_HEALING_HINT = "key_recovery_healing_hint"
        private const val KEY_RECOVERY_DISMISSED = "key_recovery_dismissed"
        private const val KEY_HAS_PENDING_RECOVERY = "key_has_pending_recovery"
    }
}
