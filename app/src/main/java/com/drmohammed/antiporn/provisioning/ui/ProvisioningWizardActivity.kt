package com.drmohammed.antiporn.provisioning.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.drmohammed.antiporn.provisioning.engine.HealingOutcome
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
    private val blockingUsersDialogHelper = BlockingSecondaryUsersDialogHelper()
    private val oemPermissionDialogHelper = ProvisioningOemPermissionDialogHelper()
    private val recoveryDialogHelper = ProvisioningRecoveryDialogHelper()
    private val dangerDialogHelper = ProvisioningDangerDialogHelper {
        if (!isFinishing && !isDestroyed) viewBinder.setupOemGuidance(this)
    }

    private var discoveredPairingPort = 0
    private var discoveredConnectPort = 0
    private var isPromoting = false
    private var isHealing = false
    private var isWifiValid = false
    private var pendingBlockingUsersJson: String? = null
    private var pendingOemPermissionType: OemPermissionType? = null
    private var pendingRecoveryHealingHint: String? = null
    private var hasPendingFreezeRecovery = false
    private var recoveryDialogDismissedByUser = false
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
                    if (newConnectPort > 0 || (!hasPendingFreezeRecovery && pendingOemPermissionType == null && pendingBlockingUsersJson == null)) {
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
        hasPendingFreezeRecovery = savedInstanceState.getBoolean(KEY_HAS_PENDING_RECOVERY, false)
        recoveryDialogDismissedByUser = savedInstanceState.getBoolean(KEY_RECOVERY_DISMISSED, false)
        pendingRecoveryHealingHint = savedInstanceState.getString(KEY_RECOVERY_HEALING_HINT)

        val restoredJson = savedInstanceState.getString(KEY_PENDING_BLOCKING_USERS_JSON)
        if (!restoredJson.isNullOrBlank()) {
            val restoredUsers = BlockingSecondaryUser.listFromJson(restoredJson)
            if (restoredUsers.isNotEmpty()) showBlockingSecondaryUsersDialog(restoredUsers)
        }
        savedInstanceState.getString(KEY_PENDING_OEM_TYPE)?.let { raw ->
            runCatching { OemPermissionType.valueOf(raw) }.getOrNull()?.let { showOemPermissionDialog(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingBlockingUsersJson?.let { outState.putString(KEY_PENDING_BLOCKING_USERS_JSON, it) }
        (oemPermissionDialogHelper.currentOemType() ?: pendingOemPermissionType)?.let {
            outState.putString(KEY_PENDING_OEM_TYPE, it.name)
        }
        if (discoveredConnectPort > 0) outState.putInt(KEY_DISCOVERED_CONNECT_PORT, discoveredConnectPort)
        outState.putBoolean(KEY_HAS_PENDING_RECOVERY, hasPendingFreezeRecovery)
        outState.putBoolean(KEY_RECOVERY_DISMISSED, recoveryDialogDismissedByUser)
        (recoveryDialogHelper.currentHealingHint() ?: pendingRecoveryHealingHint)?.let {
            outState.putString(KEY_RECOVERY_HEALING_HINT, it)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeWizardIntentExtras(intent)
    }

    private fun consumeWizardIntentExtras(targetIntent: Intent?) {
        if (targetIntent == null) return
        if (targetIntent.getBooleanExtra(EXTRA_REQUIRE_DANGER_ACK, false)) {
            targetIntent.removeExtra(EXTRA_REQUIRE_DANGER_ACK)
            dangerDialogHelper.ensureDangerAcknowledgedBeforeAction(this) {}
        }
        val extraConnPort = targetIntent.getIntExtra(EXTRA_CONNECT_PORT, 0)
        if (extraConnPort > 0) {
            discoveredConnectPort = extraConnPort
            targetIntent.removeExtra(EXTRA_CONNECT_PORT)
        }
        val usersJson = targetIntent.getStringExtra(EXTRA_BLOCKING_USERS_JSON)
        if (!usersJson.isNullOrBlank()) {
            targetIntent.removeExtra(EXTRA_BLOCKING_USERS_JSON)
            val users = BlockingSecondaryUser.listFromJson(usersJson)
            if (users.isNotEmpty()) showBlockingSecondaryUsersDialog(users)
        }
        targetIntent.getStringExtra(EXTRA_OEM_PERMISSION_TYPE)?.let { rawType ->
            targetIntent.removeExtra(EXTRA_OEM_PERMISSION_TYPE)
            runCatching { OemPermissionType.valueOf(rawType) }.getOrNull()?.let { showOemPermissionDialog(it) }
        }
        if (targetIntent.getBooleanExtra(EXTRA_SHOW_RECOVERY_DIALOG, false)) {
            targetIntent.removeExtra(EXTRA_SHOW_RECOVERY_DIALOG)
            hasPendingFreezeRecovery = true
            recoveryDialogDismissedByUser = false
            checkAndShowRecoveryDialogIfNeeded()
        }
    }

    private fun onPairAndActivateClicked() {
        if (isPromoting || isHealing) return
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
            pendingBlockingUsersJson = null
            pendingOemPermissionType = null
            onProvisioningSuccessful()
            return
        }
        intent.getIntExtra(AdbPairingNotificationService.EXTRA_CONNECT_PORT, 0).takeIf { it > 0 }?.let { discoveredConnectPort = it }
        val blockingUsers = BlockingSecondaryUser.listFromJson(intent.getStringExtra(PairingCodeReceiver.EXTRA_BLOCKING_USERS_JSON))
        val oemType = intent.getStringExtra(PairingCodeReceiver.EXTRA_OEM_PERMISSION_TYPE)
            ?.let { runCatching { OemPermissionType.valueOf(it) }.getOrNull() }
        if (blockingUsers.isEmpty() && oemType == null && isPromoting && DeviceOwnerPromoter.isPromotionActive) {
            Timber.tag(TAG).i("Ignoring concurrent failure broadcast while local promotion is active")
            return
        }
        isPromoting = false
        viewBinder.setPromotionButtonsBusy(null)
        validateInputs()
        if (blockingUsers.isNotEmpty()) { showBlockingSecondaryUsersDialog(blockingUsers); return }
        if (oemType != null) { showOemPermissionDialog(oemType); return }
        val error = intent.getStringExtra(PairingCodeReceiver.EXTRA_ERROR_MESSAGE)
        recoveryDialogDismissedByUser = false
        Toast.makeText(this, getString(R.string.toast_provisioning_failed, error ?: ""), Toast.LENGTH_LONG).show()
        checkAndShowRecoveryDialogIfNeeded()
    }

    private fun executePairingAndPromotion(code: String) {
        val pairPort = discoveredPairingPort
        val connPort = AdbPairingNotificationService.capturedConnectPort.takeIf { it > 0 } ?: discoveredConnectPort
        launchPromotionTask(R.string.provisioning_in_progress_title) {
            DeviceOwnerPromoter.pairAndPromote(this@ProvisioningWizardActivity, pairPort, connPort, code)
        }
    }

    private fun executeRemoveSecondaryUsersAndPromote(approvedUserIds: Set<Int>) {
        AdbPairingNotificationService.clearStickyOutcome(this, suppressReprompt = true)
        val effectiveConnectPort = AdbPairingNotificationService.capturedConnectPort.takeIf { it > 0 } ?: discoveredConnectPort
        launchPromotionTask(R.string.provisioning_cleaning_and_activating) {
            DeviceOwnerPromoter.removeSecondaryUsersAndPromote(this@ProvisioningWizardActivity, effectiveConnectPort, approvedUserIds, true)
        }
    }

    private fun executeRetryAlreadyPaired() {
        if (isPromoting || isHealing || DeviceOwnerPromoter.isPromotionActive) {
            Toast.makeText(this, R.string.provisioning_error_already_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        pendingOemPermissionType = null
        AdbPairingNotificationService.clearStickyOutcome(this, suppressReprompt = true)
        val connPort = AdbPairingNotificationService.capturedConnectPort.takeIf { it > 0 } ?: discoveredConnectPort
        launchPromotionTask(R.string.provisioning_in_progress_title) {
            DeviceOwnerPromoter.promoteIfAlreadyPaired(this@ProvisioningWizardActivity, connPort)
        }
    }

    private fun launchPromotionTask(@StringRes busyTextRes: Int, action: suspend () -> PromotionResult) {
        if (isPromoting || isHealing || DeviceOwnerPromoter.isPromotionActive) return
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
            is PromotionResult.Failure -> showFailureToastAndResetButtons(result.message)
        }
    }

    private fun showFailureToastAndResetButtons(message: String) {
        recoveryDialogDismissedByUser = false
        Toast.makeText(this, getString(R.string.toast_provisioning_failed, message), Toast.LENGTH_LONG).show()
        viewBinder.setPromotionButtonsBusy(null)
        checkAndShowRecoveryDialogIfNeeded()
    }

    private fun showOemPermissionDialog(oemType: OemPermissionType) {
        if (isFinishing || isDestroyed) return
        pendingOemPermissionType = oemType
        oemPermissionDialogHelper.show(
            activity = this,
            oemType = oemType,
            onRetryNow = { executeRetryAlreadyPaired() },
            onDismissed = { pendingOemPermissionType = null }
        )
    }

    private fun checkAndShowRecoveryDialogIfNeeded(healingHint: String? = pendingRecoveryHealingHint) {
        if (isFinishing || isDestroyed || !canShowRecoveryDialog()) return
        val appContext = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val stranded = ProvisioningFreezeRecoveryStore.findStillDisabledTrackedPackages(appContext)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (stranded.isEmpty()) {
                    hasPendingFreezeRecovery = false
                    pendingRecoveryHealingHint = null
                    AdbShellValidation.cancelFreezeRecoveryNotification(appContext)
                    if (ProtectionEngine.isDeviceOwner(this@ProvisioningWizardActivity)) {
                        onProvisioningSuccessful()
                    }
                    return@withContext
                }
                hasPendingFreezeRecovery = true
                if (canShowRecoveryDialog()) {
                    pendingRecoveryHealingHint = healingHint
                    recoveryDialogHelper.show(
                        activity = this@ProvisioningWizardActivity,
                        packages = stranded,
                        healingHint = healingHint,
                        onQuickAdbHeal = { executeQuickAdbHeal() },
                        onDismissedByUser = { recoveryDialogDismissedByUser = true }
                    )
                }
            }
        }
    }

    private fun canShowRecoveryDialog(): Boolean =
        !isPromoting &&
            !isHealing &&
            !DeviceOwnerPromoter.isPromotionActive &&
            !blockingUsersDialogHelper.isShowing() &&
            !oemPermissionDialogHelper.isShowing() &&
            !dangerDialogHelper.isAnyDialogShowing() &&
            !recoveryDialogDismissedByUser

    private fun executeQuickAdbHeal() {
        if (isPromoting || isHealing || DeviceOwnerPromoter.isPromotionActive) {
            Toast.makeText(this, R.string.provisioning_error_already_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        val connPort = AdbPairingNotificationService.capturedConnectPort.takeIf { it > 0 } ?: discoveredConnectPort
        val isWifiAdbEnabled = runCatching {
            Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false)
        if (connPort <= 0 && !isWifiAdbEnabled) {
            val hint = getString(R.string.provisioning_recovery_adb_unavailable_hint)
            pendingRecoveryHealingHint = hint
            recoveryDialogDismissedByUser = false
            Toast.makeText(this, R.string.provisioning_error_heal_failed, Toast.LENGTH_LONG).show()
            checkAndShowRecoveryDialogIfNeeded(hint)
            return
        }

        isHealing = true
        viewBinder.setPromotionButtonsBusy(R.string.provisioning_in_progress_title)
        val appContext = applicationContext
        lifecycleScope.launch {
            try {
                val outcome = withContext(NonCancellable + Dispatchers.IO) {
                    DeviceOwnerPromoter.healStrandedPackagesViaAdb(appContext, connPort)
                }
                isHealing = false
                if (!isFinishing && !isDestroyed) {
                    viewBinder.setPromotionButtonsBusy(null)
                    handleHealingOutcome(outcome)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Timber.tag(TAG).e(t, "Unexpected error during quick ADB heal")
                isHealing = false
                if (!isFinishing && !isDestroyed) {
                    viewBinder.setPromotionButtonsBusy(null)
                    val hint = getString(R.string.provisioning_recovery_adb_unavailable_hint)
                    pendingRecoveryHealingHint = hint
                    recoveryDialogDismissedByUser = false
                    checkAndShowRecoveryDialogIfNeeded(hint)
                }
            } finally {
                isHealing = false
                if (!isFinishing && !isDestroyed) validateInputs()
            }
        }
    }

    private fun handleHealingOutcome(outcome: HealingOutcome) {
        when (outcome) {
            is HealingOutcome.Healed -> {
                hasPendingFreezeRecovery = false
                pendingRecoveryHealingHint = null
                AdbShellValidation.cancelFreezeRecoveryNotification(this)
                Toast.makeText(
                    this,
                    getString(R.string.provisioning_recovery_toast_healed, outcome.healedCount),
                    Toast.LENGTH_LONG
                ).show()
                if (ProtectionEngine.isDeviceOwner(this)) onProvisioningSuccessful()
            }
            is HealingOutcome.NothingToHeal -> {
                hasPendingFreezeRecovery = false
                pendingRecoveryHealingHint = null
                AdbShellValidation.cancelFreezeRecoveryNotification(this)
                Toast.makeText(this, R.string.provisioning_recovery_toast_already_restored, Toast.LENGTH_SHORT).show()
                if (ProtectionEngine.isDeviceOwner(this)) onProvisioningSuccessful()
            }
            is HealingOutcome.NotPaired -> {
                Toast.makeText(this, R.string.provisioning_recovery_toast_not_paired, Toast.LENGTH_LONG).show()
                val hint = getString(R.string.provisioning_recovery_adb_unavailable_hint)
                pendingRecoveryHealingHint = hint
                recoveryDialogDismissedByUser = false
                checkAndShowRecoveryDialogIfNeeded(hint)
            }
            is HealingOutcome.Failed -> {
                Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()
                val hint = getString(R.string.provisioning_recovery_adb_unavailable_hint)
                pendingRecoveryHealingHint = hint
                recoveryDialogDismissedByUser = false
                checkAndShowRecoveryDialogIfNeeded(hint)
            }
        }
    }

    private fun showBlockingSecondaryUsersDialog(users: List<BlockingSecondaryUser>) {
        if (isFinishing || isDestroyed || users.isEmpty()) return
        pendingBlockingUsersJson = BlockingSecondaryUser.listToJson(users)
        blockingUsersDialogHelper.show(
            activity = this,
            users = users,
            onOpenSettings = { dangerDialogHelper.openBlockingUsersSettings(this, users) },
            onConfirmedDeleteAndActivate = { approvedUserIds ->
                pendingBlockingUsersJson = null
                ProvisioningAckStore.recordAck(this)
                AdbPairingNotificationService.clearStickyOutcome(this, suppressReprompt = true)
                executeRemoveSecondaryUsersAndPromote(approvedUserIds)
            },
            onCancel = {
                pendingBlockingUsersJson = null
                AdbPairingNotificationService.clearStickyOutcome(this)
            }
        )
    }

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
        val needsRecoveryAdb = hasPendingFreezeRecovery || ProvisioningFreezeRecoveryStore.getPendingLedger(this) != null
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
        if (ProtectionEngine.isDeviceOwner(this)) {
            val appContext = applicationContext
            lifecycleScope.launch(Dispatchers.IO) {
                val stranded = ProvisioningFreezeRecoveryStore.findStillDisabledTrackedPackages(appContext)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (stranded.isEmpty()) {
                        onProvisioningSuccessful()
                    } else {
                        hasPendingFreezeRecovery = true
                        recoveryDialogDismissedByUser = false
                        val svcConnectPort = AdbPairingNotificationService.capturedConnectPort
                        if (svcConnectPort > 0) discoveredConnectPort = svcConnectPort
                        checkAndShowRecoveryDialogIfNeeded()
                    }
                }
            }
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
        blockingUsersDialogHelper.dismiss()
        oemPermissionDialogHelper.dismiss()
        recoveryDialogHelper.dismiss()
        dangerDialogHelper.dismiss()
        if (!isChangingConfigurations &&
            !ProtectionEngine.isDeviceOwner(this) &&
            !isPromoting &&
            !isHealing &&
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
        viewBinder.validateInputs(isPromoting || isHealing, discoveredPairingPort > 0 && discoveredConnectPort > 0, isWifiValid)
    }

    private fun onProvisioningSuccessful() {
        if (isFinishing || isDestroyed) return
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
        private const val KEY_PENDING_BLOCKING_USERS_JSON = "key_pending_blocking_users_json"
        private const val KEY_PENDING_OEM_TYPE = "key_pending_oem_type"
        private const val KEY_DISCOVERED_CONNECT_PORT = "key_discovered_connect_port"
        private const val KEY_RECOVERY_HEALING_HINT = "key_recovery_healing_hint"
        private const val KEY_RECOVERY_DISMISSED = "key_recovery_dismissed"
        private const val KEY_HAS_PENDING_RECOVERY = "key_has_pending_recovery"
        const val EXTRA_REQUIRE_DANGER_ACK = "extra_require_danger_ack"
        const val EXTRA_BLOCKING_USERS_JSON = "extra_blocking_users_json"
        const val EXTRA_OEM_PERMISSION_TYPE = "extra_oem_permission_type"
        const val EXTRA_SHOW_RECOVERY_DIALOG = "extra_show_recovery_dialog"
        const val EXTRA_CONNECT_PORT = "extra_connect_port"
    }
}
