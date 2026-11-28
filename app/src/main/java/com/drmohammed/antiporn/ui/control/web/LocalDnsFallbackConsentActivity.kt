package com.drmohammed.antiporn.ui.control.web

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.Window
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.network.dns.DnsFallbackConsentNotifier
import com.drmohammed.antiporn.core.network.dns.DnsResilienceManager
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Lightweight dialog Activity requesting one-time user consent to activate
 * the silent Local DNS VPN fallback engine when Private DNS stalls.
 */
class LocalDnsFallbackConsentActivity : AppCompatActivity() {

    private var activeDialog: AlertDialog? = null
    private var isAwaitingVpnConsent = false
    private var isApproving = false

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isAwaitingVpnConsent = false
        if (isFinishing || isDestroyed) return@registerForActivityResult
        val granted = result.resultCode == Activity.RESULT_OK ||
            runCatching { VpnService.prepare(this) == null }.getOrDefault(false)
        if (granted) {
            proceedWithFallbackActivation()
        } else {
            Toast.makeText(applicationContext, R.string.vpn_permission_denied_msg, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        isAwaitingVpnConsent = savedInstanceState?.getBoolean(STATE_AWAITING_VPN_CONSENT, false) ?: false
        isApproving = savedInstanceState?.getBoolean(STATE_IS_APPROVING, false) ?: false
        if (!isAwaitingVpnConsent && !isApproving) {
            showConsentDialog()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!isAwaitingVpnConsent && !isApproving) {
            showConsentDialog()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AWAITING_VPN_CONSENT, isAwaitingVpnConsent)
        outState.putBoolean(STATE_IS_APPROVING, isApproving)
    }

    private fun showConsentDialog() {
        if (isFinishing || isDestroyed || isApproving) return
        val netId = intent?.getStringExtra(EXTRA_NET_ID)
        if (!netId.isNullOrBlank()) {
            Timber.d("Showing Local DNS VPN fallback consent dialog for network %s", netId)
        }
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_dns_fallback_consent_title)
            .setMessage(R.string.dialog_dns_fallback_consent_desc)
            .setPositiveButton(R.string.btn_dns_fallback_approve) { _, _ ->
                onApproveClicked()
            }
            .setNegativeButton(R.string.btn_dns_fallback_not_now) { _, _ ->
                DnsFallbackConsentNotifier.cancelNotification(applicationContext)
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }

    private fun onApproveClicked() {
        if (isApproving || isAwaitingVpnConsent) return
        val appCtx = applicationContext
        val policy = PolicyRepository.getInstance(appCtx).getCurrentPolicy()
        val needsVpnPrepare = !SafeDpmGateway.isDeviceOwner(this) || !policy.isBlockExternalVpnEnabled
        if (needsVpnPrepare) {
            val prepareIntent = runCatching { VpnService.prepare(this) }.getOrElse {
                Toast.makeText(appCtx, R.string.vpn_permission_denied_msg, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            if (prepareIntent != null) {
                isAwaitingVpnConsent = true
                runCatching { vpnConsentLauncher.launch(prepareIntent) }.onFailure {
                    isAwaitingVpnConsent = false
                    Toast.makeText(appCtx, R.string.vpn_permission_denied_msg, Toast.LENGTH_SHORT).show()
                    finish()
                }
                return
            }
        }
        proceedWithFallbackActivation()
    }

    private fun proceedWithFallbackActivation() {
        if (isApproving) return
        isApproving = true
        val appCtx = applicationContext
        lifecycleScope.launch {
            try {
                val approved = withContext(Dispatchers.IO + NonCancellable) {
                    val updated = PolicyRepository.getInstance(appCtx).approveLocalVpnFallbackLocally()
                    if (updated != null) {
                        DnsFallbackConsentNotifier.cancelNotification(appCtx)
                        DnsResilienceManager.triggerImmediateEvaluationForActiveNetwork(appCtx)
                    }
                    updated
                }
                if (approved != null) {
                    Toast.makeText(appCtx, R.string.toast_dns_fallback_enabled, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(appCtx, R.string.toast_self_policy_save_failed, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed approving Local DNS VPN fallback")
                Toast.makeText(appCtx, R.string.toast_self_policy_save_failed, Toast.LENGTH_LONG).show()
            } finally {
                isApproving = false
                if (!isFinishing && !isDestroyed) {
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        activeDialog?.dismiss()
        activeDialog = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NET_ID = "extra_net_id"
        private const val STATE_AWAITING_VPN_CONSENT = "state_awaiting_vpn_consent"
        private const val STATE_IS_APPROVING = "state_is_approving"
    }
}
