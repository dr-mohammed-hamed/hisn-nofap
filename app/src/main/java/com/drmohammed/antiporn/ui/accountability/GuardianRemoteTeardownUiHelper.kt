package com.drmohammed.antiporn.ui.accountability

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.GuardianTeardownAlreadyCompletedException
import com.drmohammed.antiporn.data.repository.cancelGuardianImmediateTeardown
import com.drmohammed.antiporn.data.repository.completeGuardianSideTeardownCleanup
import com.drmohammed.antiporn.data.repository.isPendingGuardianTeardown
import com.drmohammed.antiporn.data.repository.requestGuardianImmediateTeardown
import com.drmohammed.antiporn.ui.common.showSafely
import com.drmohammed.antiporn.ui.control.other.OtherSettingsActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Shared coordinator for Guardian-authorized immediate Device Owner removal UI
 * in OtherSettingsActivity and resume completion sync.
 */
class GuardianRemoteTeardownUiHelper {

    class OtherSettingsDangerZoneBinding(
        val refresh: () -> Unit,
        val executeImmediateTeardown: () -> Unit,
        val executeCancelTeardown: () -> Unit
    )

    fun bindOtherSettingsDangerZone(
        activity: AppCompatActivity,
        wardId: String,
        wardName: String,
        policyProvider: () -> DevicePolicyBundle,
        onPolicyUpdated: (DevicePolicyBundle) -> Unit,
        isExternalBusy: () -> Boolean = { false },
        onBusyChanged: (Boolean) -> Unit = {},
        onAlreadyCompleted: () -> Unit = {}
    ): OtherSettingsDangerZoneBinding {
        val noOp = OtherSettingsDangerZoneBinding({}, {}, {})
        val card = activity.findViewById<MaterialCardView>(R.id.cardGuardianDangerTeardown) ?: return noOp
        val btnRemove = activity.findViewById<MaterialButton>(R.id.btnRemoteRemoveDeviceOwner) ?: return noOp
        val layoutPending = activity.findViewById<LinearLayout>(R.id.layoutPendingRemoteTeardown) ?: return noOp
        val btnCancel = activity.findViewById<MaterialButton>(R.id.btnCancelRemoteTeardown) ?: return noOp

        if (wardId.isBlank()) {
            card.visibility = View.GONE
            return noOp
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
            activity.supportFragmentManager.showSafely(RemoteTeardownDialogFragment.TAG_STAGE1) {
                RemoteTeardownDialogFragment.newInstance(
                    RemoteTeardownDialogFragment.MODE_STAGE1_WARNING,
                    wardName
                )
            }
        }

        btnCancel.setOnClickListener {
            if (inFlight || isExternalBusy() || activity.isFinishing || activity.isDestroyed) return@setOnClickListener
            activity.supportFragmentManager.showSafely(RemoteTeardownDialogFragment.TAG_CANCEL) {
                RemoteTeardownDialogFragment.newInstance(
                    RemoteTeardownDialogFragment.MODE_CANCEL_PENDING,
                    wardName
                )
            }
        }

        val executeImmediate: () -> Unit = {
            if (!inFlight && !isExternalBusy() && !activity.isFinishing && !activity.isDestroyed) {
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
                                val errText = err.localizedMessage ?: activity.getString(R.string.error_unknown)
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
                        val errText = e.localizedMessage ?: activity.getString(R.string.error_unknown)
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

        val executeCancel: () -> Unit = {
            if (!inFlight && !isExternalBusy() && !activity.isFinishing && !activity.isDestroyed) {
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
                                    val errText = err.localizedMessage ?: activity.getString(R.string.error_unknown)
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
                        val errText = e.localizedMessage ?: activity.getString(R.string.error_unknown)
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

        return OtherSettingsDangerZoneBinding(
            refresh = {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    renderState(policyProvider())
                }
            },
            executeImmediateTeardown = executeImmediate,
            executeCancelTeardown = executeCancel
        )
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
                val partner = partnerDao.getPartnerById(wardId)
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
        // DialogFragment lifecycle is managed by FragmentManager across rotation
    }

    companion object {
        private const val TAG = "GuardianTeardownUi"
    }
}
