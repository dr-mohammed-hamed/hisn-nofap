package com.drmohammed.antiporn.ui.accountability

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.fortress.model.FortressesConfig
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.GuardianTeardownAlreadyCompletedException
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.data.repository.cancelGuardianImmediateTeardown
import com.drmohammed.antiporn.data.repository.rejectDeviceOwnerDeactivation
import com.drmohammed.antiporn.data.repository.requestGuardianImmediateTeardown
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.ui.common.showSafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * View binder responsible for guardian alert banners (Self-Control modification notice
 * and Emergency Breakout request), including revert, dismiss, and reject actions.
 */
class GuardianAlertsViewBinder(
    private val activity: AppCompatActivity,
    private val cardSelfControlAlertBanner: MaterialCardView,
    private val tvSelfControlAlertMsg: TextView,
    private val btnRevertSelfControlChanges: MaterialButton,
    private val btnDismissSelfControlAlert: MaterialButton,
    private val cardBreakoutAlertBanner: MaterialCardView,
    private val tvBreakoutAlertMsg: TextView,
    private val btnRejectBreakout: MaterialButton,
    private val wardNameProvider: () -> String,
    private val wardIdProvider: () -> String,
    private val policyProvider: () -> DevicePolicyBundle,
    private val isSelfControlAllowedProvider: () -> Boolean,
    private val onPolicyReverted: (DevicePolicyBundle) -> Unit,
    private val onPolicyAcknowledged: (DevicePolicyBundle) -> Unit,
    private val onTeardownAlreadyCompleted: () -> Unit = {}
) {
    private var lastRejectBreakoutClickTime = 0L
    private val btnApproveImmediateTeardown: MaterialButton? by lazy {
        activity.findViewById(R.id.btnApproveImmediateTeardown)
    }
    private val btnCancelPendingTeardown: MaterialButton? by lazy {
        activity.findViewById(R.id.btnCancelPendingTeardown)
    }

    fun setupListeners() {
        btnRevertSelfControlChanges.setOnClickListener {
            if (activity.isFinishing || activity.isDestroyed) return@setOnClickListener
            val currentPolicy = policyProvider()
            val snapshotJson = currentPolicy.guardianPolicySnapshot
            if (snapshotJson.isNullOrBlank()) {
                cardSelfControlAlertBanner.visibility = View.GONE
                return@setOnClickListener
            }
            val wardId = wardIdProvider()
            if (wardId.isBlank()) {
                Toast.makeText(activity.applicationContext, R.string.toast_policy_push_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnRevertSelfControlChanges.isEnabled = false
            activity.lifecycleScope.launch {
                try {
                    val snapshotPolicy = DevicePolicyBundle.fromJson(snapshotJson)
                    val accountRepo = AccountabilityRepository.getInstance(activity.applicationContext)
                    val myUid = accountRepo.getCachedUid()
                    val myName = accountRepo.getMyDisplayName()
                    val revertedPolicy = PolicyRepository.getInstance(activity.applicationContext)
                        .mergeAndPushRemotePolicyPatch(
                            wardUid = wardId,
                            fallbackPolicy = currentPolicy,
                            guardianUidFallback = myUid,
                            guardianNameFallback = myName
                        ) { latest ->
                            val preservedFortresses = if (latest.isSelfControlAllowed && latest.selfControlFortressesAllowed) {
                                FortressesConfig(
                                    profiles = listOf(snapshotPolicy.fortressesConfig.getEmergencyFortress()) +
                                        latest.fortressesConfig.getCustomFortresses()
                                ).normalized()
                            } else {
                                snapshotPolicy.fortressesConfig
                            }
                            snapshotPolicy.copy(
                                fortressesConfig = preservedFortresses,
                                isSelfControlAllowed = isSelfControlAllowedProvider(),
                                deactivationStatus = latest.deactivationStatus,
                                deactivationRequestedAt = latest.deactivationRequestedAt,
                                deactivationUnlockAt = latest.deactivationUnlockAt,
                                deactivationHasQr = latest.deactivationHasQr,
                                remoteTeardownRequestedAt = latest.remoteTeardownRequestedAt,
                                breakoutStatus = latest.breakoutStatus,
                                breakoutRequestedAt = latest.breakoutRequestedAt,
                                breakoutUnlockAt = latest.breakoutUnlockAt,
                                version = maxOf(latest.version, currentPolicy.version) + 1,
                                updatedAt = TrustedTimeProvider.currentTimeMillis(activity.applicationContext)
                            )
                        }
                    if (activity.isFinishing || activity.isDestroyed) return@launch
                    if (revertedPolicy != null) {
                        cardSelfControlAlertBanner.visibility = View.GONE
                        onPolicyReverted(revertedPolicy)
                        Toast.makeText(activity, R.string.toast_self_control_reverted, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, R.string.toast_policy_push_failed, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).e(e, "Error reverting self-control policy")
                    Toast.makeText(activity.applicationContext, R.string.toast_policy_push_failed, Toast.LENGTH_LONG).show()
                } finally {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        btnRevertSelfControlChanges.isEnabled = true
                    }
                }
            }
        }

        btnDismissSelfControlAlert.setOnClickListener {
            if (activity.isFinishing || activity.isDestroyed) return@setOnClickListener
            val wardId = wardIdProvider()
            if (wardId.isBlank()) {
                Toast.makeText(activity.applicationContext, R.string.toast_policy_push_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnDismissSelfControlAlert.isEnabled = false
            activity.lifecycleScope.launch {
                try {
                    val currentPolicy = policyProvider()
                    val accountRepo = AccountabilityRepository.getInstance(activity.applicationContext)
                    val myUid = accountRepo.getCachedUid()
                    val myName = accountRepo.getMyDisplayName()
                    val acknowledgedPolicy = PolicyRepository.getInstance(activity.applicationContext)
                        .mergeAndPushRemotePolicyPatch(
                            wardUid = wardId,
                            fallbackPolicy = currentPolicy,
                            guardianUidFallback = myUid,
                            guardianNameFallback = myName
                        ) { latest ->
                            latest.copy(
                                version = maxOf(latest.version, currentPolicy.version) + 1,
                                updatedAt = TrustedTimeProvider.currentTimeMillis(activity.applicationContext)
                            )
                        }
                    if (activity.isFinishing || activity.isDestroyed) return@launch
                    if (acknowledgedPolicy != null) {
                        cardSelfControlAlertBanner.visibility = View.GONE
                        onPolicyAcknowledged(acknowledgedPolicy)
                        Toast.makeText(activity, R.string.toast_self_control_dismissed, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, R.string.toast_policy_push_failed, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).e(e, "Error dismissing self-control alert")
                    Toast.makeText(activity.applicationContext, R.string.toast_policy_push_failed, Toast.LENGTH_LONG).show()
                } finally {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        btnDismissSelfControlAlert.isEnabled = true
                    }
                }
            }
        }

        btnRejectBreakout.setOnClickListener {
            if (activity.isFinishing || activity.isDestroyed) return@setOnClickListener
            val wardId = wardIdProvider()
            if (wardId.isBlank()) return@setOnClickListener
            val now = SystemClock.elapsedRealtime()
            if (now - lastRejectBreakoutClickTime < 1000L) return@setOnClickListener
            lastRejectBreakoutClickTime = now
            btnRejectBreakout.isEnabled = false
            btnApproveImmediateTeardown?.isEnabled = false
            activity.lifecycleScope.launch {
                try {
                    val currentPolicy = policyProvider()
                    val accountRepo = AccountabilityRepository.getInstance(activity.applicationContext)
                    val result = if (currentPolicy.deactivationStatus == DevicePolicyBundle.DEACTIVATION_STATUS_PENDING) {
                        accountRepo.rejectDeviceOwnerDeactivation(wardId)
                    } else {
                        accountRepo.rejectEmergencyBreakout(wardId)
                    }
                    if (activity.isFinishing || activity.isDestroyed) return@launch
                    result.fold(
                        onSuccess = {
                            Toast.makeText(activity, R.string.toast_breakout_rejected, Toast.LENGTH_SHORT).show()
                            cardBreakoutAlertBanner.visibility = View.GONE
                        },
                        onFailure = { err ->
                            Toast.makeText(activity, err.localizedMessage ?: activity.getString(R.string.error_unknown), Toast.LENGTH_LONG).show()
                        }
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).e(e, "Error rejecting breakout")
                    Toast.makeText(
                        activity.applicationContext,
                        e.localizedMessage ?: activity.getString(R.string.error_unknown),
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        btnRejectBreakout.isEnabled = true
                        btnApproveImmediateTeardown?.isEnabled = true
                    }
                }
            }
        }

        btnApproveImmediateTeardown?.setOnClickListener {
            if (activity.isFinishing || activity.isDestroyed) return@setOnClickListener
            val wardId = wardIdProvider()
            if (wardId.isBlank()) return@setOnClickListener
            val wardName = wardNameProvider()
            activity.supportFragmentManager.showSafely(RemoteTeardownDialogFragment.TAG_STAGE1) {
                RemoteTeardownDialogFragment.newInstance(
                    RemoteTeardownDialogFragment.MODE_STAGE1_WARNING,
                    wardName
                )
            }
        }

        btnCancelPendingTeardown?.setOnClickListener {
            if (activity.isFinishing || activity.isDestroyed) return@setOnClickListener
            val wardId = wardIdProvider()
            if (wardId.isBlank()) return@setOnClickListener
            val wardName = wardNameProvider()
            activity.supportFragmentManager.showSafely(RemoteTeardownDialogFragment.TAG_CANCEL) {
                RemoteTeardownDialogFragment.newInstance(
                    RemoteTeardownDialogFragment.MODE_CANCEL_PENDING,
                    wardName
                )
            }
        }
    }

    fun executeApprovedImmediateTeardown() {
        if (activity.isFinishing || activity.isDestroyed) return
        val wardId = wardIdProvider()
        if (wardId.isBlank()) return
        btnApproveImmediateTeardown?.isEnabled = false
        btnRejectBreakout.isEnabled = false
        activity.lifecycleScope.launch {
            try {
                val accountRepo = AccountabilityRepository.getInstance(activity.applicationContext)
                val result = accountRepo.requestGuardianImmediateTeardown(wardId)
                if (activity.isFinishing || activity.isDestroyed) return@launch
                result.fold(
                    onSuccess = { requestedAt ->
                        val updated = policyProvider().copy(
                            deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
                            deactivationRequestedAt = requestedAt,
                            deactivationUnlockAt = maxOf(1L, requestedAt - 60_000L),
                            deactivationHasQr = false,
                            remoteTeardownRequestedAt = requestedAt,
                            isInstantInternetKillEnabled = false
                        )
                        onPolicyAcknowledged(updated)
                        Toast.makeText(
                            activity.applicationContext,
                            R.string.toast_guardian_remote_teardown_sent,
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onFailure = { err ->
                        val msg = err.localizedMessage ?: activity.getString(R.string.error_unknown)
                        Toast.makeText(
                            activity.applicationContext,
                            activity.getString(R.string.toast_guardian_remote_teardown_failed, msg),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Error approving immediate teardown")
                val msg = e.localizedMessage ?: activity.getString(R.string.error_unknown)
                Toast.makeText(
                    activity.applicationContext,
                    activity.getString(R.string.toast_guardian_remote_teardown_failed, msg),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    btnApproveImmediateTeardown?.isEnabled = true
                    btnRejectBreakout.isEnabled = true
                }
            }
        }
    }

    fun executeCancelPendingTeardown() {
        if (activity.isFinishing || activity.isDestroyed) return
        val wardId = wardIdProvider()
        if (wardId.isBlank()) return
        btnCancelPendingTeardown?.isEnabled = false
        activity.lifecycleScope.launch {
            try {
                val accountRepo = AccountabilityRepository.getInstance(activity.applicationContext)
                val result = accountRepo.cancelGuardianImmediateTeardown(wardId)
                if (activity.isFinishing || activity.isDestroyed) return@launch
                result.fold(
                    onSuccess = {
                        val updated = policyProvider().copy(
                            deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_CANCELLED,
                            deactivationRequestedAt = null,
                            deactivationUnlockAt = null,
                            deactivationHasQr = false,
                            remoteTeardownRequestedAt = null
                        )
                        onPolicyAcknowledged(updated)
                        Toast.makeText(
                            activity.applicationContext,
                            R.string.toast_guardian_remote_teardown_cancelled,
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { err ->
                        if (err is GuardianTeardownAlreadyCompletedException) {
                            onTeardownAlreadyCompleted()
                        } else {
                            val msg = err.localizedMessage ?: activity.getString(R.string.error_unknown)
                            Toast.makeText(
                                activity.applicationContext,
                                activity.getString(R.string.toast_guardian_remote_teardown_failed, msg),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Error cancelling pending remote teardown")
                val msg = e.localizedMessage ?: activity.getString(R.string.error_unknown)
                Toast.makeText(
                    activity.applicationContext,
                    activity.getString(R.string.toast_guardian_remote_teardown_failed, msg),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    btnCancelPendingTeardown?.isEnabled = true
                }
            }
        }
    }

    fun renderPolicyAlerts(policy: DevicePolicyBundle) {
        if (activity.isFinishing || activity.isDestroyed) return
        val wardName = wardNameProvider()
        if (!policy.guardianPolicySnapshot.isNullOrBlank() || policy.lastSelfModifiedAt != null) {
            cardSelfControlAlertBanner.visibility = View.VISIBLE
            val formattedTime = if (policy.lastSelfModifiedAt != null) {
                val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                sdf.format(Date(policy.lastSelfModifiedAt))
            } else {
                ""
            }
            tvSelfControlAlertMsg.text = activity.getString(R.string.guardian_self_control_alert_msg, wardName, formattedTime)
        } else {
            cardSelfControlAlertBanner.visibility = View.GONE
        }

        val hasRemoteTeardownTimestamp = (policy.remoteTeardownRequestedAt ?: 0L) > 0L
        val isGuardianRemoteTeardownPending = policy.deactivationStatus == DevicePolicyBundle.DEACTIVATION_STATUS_PENDING &&
            hasRemoteTeardownTimestamp
        val isDeactivationPending = policy.deactivationStatus == DevicePolicyBundle.DEACTIVATION_STATUS_PENDING &&
            !hasRemoteTeardownTimestamp &&
            policy.deactivationUnlockAt != null
        val isBreakoutPending = policy.breakoutStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING && policy.breakoutUnlockAt != null

        if (isGuardianRemoteTeardownPending) {
            cardBreakoutAlertBanner.visibility = View.VISIBLE
            tvBreakoutAlertMsg.text = activity.getString(R.string.guardian_remote_teardown_pending_banner, wardName)
            btnRejectBreakout.visibility = View.GONE
            btnApproveImmediateTeardown?.visibility = View.GONE
            btnCancelPendingTeardown?.visibility = View.VISIBLE
        } else if (isDeactivationPending || isBreakoutPending) {
            cardBreakoutAlertBanner.visibility = View.VISIBLE
            btnRejectBreakout.visibility = View.VISIBLE
            btnCancelPendingTeardown?.visibility = View.GONE
            btnApproveImmediateTeardown?.visibility = if (isDeactivationPending) View.VISIBLE else View.GONE
            val nowTrusted = TrustedTimeProvider.currentTimeMillis(activity.applicationContext)
            val unlockAt = if (isDeactivationPending) (policy.deactivationUnlockAt ?: 0L) else (policy.breakoutUnlockAt ?: 0L)
            val remainingMs = (unlockAt - nowTrusted).coerceAtLeast(0L)
            val hours = remainingMs / (1000 * 3600)
            val mins = (remainingMs % (1000 * 3600)) / (1000 * 60)
            val timeStr = String.format(Locale.US, "%02d:%02d", hours, mins)

            if (isDeactivationPending) {
                tvBreakoutAlertMsg.text = activity.getString(R.string.guardian_deactivation_alert_msg, wardName, timeStr)
            } else {
                tvBreakoutAlertMsg.text = activity.getString(R.string.guardian_breakout_alert_msg, wardName, timeStr)
            }

            val wardId = wardIdProvider()
            if (wardId.isNotBlank()) {
                try {
                    val notifTitle = if (isDeactivationPending) {
                        activity.getString(R.string.toast_guardian_deactivation_alert)
                    } else {
                        activity.getString(R.string.notif_breakout_alert_title)
                    }
                    val notifMessage = if (isDeactivationPending) {
                        activity.getString(R.string.guardian_deactivation_alert_msg, wardName, timeStr)
                    } else {
                        activity.getString(R.string.notif_breakout_alert_desc, wardName, timeStr)
                    }
                    AppNotificationManager.getInstance(activity.applicationContext).postEventNotification(
                        category = NotificationCategory.GUARDIAN,
                        title = notifTitle,
                        message = notifMessage,
                        dedupKey = "alert_${wardId}_$unlockAt",
                        cooldownMs = 60 * 60 * 1000L,
                        actionType = NotificationActionType.OPEN_GUARDIAN_PANEL,
                        actionPayload = wardId,
                        systemNotificationId = NotificationRegistry.EVENT_BREAKOUT_REQUEST_ID,
                        channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                        postSystemNotification = true,
                        importance = 3
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error logging breakout alert notification")
                }
            }
        } else {
            cardBreakoutAlertBanner.visibility = View.GONE
            btnApproveImmediateTeardown?.visibility = View.GONE
            btnCancelPendingTeardown?.visibility = View.GONE
        }
    }

    fun hideSelfControlAlert() {
        cardSelfControlAlertBanner.visibility = View.GONE
    }

    fun setInputsEnabled(enabled: Boolean) {
        btnRevertSelfControlChanges.isEnabled = enabled
        btnDismissSelfControlAlert.isEnabled = enabled
        btnRejectBreakout.isEnabled = enabled
        btnApproveImmediateTeardown?.isEnabled = enabled
        btnCancelPendingTeardown?.isEnabled = enabled
    }

    fun onDestroy() {
        // DialogFragments are lifecycle-managed by supportFragmentManager
    }

    companion object {
        private const val TAG = "GuardianAlertsBinder"
    }
}
