package com.drmohammed.antiporn.ui.accountability

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.GuardianTeardownAlreadyCompletedException
import com.drmohammed.antiporn.data.repository.cancelGuardianImmediateTeardown
import com.drmohammed.antiporn.data.repository.completeGuardianSideTeardownCleanup
import com.drmohammed.antiporn.data.repository.isPendingGuardianTeardown
import com.drmohammed.antiporn.data.repository.requestGuardianImmediateTeardown
import com.drmohammed.antiporn.ui.control.other.OtherSettingsActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Shared two-stage severe confirmation UI helper for Guardian-authorized immediate
 * Device Owner removal from a supervised ward's phone.
 */
class GuardianRemoteTeardownUiHelper {

    private var activeDialog: AlertDialog? = null
    private var countdownJob: Job? = null

    fun showTwoStageSevereWarningDialog(
        activity: AppCompatActivity,
        wardName: String,
        onConfirmed: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        dismiss()

        val displayName = wardName.trim().ifBlank {
            activity.getString(R.string.status_ward_badge)
        }

        val stage1Dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dialog_remote_teardown_stage1_title)
            .setMessage(activity.getString(R.string.dialog_remote_teardown_stage1_msg, displayName))
            .setPositiveButton(R.string.btn_remote_teardown_stage1_continue) { dialog, _ ->
                dialog.dismiss()
                showStage2FinalConfirmation(activity, displayName, onConfirmed)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        stage1Dialog.setOnShowListener {
            val redColor = MaterialColors.getColor(
                activity,
                androidx.appcompat.R.attr.colorError,
                ContextCompat.getColor(activity, R.color.status_red)
            )
            stage1Dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(redColor)
        }
        stage1Dialog.setOnDismissListener {
            if (activeDialog === stage1Dialog) {
                activeDialog = null
            }
        }
        activeDialog = stage1Dialog
        stage1Dialog.show()
    }

    private fun showStage2FinalConfirmation(
        activity: AppCompatActivity,
        displayName: String,
        onConfirmed: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        dismiss()

        val density = activity.resources.displayMetrics.density
        val padH = (24 * density).toInt()
        val padTop = (12 * density).toInt()
        val padBottom = (4 * density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padTop, padH, padBottom)
        }

        val onSurface = MaterialColors.getColor(
            container,
            com.google.android.material.R.attr.colorOnSurface
        )
        val redColor = MaterialColors.getColor(
            container,
            androidx.appcompat.R.attr.colorError,
            ContextCompat.getColor(activity, R.color.status_red)
        )

        val messageView = TextView(activity).apply {
            text = activity.getString(R.string.dialog_remote_teardown_stage2_msg, displayName)
            textSize = 14f
            setTextColor(onSurface)
            setLineSpacing(0f, 1.15f)
        }

        val ackCheckBox = MaterialCheckBox(activity).apply {
            text = activity.getString(R.string.checkbox_remote_teardown_acknowledge)
            textSize = 13f
            setTextColor(redColor)
            val topMargin = (14 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.topMargin = topMargin
            }
        }

        container.addView(messageView)
        container.addView(ackCheckBox)

        val stage2Dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dialog_remote_teardown_stage2_title)
            .setView(container)
            .setPositiveButton(
                activity.getString(R.string.btn_remote_teardown_confirm_countdown, COUNTDOWN_SECONDS),
                null
            )
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        stage2Dialog.setOnShowListener {
            val positiveBtn = stage2Dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            var remainingSeconds = COUNTDOWN_SECONDS

            fun syncButtonState() {
                if (activity.isFinishing || activity.isDestroyed) return
                val ready = remainingSeconds <= 0 && ackCheckBox.isChecked
                positiveBtn?.isEnabled = ready
                positiveBtn?.alpha = if (ready) 1.0f else 0.5f
                positiveBtn?.setTextColor(redColor)
                positiveBtn?.text = if (remainingSeconds > 0) {
                    activity.getString(R.string.btn_remote_teardown_confirm_countdown, remainingSeconds)
                } else {
                    activity.getString(R.string.btn_remote_teardown_confirm_final)
                }
            }

            syncButtonState()
            ackCheckBox.setOnCheckedChangeListener { _, _ -> syncButtonState() }

            positiveBtn?.setOnClickListener {
                if (remainingSeconds > 0 || !ackCheckBox.isChecked) return@setOnClickListener
                stage2Dialog.dismiss()
                onConfirmed()
            }

            countdownJob?.cancel()
            countdownJob = activity.lifecycleScope.launch {
                while (remainingSeconds > 0) {
                    delay(1_000L)
                    if (activity.isFinishing || activity.isDestroyed || !stage2Dialog.isShowing) {
                        break
                    }
                    remainingSeconds--
                    syncButtonState()
                }
            }
        }

        stage2Dialog.setOnDismissListener {
            countdownJob?.cancel()
            countdownJob = null
            if (activeDialog === stage2Dialog) {
                activeDialog = null
            }
        }
        activeDialog = stage2Dialog
        stage2Dialog.show()
    }

    fun showCancelPendingTeardownDialog(
        activity: AppCompatActivity,
        wardName: String,
        onConfirmed: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        dismiss()

        val displayName = wardName.trim().ifBlank {
            activity.getString(R.string.status_ward_badge)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dialog_cancel_remote_teardown_title)
            .setMessage(activity.getString(R.string.dialog_cancel_remote_teardown_msg, displayName))
            .setPositiveButton(R.string.btn_confirm_cancel_remote_teardown) { d, _ ->
                d.dismiss()
                onConfirmed()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
            }
        }
        activeDialog = dialog
        dialog.show()
    }

    fun bindOtherSettingsDangerZone(
        activity: AppCompatActivity,
        wardId: String,
        wardName: String,
        policyProvider: () -> DevicePolicyBundle,
        onPolicyUpdated: (DevicePolicyBundle) -> Unit,
        isExternalBusy: () -> Boolean = { false },
        onBusyChanged: (Boolean) -> Unit = {},
        onAlreadyCompleted: () -> Unit = {}
    ): () -> Unit {
        val card = activity.findViewById<MaterialCardView>(R.id.cardGuardianDangerTeardown) ?: return {}
        val btnRemove = activity.findViewById<MaterialButton>(R.id.btnRemoteRemoveDeviceOwner) ?: return {}
        val layoutPending = activity.findViewById<LinearLayout>(R.id.layoutPendingRemoteTeardown) ?: return {}
        val btnCancel = activity.findViewById<MaterialButton>(R.id.btnCancelRemoteTeardown) ?: return {}

        if (wardId.isBlank()) {
            card.visibility = View.GONE
            return {}
        }

        card.visibility = View.VISIBLE
        val accountRepo = AccountabilityRepository.getInstance(activity.applicationContext)
        var inFlight = false

        fun renderState(policy: DevicePolicyBundle) {
            val isPending = (policy.deactivationStatus == DevicePolicyBundle.DEACTIVATION_STATUS_PENDING &&
                (policy.remoteTeardownRequestedAt ?: 0L) > 0L) || accountRepo.isPendingGuardianTeardown(wardId)
            val buttonsEnabled = !inFlight && !isExternalBusy()
            btnRemove.visibility = if (isPending) View.GONE else View.VISIBLE
            layoutPending.visibility = if (isPending) View.VISIBLE else View.GONE
            btnRemove.isEnabled = buttonsEnabled
            btnCancel.isEnabled = buttonsEnabled
        }

        renderState(policyProvider())

        btnRemove.setOnClickListener {
            if (inFlight || isExternalBusy() || activity.isFinishing || activity.isDestroyed) return@setOnClickListener
            showTwoStageSevereWarningDialog(activity, wardName) {
                if (inFlight || isExternalBusy() || activity.isFinishing || activity.isDestroyed) return@showTwoStageSevereWarningDialog
                inFlight = true
                onBusyChanged(true)
                renderState(policyProvider())
                activity.lifecycleScope.launch {
                    try {
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
                                onPolicyUpdated(updated)
                                val resIntent = Intent().apply {
                                    putExtra(OtherSettingsActivity.EXTRA_UPDATED_POLICY_JSON, updated.toJson())
                                }
                                activity.setResult(Activity.RESULT_OK, resIntent)
                                Toast.makeText(
                                    activity.applicationContext,
                                    R.string.toast_guardian_remote_teardown_sent,
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            onFailure = { err ->
                                val errText = err.localizedMessage
                                    ?: activity.getString(R.string.error_unknown)
                                Toast.makeText(
                                    activity.applicationContext,
                                    activity.getString(R.string.toast_guardian_remote_teardown_failed, errText),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Timber.tag(TAG).e(e, "Error triggering remote teardown from OtherSettings")
                        val errText = e.localizedMessage
                            ?: activity.getString(R.string.error_unknown)
                        Toast.makeText(
                            activity.applicationContext,
                            activity.getString(R.string.toast_guardian_remote_teardown_failed, errText),
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        inFlight = false
                        onBusyChanged(false)
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            renderState(policyProvider())
                        }
                    }
                }
            }
        }

        btnCancel.setOnClickListener {
            if (inFlight || isExternalBusy() || activity.isFinishing || activity.isDestroyed) return@setOnClickListener
            showCancelPendingTeardownDialog(activity, wardName) {
                if (inFlight || isExternalBusy() || activity.isFinishing || activity.isDestroyed) return@showCancelPendingTeardownDialog
                inFlight = true
                onBusyChanged(true)
                renderState(policyProvider())
                activity.lifecycleScope.launch {
                    try {
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
                                onPolicyUpdated(updated)
                                val resIntent = Intent().apply {
                                    putExtra(OtherSettingsActivity.EXTRA_UPDATED_POLICY_JSON, updated.toJson())
                                }
                                activity.setResult(Activity.RESULT_OK, resIntent)
                                Toast.makeText(
                                    activity.applicationContext,
                                    R.string.toast_guardian_remote_teardown_cancelled,
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onFailure = { err ->
                                if (err is GuardianTeardownAlreadyCompletedException) {
                                    onAlreadyCompleted()
                                } else {
                                    val errText = err.localizedMessage
                                        ?: activity.getString(R.string.error_unknown)
                                    Toast.makeText(
                                        activity.applicationContext,
                                        activity.getString(R.string.toast_guardian_remote_teardown_failed, errText),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Timber.tag(TAG).e(e, "Error cancelling remote teardown from OtherSettings")
                        val errText = e.localizedMessage
                            ?: activity.getString(R.string.error_unknown)
                        Toast.makeText(
                            activity.applicationContext,
                            activity.getString(R.string.toast_guardian_remote_teardown_failed, errText),
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        inFlight = false
                        onBusyChanged(false)
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            renderState(policyProvider())
                        }
                    }
                }
            }
        }

        return {
            if (!activity.isFinishing && !activity.isDestroyed) {
                renderState(policyProvider())
            }
        }
    }

    fun syncRemoteCompletionStateOnResume(
        activity: AppCompatActivity,
        wardId: String,
        wardName: String,
        onCompleted: () -> Unit
    ) {
        if (wardId.isBlank() || activity.isFinishing || activity.isDestroyed) return
        val appContext = activity.applicationContext
        val accountRepo = AccountabilityRepository.getInstance(appContext)
        activity.lifecycleScope.launch(Dispatchers.IO) {
            var completed = false
            try {
                val partnerDao = HisnRoomDatabase.getInstance(appContext).partnerDao()
                val partner = partnerDao.getPartnerByIdAndRole(wardId, PartnerEntity.ROLE_WARD)
                if (partner == null && !accountRepo.isPendingGuardianTeardown(wardId)) {
                    completed = true
                } else {
                    val snapshot = try {
                        FirebaseFirestore.getInstance()
                            .collection("policies")
                            .document(wardId)
                            .get()
                            .await()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (e is FirebaseFirestoreException &&
                            e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED &&
                            FirebaseAuth.getInstance().currentUser != null &&
                            (accountRepo.isPendingGuardianTeardown(wardId) || partner == null)
                        ) {
                            accountRepo.completeGuardianSideTeardownCleanup(wardId, wardName)
                            completed = true
                            null
                        } else {
                            throw e
                        }
                    }
                    if (!completed && snapshot != null && snapshot.exists() && snapshot.data != null) {
                        val policy = DevicePolicyBundle.fromMap(snapshot.data ?: emptyMap())
                        if (policy.deactivationStatus == DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED &&
                            (accountRepo.isPendingGuardianTeardown(wardId) || !policy.isSupervised)
                        ) {
                            accountRepo.completeGuardianSideTeardownCleanup(wardId, wardName)
                            completed = true
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).d(e, "Resume teardown completion check skipped")
            }
            if (completed) {
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onCompleted()
                    }
                }
            }
        }
    }

    fun dismiss() {
        countdownJob?.cancel()
        countdownJob = null
        activeDialog?.dismiss()
        activeDialog = null
    }

    companion object {
        private const val TAG = "GuardianTeardownUi"
        private const val COUNTDOWN_SECONDS = 5
    }
}
