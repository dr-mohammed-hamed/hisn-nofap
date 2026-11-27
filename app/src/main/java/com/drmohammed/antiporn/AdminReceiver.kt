package com.drmohammed.antiporn

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AntiPorn_AdminReceiver"
        const val ACTION_ENFORCE_POLICIES = "com.drmohammed.antiporn.ENFORCE_POLICIES"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        val appContext = context.applicationContext ?: context
        if (!com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(appContext)) {
            Timber.tag(TAG).i("Device Admin enabled transiently without Device Owner; deferring timestamp and policy sync.")
            return
        }
        Timber.tag(TAG).i("Device Admin / Owner enabled. Enforcing DNS and security policies...")
        try {
            com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(appContext).recordDeviceOwnerEnabledTimestamp()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error recording DO enabled timestamp in onEnabled")
        }
        enforcePoliciesAsync(appContext)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Timber.tag(TAG).i("Profile provisioning complete. Enforcing DNS and security policies...")
        val appContext = context.applicationContext ?: context
        try {
            com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(appContext).recordDeviceOwnerEnabledTimestamp()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error recording DO enabled timestamp in onProfileProvisioningComplete")
        }
        enforcePoliciesAsync(appContext)

        // Bootstrap MainActivity as a fallback strictly for legacy Android versions (Android 11 and below)
        // ONLY if EnterpriseProvisioningActivity is not actively handling the OOBE compliance flow
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            if (!com.drmohammed.antiporn.provisioning.ui.EnterpriseProvisioningActivity.isComplianceFlowActive) {
                try {
                    val launchIntent = appContext.packageManager?.getLaunchIntentForPackage(appContext.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    } ?: Intent(appContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    appContext.startActivity(launchIntent)
                    Timber.tag(TAG).i("Dispatched MainActivity launch intent from AdminReceiver fallback.")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to launch MainActivity in onProfileProvisioningComplete")
                }
            } else {
                Timber.tag(TAG).i("EnterpriseProvisioningActivity is actively running compliance flow; skipping redundant AdminReceiver launch.")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_ENFORCE_POLICIES -> {
                Timber.tag(TAG).i("Broadcast received (${intent?.action}). Enforcing DNS policies...")
                enforcePoliciesAsync(context)
            }
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Timber.tag(TAG).w("Device Admin disabled.")
        val appContext = context.applicationContext ?: context
        try {
            com.drmohammed.antiporn.watchdog.WatchdogOrchestrator.stopAll(appContext)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error stopping watchdog in onDisabled")
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.admin_disable_warning)
    }

    private fun enforcePoliciesAsync(context: Context) {
        val appContext = context.applicationContext ?: context
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter.isPromotionActive) {
                    Timber.tag(TAG).i("Promotion active; deferring AdminReceiver enforcement to AdbPostPromotionVerifier.")
                    return@launch
                }
                try {
                    com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(appContext).startFirestoreSync()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error initiating PolicyRepository sync in AdminReceiver")
                }
                try {
                    com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(appContext).syncZeroTrustBaselineAndReconcileAsync()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error initiating syncZeroTrustBaselineAndReconcileAsync in AdminReceiver")
                }
                try {
                    com.drmohammed.antiporn.core.engine.SystemAppsHealingManager.healPreInstalledStoresSafely(appContext)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error executing SystemAppsHealingManager in AdminReceiver")
                }
                com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(appContext).ensureInitialBaselineSeeded()
                com.drmohammed.antiporn.core.engine.ProtectionEngine.applyAllSync(appContext)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error applying policies in AdminReceiver")
            } finally {
                pendingResult?.finish()
            }
        }
    }

}
