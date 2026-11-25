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
import com.drmohammed.antiporn.provisioning.engine.BlockingSecondaryUser
import com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter
import com.drmohammed.antiporn.provisioning.engine.PromotionResult
import com.drmohammed.antiporn.provisioning.engine.ProvisioningAckStore
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
    private val dangerDialogHelper = ProvisioningDangerDialogHelper {
        if (!isFinishing && !isDestroyed) viewBinder.setupOemGuidance(this)
    }

    private var discoveredPairingPort = 0
    private var discoveredConnectPort = 0
    private var isPromoting = false
    private var isWifiValid = false
    private var pendingBlockingUsersJson: String? = null
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
                    discoveredConnectPort = intent.getIntExtra(AdbPairingNotificationService.EXTRA_CONNECT_PORT, 0)
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

        val restoredJson = savedInstanceState?.getString(KEY_PENDING_BLOCKING_USERS_JSON)
        if (!restoredJson.isNullOrBlank()) {
            val restoredUsers = BlockingSecondaryUser.listFromJson(restoredJson)
            if (restoredUsers.isNotEmpty()) showBlockingSecondaryUsersDialog(restoredUsers)
        } else {
            consumeWizardIntentExtras(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingBlockingUsersJson?.let { outState.putString(KEY_PENDING_BLOCKING_USERS_JSON, it) }
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
    }

    private fun onPairAndActivateClicked() {
        if (isPromoting) return
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
            onProvisioningSuccessful()
            return
        }
        intent.getIntExtra(AdbPairingNotificationService.EXTRA_CONNECT_PORT, 0).takeIf { it > 0 }?.let { discoveredConnectPort = it }
        val blockingUsers = BlockingSecondaryUser.listFromJson(intent.getStringExtra(PairingCodeReceiver.EXTRA_BLOCKING_USERS_JSON))
        if (blockingUsers.isEmpty() && isPromoting && DeviceOwnerPromoter.isPromotionActive) {
            Timber.tag(TAG).i("Ignoring concurrent failure broadcast while local promotion is active")
            return
        }
        isPromoting = false
        viewBinder.setPromotionButtonsBusy(null)
        validateInputs()
        if (blockingUsers.isNotEmpty()) {
            showBlockingSecondaryUsersDialog(blockingUsers)
            return
        }
        val error = intent.getStringExtra(PairingCodeReceiver.EXTRA_ERROR_MESSAGE)
        Toast.makeText(this, getString(R.string.toast_provisioning_failed, error ?: ""), Toast.LENGTH_LONG).show()
    }

    private fun executePairingAndPromotion(code: String) {
        val pairPort = discoveredPairingPort
        val connPort = AdbPairingNotificationService.capturedConnectPort.takeIf { it > 0 } ?: discoveredConnectPort
        launchPromotionTask(R.string.provisioning_in_progress_title) {
            DeviceOwnerPromoter.pairAndPromote(this@ProvisioningWizardActivity, pairPort, connPort, code)
        }
    }

    private fun executeRemoveSecondaryUsersAndPromote(approvedUserIds: Set<Int>) {
        AdbPairingNotificationService.clearStickyOutcome(this)
        val effectiveConnectPort = AdbPairingNotificationService.capturedConnectPort.takeIf { it > 0 } ?: discoveredConnectPort
        launchPromotionTask(R.string.provisioning_cleaning_and_activating) {
            DeviceOwnerPromoter.removeSecondaryUsersAndPromote(this@ProvisioningWizardActivity, effectiveConnectPort, approvedUserIds, true)
        }
    }

    private fun launchPromotionTask(@StringRes busyTextRes: Int, action: suspend () -> PromotionResult) {
        if (isPromoting || DeviceOwnerPromoter.isPromotionActive) return
        isPromoting = true
        viewBinder.setPromotionButtonsBusy(busyTextRes)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = action()
                withContext(Dispatchers.Main) { handlePromotionResult(result) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Timber.tag(TAG).e(t, "Unexpected error during provisioning task")
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        showFailureToastAndResetButtons("${t.javaClass.simpleName}: ${t.message ?: getString(R.string.provisioning_error_unknown)}")
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) { isPromoting = false; validateInputs() }
                }
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
            is PromotionResult.Failure -> showFailureToastAndResetButtons(result.message)
        }
    }

    private fun showFailureToastAndResetButtons(message: String) {
        Toast.makeText(this, getString(R.string.toast_provisioning_failed, message), Toast.LENGTH_LONG).show()
        viewBinder.setPromotionButtonsBusy(null)
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
                AdbPairingNotificationService.clearStickyOutcome(this)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !ProtectionEngine.isDeviceOwner(this)) {
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
            onProvisioningSuccessful()
            return
        }
        discoveredPairingPort = AdbPairingNotificationService.capturedPairingPort
        val svcConnectPort = AdbPairingNotificationService.capturedConnectPort
        if (svcConnectPort > 0 || pendingBlockingUsersJson == null) discoveredConnectPort = svcConnectPort
        updateRadarUi()
        viewBinder.setupOemGuidance(this)
        viewBinder.updateDevOptionsStatus(this, dangerDialogHelper.isDevOptionsEnabled(this), discoveredPairingPort > 0)
        if (isInMultiWindowMode) applyMultiWindowState(true)
        validateInputs()
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
        dangerDialogHelper.dismiss()
        if (!isChangingConfigurations && !ProtectionEngine.isDeviceOwner(this) && !isPromoting && !DeviceOwnerPromoter.isPromotionActive) {
            AdbPairingNotificationService.stop(this)
        }
        super.onDestroy()
    }

    private fun updateRadarUi() {
        viewBinder.updateRadarUi(this, discoveredPairingPort)
        validateInputs()
    }

    private fun validateInputs() {
        viewBinder.validateInputs(isPromoting, discoveredPairingPort > 0 && discoveredConnectPort > 0, isWifiValid)
    }

    private fun onProvisioningSuccessful() {
        if (isFinishing || isDestroyed) return
        ProvisioningAckStore.clearAck(this)
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
        const val EXTRA_REQUIRE_DANGER_ACK = "extra_require_danger_ack"
        const val EXTRA_BLOCKING_USERS_JSON = "extra_blocking_users_json"
        const val EXTRA_CONNECT_PORT = "extra_connect_port"
    }
}
