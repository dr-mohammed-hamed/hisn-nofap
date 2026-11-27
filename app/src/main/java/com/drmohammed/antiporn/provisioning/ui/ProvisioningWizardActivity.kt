package com.drmohammed.antiporn.provisioning.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.provisioning.engine.AdbShellValidation
import com.drmohammed.antiporn.provisioning.engine.BlockingSecondaryUser
import com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter
import com.drmohammed.antiporn.provisioning.engine.OemPermissionType
import com.drmohammed.antiporn.provisioning.engine.PromotionResult
import com.drmohammed.antiporn.provisioning.engine.ProvisioningAckStore
import com.drmohammed.antiporn.provisioning.engine.ProvisioningFreezeRecoveryStore
import com.drmohammed.antiporn.provisioning.engine.ProvisioningWifiWatcher
import com.drmohammed.antiporn.provisioning.receiver.PairingCodeReceiver
import com.drmohammed.antiporn.provisioning.service.AdbPairingNotificationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Onboarding Setup Wizard for non-technical users to grant Device Owner permission
 * directly from the device with minimum friction.
 */
class ProvisioningWizardActivity : AppCompatActivity() {

    private val viewBinder = ProvisioningWizardViewBinder()
    private val outcomeDialogs = ProvisioningOutcomeDialogCoordinator()
    private val recoveryCoordinator = ProvisioningRecoveryCoordinator()
    private val dangerDialogHelper = ProvisioningDangerDialogHelper {
        if (!isFinishing && !isDestroyed) viewBinder.setupOemGuidance(this)
    }

    private var discoveredPairingPort = 0
    private var discoveredConnectPort = 0
    private var isPromoting = false
    private var isWifiValid = false
    private var hasAutoLaunchedAdjacent = false

    private val wifiWatcher by lazy { ProvisioningWifiWatcher(this) { onWifiStateChanged(it) } }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) AdbPairingNotificationService.start(this)
    }

    private val provisioningReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AdbPairingNotificationService.ACTION_PORTS_DISCOVERED -> {
                    discoveredPairingPort = intent.getIntExtra(AdbPairingNotificationService.EXTRA_PAIRING_PORT, 0)
                    val newConnectPort = intent.getIntExtra(AdbPairingNotificationService.EXTRA_CONNECT_PORT, 0)
                    if (newConnectPort > 0 || (!recoveryCoordinator.hasPendingFreezeRecovery && !outcomeDialogs.hasAnyPendingOutcome())) {
                        discoveredConnectPort = newConnectPort
                    }
                    if (!isFinishing && !isDestroyed) updateRadarUi()
                }
                PairingCodeReceiver.ACTION_PROVISIONING_RESULT -> handleProvisioningBroadcast(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provisioning_wizard)

        viewBinder.bindViews(this) { validateInputs() }
        viewBinder.bindListeners(
            WizardClickCallbacks(
                onConnectWifi = { dangerDialogHelper.openWifiConnectivitySettings(this) },
                onOpenDevOptions = { dangerDialogHelper.handleDevOptionsAction(this, isWifiValid) },
                onLaunchAdjacentSettings = {
                    dangerDialogHelper.ensureDangerAcknowledgedBeforeAction(this) {
                        dangerDialogHelper.launchAdjacentDevSettings(this)
                    }
                },
                onPairAndActivate = { onPairAndActivateClicked() }
            )
        )
        viewBinder.setupOemGuidance(this)
        viewBinder.setupLegacyPureSite()

        if (isInMultiWindowMode) applyMultiWindowState(true)

        if (savedInstanceState != null) {
            restoreSavedWizardState(savedInstanceState)
        } else {
            consumeWizardIntentExtras(intent)
        }
    }

    private fun restoreSavedWizardState(savedInstanceState: Bundle) {
        val restoredConnPort = savedInstanceState.getInt(KEY_DISCOVERED_CONNECT_PORT, 0)
        if (restoredConnPort > 0) discoveredConnectPort = restoredConnPort
        recoveryCoordinator.restoreState(savedInstanceState)
        outcomeDialogs.restoreState(
            savedInstanceState = savedInstanceState,
            activity = this,
            dangerDialogHelper = dangerDialogHelper,
            onDeleteSecondaryUsers = ::executeRemoveSecondaryUsersAndPromote,
            onRetryAlreadyPaired = ::executeRetryAlreadyPaired
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outcomeDialogs.saveState(outState)
        if (discoveredConnectPort > 0) outState.putInt(KEY_DISCOVERED_CONNECT_PORT, discoveredConnectPort)
        recoveryCoordinator.saveState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeWizardIntentExtras(intent)
    }

    private fun consumeWizardIntentExtras(targetIntent: Intent?) {
        outcomeDialogs.consumeIntentExtras(
            targetIntent = targetIntent,
            activity = this,
            dangerDialogHelper = dangerDialogHelper,
            onConnectPortDiscovered = { discoveredConnectPort = it },
            onDeleteSecondaryUsers = ::executeRemoveSecondaryUsersAndPromote,
            onRetryAlreadyPaired = ::executeRetryAlreadyPaired,
            onRecoveryRequested = {
                recoveryCoordinator.markRequestedFromIntent()
                checkAndShowRecoveryDialogIfNeeded()
            }
        )
    }

    private fun onPairAndActivateClicked() {
        if (isPromoting || recoveryCoordinator.isHealing) return
        val code = viewBinder.currentPairingCode()
        if (code.length != 6 || discoveredPairingPort == 0 || discoveredConnectPort == 0) {
            Toast.makeText(this, R.string.provisioning_input_label, Toast.LENGTH_SHORT).show()
            return
        }
        dangerDialogHelper.ensureDangerAcknowledgedBeforeAction(this) { executePairingAndPromotion(code) }
    }

    private fun handleProvisioningBroadcast(intent: Intent) {
        if (isFinishing || isDestroyed) return
        if (intent.getBooleanExtra(PairingCodeReceiver.EXTRA_SUCCESS, false)) {
            outcomeDialogs.clearAll()
            onProvisioningSuccessful()
            return
        }
        intent.getIntExtra(AdbPairingNotificationService.EXTRA_CONNECT_PORT, 0).takeIf { it > 0 }?.let { discoveredConnectPort = it }
        val blockingUsers = BlockingSecondaryUser.listFromJson(intent.getStringExtra(PairingCodeReceiver.EXTRA_BLOCKING_USERS_JSON))
        val oemType = intent.getStringExtra(PairingCodeReceiver.EXTRA_OEM_PERMISSION_TYPE)
            ?.let { runCatching { OemPermissionType.valueOf(it) }.getOrNull() }
        val accountsPayload = BlockingAccountsPayload.fromJson(intent.getStringExtra(PairingCodeReceiver.EXTRA_BLOCKING_ACCOUNTS_JSON))
        if (blockingUsers.isEmpty() && oemType == null && accountsPayload == null && isPromoting && DeviceOwnerPromoter.isPromotionActive) {
            Timber.tag(TAG).i("Ignoring concurrent failure broadcast while local promotion is active")
            return
        }
        isPromoting = false
        viewBinder.setPromotionButtonsBusy(null)
        validateInputs()
        if (blockingUsers.isNotEmpty()) { showBlockingSecondaryUsersDialog(blockingUsers); return }
        if (oemType != null) { showOemPermissionDialog(oemType); return }
        if (accountsPayload != null) { showAccountsBlockingDialog(accountsPayload); return }
        val error = intent.getStringExtra(PairingCodeReceiver.EXTRA_ERROR_MESSAGE)
        outcomeDialogs.clearAll()
        recoveryCoordinator.recoveryDialogDismissedByUser = false
        Toast.makeText(this, getString(R.string.toast_provisioning_failed, error ?: ""), Toast.LENGTH_LONG).show()
        checkAndShowRecoveryDialogIfNeeded()
    }

    private fun executePairingAndPromotion(code: String) {
        val pairPort = discoveredPairingPort
        val connPort = AdbPairingNotificationService.capturedConnectPort.takeIf { it > 0 } ?: discoveredConnectPort
        val appContext = applicationContext
        launchPromotionTask(R.string.provisioning_in_progress_title) {
            DeviceOwnerPromoter.pairAndPromote(appContext, pairPort, connPort, code)
        }
    }

    private fun executeRemoveSecondaryUsersAndPromote(approvedUserIds: Set<Int>) {
        AdbPairingNotificationService.clearStickyOutcome(this, suppressReprompt = true)
        val effectiveConnectPort = AdbPairingNotificationService.capturedConnectPort.takeIf { it > 0 } ?: discoveredConnectPort
        val appContext = applicationContext
        launchPromotionTask(R.string.provisioning_cleaning_and_activating) {
            DeviceOwnerPromoter.removeSecondaryUsersAndPromote(appContext, effectiveConnectPort, approvedUserIds, true)
        }
    }

    private fun executeRetryAlreadyPaired() {
        if (isPromoting || recoveryCoordinator.isHealing || DeviceOwnerPromoter.isPromotionActive) {
            Toast.makeText(this, R.string.provisioning_error_already_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        outcomeDialogs.clearAll()
        AdbPairingNotificationService.clearStickyOutcome(this, suppressReprompt = true)
        val connPort = AdbPairingNotificationService.capturedConnectPort.takeIf { it > 0 } ?: discoveredConnectPort
        val appContext = applicationContext
        launchPromotionTask(R.string.provisioning_in_progress_title) {
            DeviceOwnerPromoter.promoteIfAlreadyPaired(appContext, connPort)
        }
    }

    private fun launchPromotionTask(@StringRes busyTextRes: Int, action: suspend () -> PromotionResult) {
        if (isPromoting || recoveryCoordinator.isHealing || DeviceOwnerPromoter.isPromotionActive) return
        isPromoting = true
        viewBinder.setPromotionButtonsBusy(busyTextRes)
        val appContext = applicationContext
        lifecycleScope.launch {
            try {
                val result = withContext(NonCancellable + Dispatchers.IO) { action() }
                isPromoting = false
                if (!isFinishing && !isDestroyed) handlePromotionResult(result)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Timber.tag(TAG).e(t, "Unexpected error during provisioning task")
                isPromoting = false
                if (!isFinishing && !isDestroyed) {
                    showFailureToastAndResetButtons(appContext.getString(R.string.provisioning_error_unknown))
                }
            } finally {
                isPromoting = false
                if (!isFinishing && !isDestroyed) validateInputs()
            }
        }
    }

    private fun handlePromotionResult(result: PromotionResult) {
        if (isFinishing || isDestroyed) return
        when (result) {
            is PromotionResult.Success -> onProvisioningSuccessful()
            is PromotionResult.SecondaryUsersBlocking -> {
                viewBinder.setPromotionButtonsBusy(null)
                showBlockingSecondaryUsersDialog(result.users)
            }
            is PromotionResult.OemPermissionRequired -> {
                viewBinder.setPromotionButtonsBusy(null)
                showOemPermissionDialog(result.oemType)
            }
            is PromotionResult.AccountsBlocking -> {
                viewBinder.setPromotionButtonsBusy(null)
                showAccountsBlockingDialog(
                    BlockingAccountsPayload(result.accountLabels, result.accountTypes, result.message)
                )
            }
            is PromotionResult.Failure -> {
                if (DeviceOwnerPromoter.isPromotionActive &&
                    result.message == getString(R.string.provisioning_error_already_in_progress)
                ) {
                    Timber.tag(TAG).i("Ignoring concurrent lock rejection while service promotion is active")
                    return
                }
                showFailureToastAndResetButtons(result.message)
            }
        }
    }

    private fun showFailureToastAndResetButtons(message: String) {
        outcomeDialogs.clearAll()
        recoveryCoordinator.recoveryDialogDismissedByUser = false
        Toast.makeText(this, getString(R.string.toast_provisioning_failed, message), Toast.LENGTH_LONG).show()
        viewBinder.setPromotionButtonsBusy(null)
        checkAndShowRecoveryDialogIfNeeded()
    }

    private fun showOemPermissionDialog(oemType: OemPermissionType) =
        outcomeDialogs.showOemPermission(this, oemType, ::executeRetryAlreadyPaired)

    private fun showAccountsBlockingDialog(payload: BlockingAccountsPayload) =
        outcomeDialogs.showAccountsBlocking(this, payload, ::executeRetryAlreadyPaired)

    private fun showBlockingSecondaryUsersDialog(users: List<BlockingSecondaryUser>) =
        outcomeDialogs.showBlockingUsers(this, users, dangerDialogHelper, ::executeRemoveSecondaryUsersAndPromote)

    private fun checkAndShowRecoveryDialogIfNeeded() {
        recoveryCoordinator.checkAndShowIfNeeded(
            activity = this,
            canShowDialog = ::canShowRecoveryDialog,
            discoveredConnectPort = { discoveredConnectPort },
            onBusyChanged = viewBinder::setPromotionButtonsBusy,
            onValidationNeeded = ::validateInputs,
            onDeviceOwnerConfirmed = ::onProvisioningSuccessful
        )
    }

    private fun canShowRecoveryDialog(): Boolean =
        !isPromoting &&
            !recoveryCoordinator.isHealing &&
            !DeviceOwnerPromoter.isPromotionActive &&
            !outcomeDialogs.isAnyShowing() &&
            !dangerDialogHelper.isAnyDialogShowing() &&
            !recoveryCoordinator.recoveryDialogDismissedByUser

    private fun onWifiStateChanged(isValid: Boolean) {
        if (isFinishing || isDestroyed) return
        isWifiValid = isValid
        viewBinder.updateWifiWarning(isValid)
        validateInputs()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(AdbPairingNotificationService.ACTION_PORTS_DISCOVERED)
            addAction(PairingCodeReceiver.ACTION_PROVISIONING_RESULT)
        }
        ContextCompat.registerReceiver(this, provisioningReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val needsRecoveryAdb = recoveryCoordinator.hasPendingFreezeRecovery || ProvisioningFreezeRecoveryStore.getPendingLedger(this) != null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (!ProtectionEngine.isDeviceOwner(this) || needsRecoveryAdb)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            AdbPairingNotificationService.start(this)
        }
        wifiWatcher.startWatching()
    }

    override fun onResume() {
        super.onResume()
        if (ProtectionEngine.isDeviceOwner(this) && !DeviceOwnerPromoter.isPromotionActive && !isPromoting && !recoveryCoordinator.isHealing) {
            recoveryCoordinator.reconcileOnDeviceOwnerResume(
                activity = this,
                canShowDialog = ::canShowRecoveryDialog,
                discoveredConnectPort = { discoveredConnectPort },
                onConnectPortDiscovered = { discoveredConnectPort = it },
                onBusyChanged = viewBinder::setPromotionButtonsBusy,
                onValidationNeeded = ::validateInputs,
                onDeviceOwnerConfirmed = ::onProvisioningSuccessful
            )
            return
        }
        discoveredPairingPort = AdbPairingNotificationService.capturedPairingPort
        val svcConnectPort = AdbPairingNotificationService.capturedConnectPort
        if (svcConnectPort > 0) discoveredConnectPort = svcConnectPort
        updateRadarUi()
        viewBinder.setupOemGuidance(this)
        viewBinder.updateDevOptionsStatus(this, dangerDialogHelper.isDevOptionsEnabled(this), discoveredPairingPort > 0)
        if (isInMultiWindowMode) applyMultiWindowState(true)
        validateInputs()
        outcomeDialogs.reconcilePendingStickyOutcome(
            activity = this,
            isLocalPromoting = isPromoting,
            dangerDialogHelper = dangerDialogHelper,
            onConnectPortDiscovered = { discoveredConnectPort = it },
            onDeleteSecondaryUsers = ::executeRemoveSecondaryUsersAndPromote,
            onRetryAlreadyPaired = ::executeRetryAlreadyPaired
        )
        checkAndShowRecoveryDialogIfNeeded()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyMultiWindowState(isInMultiWindowMode)
    }

    private fun applyMultiWindowState(isInMultiWindow: Boolean) {
        viewBinder.updateMultiWindowUi(isInMultiWindow)
        updateRadarUi()
        if (isInMultiWindow && !hasAutoLaunchedAdjacent) {
            hasAutoLaunchedAdjacent = true
            dangerDialogHelper.launchAdjacentDevSettings(this)
        } else if (!isInMultiWindow) {
            hasAutoLaunchedAdjacent = false
        }
    }

    override fun onStop() {
        super.onStop()
        wifiWatcher.stopWatching()
        try { unregisterReceiver(provisioningReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        outcomeDialogs.dismissAll()
        recoveryCoordinator.dismiss()
        dangerDialogHelper.dismiss()
        if (!isChangingConfigurations &&
            !ProtectionEngine.isDeviceOwner(this) &&
            !isPromoting &&
            !recoveryCoordinator.isHealing &&
            !DeviceOwnerPromoter.isPromotionActive
        ) {
            AdbPairingNotificationService.stop(this)
        }
        super.onDestroy()
    }

    private fun updateRadarUi() {
        viewBinder.updateRadarUi(this, discoveredPairingPort)
        validateInputs()
    }

    private fun validateInputs() {
        viewBinder.validateInputs(isPromoting || recoveryCoordinator.isHealing, discoveredPairingPort > 0 && discoveredConnectPort > 0, isWifiValid)
    }

    private fun onProvisioningSuccessful() {
        if (isFinishing || isDestroyed) return
        outcomeDialogs.clearAll()
        AdbPairingNotificationService.pendingStickyOutcome = null
        ProvisioningAckStore.clearAck(this)
        AdbShellValidation.cancelFreezeRecoveryNotification(this)
        AdbPairingNotificationService.stop(this)
        Toast.makeText(this, R.string.toast_provisioning_success, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    companion object {
        private const val TAG = "AntiPorn_WizardAct"
        private const val KEY_DISCOVERED_CONNECT_PORT = "key_discovered_connect_port"
        const val EXTRA_REQUIRE_DANGER_ACK = "extra_require_danger_ack"
        const val EXTRA_BLOCKING_USERS_JSON = "extra_blocking_users_json"
        const val EXTRA_OEM_PERMISSION_TYPE = "extra_oem_permission_type"
        const val EXTRA_BLOCKING_ACCOUNTS_JSON = "extra_blocking_accounts_json"
        const val EXTRA_SHOW_RECOVERY_DIALOG = "extra_show_recovery_dialog"
        const val EXTRA_CONNECT_PORT = "extra_connect_port"
    }
}
