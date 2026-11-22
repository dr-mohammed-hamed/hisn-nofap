package com.drmohammed.antiporn.provisioning.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.net.toUri
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Enterprise Provisioning Activity (Android 12+ / API 31+).
 * Handles:
 * 1. [DevicePolicyManager.ACTION_GET_PROVISIONING_MODE]: Declares fully managed device mode.
 * 2. [DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE]: Enforces baseline policies,
 *    re-enables essential system apps via intent-based resolution, and guarantees
 *    returning [Activity.RESULT_OK] to the Setup Wizard on the UI thread under all circumstances.
 */
class EnterpriseProvisioningActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AntiPorn_Provisioning"
        const val ACTION_PROVISIONING_SUCCESSFUL = "android.app.action.PROVISIONING_SUCCESSFUL"

        /**
         * Global flag indicating active compliance flow.
         * Used by [AdminReceiver] to avoid launching MainActivity over the OOBE provisioning screen.
         */
        @Volatile
        var isComplianceFlowActive: Boolean = false
            private set
    }

    private val isProvisioningCompleted = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> {
                handleGetProvisioningMode()
            }
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE,
            ACTION_PROVISIONING_SUCCESSFUL -> {
                isComplianceFlowActive = true
                enableEdgeToEdge()
                setContentView(R.layout.activity_enterprise_provisioning)

                findViewById<View>(R.id.layoutRootEnterpriseProvisioning)?.let { rootView ->
                    val basePadding = (24 * resources.displayMetrics.density).toInt()
                    ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
                        val insets = windowInsets.getInsets(
                            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                        )
                        view.setPadding(
                            basePadding + insets.left,
                            basePadding + insets.top,
                            basePadding + insets.right,
                            basePadding + insets.bottom
                        )
                        windowInsets
                    }
                }

                // Protect provisioning flow against accidental back press dismissal
                onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        Log.w(TAG, "Back button press ignored during active provisioning compliance.")
                    }
                })
                handleAdminPolicyCompliance()
            }
            else -> {
                Log.w(TAG, "Unknown or unsupported provisioning action received: ${intent?.action}")
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
    }

    @SuppressLint("InlinedApi")
    private fun handleGetProvisioningMode() {
        Log.i(TAG, "Setup Wizard queried GET_PROVISIONING_MODE. Resolving target provisioning mode...")
        val allowedModes = intent?.getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES)
        if (allowedModes != null && !allowedModes.contains(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)) {
            Log.e(TAG, "Device does not support FULLY_MANAGED_DEVICE mode (allowed: $allowedModes). Aborting.")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val resultIntent = Intent().apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun handleAdminPolicyCompliance() {
        Log.i(TAG, "Setup Wizard initiated ADMIN_POLICY_COMPLIANCE. Applying baseline shields in background...")
        val appContext = applicationContext

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Enforce baseline security shields (DNS, Anti-tamper, VPN lock, Curfew)
                try {
                    ProtectionEngine.applyAllSync(appContext)
                    Log.i(TAG, "Baseline protection successfully applied during compliance.")
                } catch (t: Throwable) {
                    Log.e(TAG, "Non-fatal error applying protection shields in compliance", t)
                }

                // 2. Dual-layer resilience: ensure core system applications remain active across all OEMs
                try {
                    if (com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(appContext)) {
                        enableCoreSystemApps(appContext)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Non-fatal error enabling core system apps in compliance", t)
                }
            } finally {
                // 3. Transition UI to completed state on UI thread (do NOT finish automatically)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showSetupCompletedUi()
                    }
                }
            }
        }
    }

    private fun showSetupCompletedUi() {
        findViewById<View>(R.id.progressProvisioning)?.visibility = View.GONE
        findViewById<android.widget.TextView>(R.id.tvProvisioningTitle)?.setText(R.string.provisioning_setup_completed_title)
        findViewById<android.widget.TextView>(R.id.tvProvisioningSubtitle)?.setText(R.string.provisioning_setup_completed_subtitle)
        findViewById<View>(R.id.tvProvisioningNotice)?.visibility = View.GONE

        val layoutActions = findViewById<View>(R.id.layoutProvisioningActions)
        layoutActions?.visibility = View.VISIBLE

        findViewById<View>(R.id.btnCompleteProvisioning)?.setOnClickListener {
            completeProvisioningAndExit()
        }

        findViewById<View>(R.id.btnManageSystemApps)?.setOnClickListener {
            val intent = Intent(this, SystemAppsManagerActivity::class.java)
            startActivity(intent)
        }
    }

    private fun completeProvisioningAndExit() {
        val appContext = applicationContext
        if (isProvisioningCompleted.compareAndSet(false, true)) {
            isComplianceFlowActive = false
            // ALWAYS signal RESULT_OK first so Setup Wizard transitions cleanly
            try {
                setResult(Activity.RESULT_OK)
                Log.i(TAG, "Signaled Activity.RESULT_OK to Setup Wizard.")
            } catch (t: Throwable) {
                Log.e(TAG, "Error setting Activity.RESULT_OK", t)
            }

            try {
                // Bootstrap MainActivity so it greets the user upon OOBE conclusion
                val launchIntent = appContext.packageManager?.getLaunchIntentForPackage(appContext.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                } ?: Intent(appContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                appContext.startActivity(launchIntent)
                Log.i(TAG, "Dispatched MainActivity bootstrap intent successfully.")
            } catch (t: Throwable) {
                Log.e(TAG, "Non-fatal error launching MainActivity from compliance activity", t)
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        isComplianceFlowActive = false
        super.onDestroy()
    }

    /**
     * Ultra-defensive core system apps enabler:
     * Combines dynamic Intent-based package resolution with explicit known OEM fallbacks (Samsung, Xiaomi,
     * Realme/ColorOS, Pixel, Transsion). Every single resolution and enable call is isolated in its own
     * try-catch block to guarantee zero crashes on any device.
     */
    private fun enableCoreSystemApps(context: Context) {
        try {
            val pm = context.packageManager ?: return

            // 1. Intent definitions for essential smartphone functionalities
            val coreIntents = listOf(
                Intent(Intent.ACTION_DIAL),                                          // Phone / Dialer
                Intent(Intent.ACTION_SENDTO).apply { data = "smsto:".toUri() },      // SMS / Messages
                Intent(MediaStore.ACTION_IMAGE_CAPTURE),                             // Camera
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY), // Gallery / Photos
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MARKET)   // Pre-installed App Stores
            )

            // Dynamic Intent-based resolution: queries OEM-installed system handlers
            for (intent in coreIntents) {
                // A) Direct intent enablement via DPM
                try {
                    com.drmohammed.antiporn.telemetry.SafeDpmGateway.enableSystemAppBlocking(context, intent)
                } catch (t: Throwable) {
                    Log.d(TAG, "enableSystemApp(intent) skipped for $intent: ${t.message}")
                }

                // B) Resolve matching system package names and enable individually
                try {
                    val resolvedActivities = pm.queryIntentActivities(
                        intent,
                        PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_UNINSTALLED_PACKAGES
                    )
                    for (resolveInfo in resolvedActivities.orEmpty()) {
                        val pkgName = resolveInfo.activityInfo?.packageName
                        if (!pkgName.isNullOrBlank() && pkgName != context.packageName) {
                            try {
                                com.drmohammed.antiporn.telemetry.SafeDpmGateway.enableSystemAppBlocking(context, pkgName)
                                Log.d(TAG, "Enabled resolved system app: $pkgName")
                            } catch (t: Throwable) {
                                Log.d(TAG, "enableSystemApp(pkg) skipped for $pkgName: ${t.message}")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "queryIntentActivities skipped for $intent: ${t.message}")
                }
            }

            // 2. Explicit OEM-specific packages fallback (Google, Samsung, Xiaomi, Realme/Oppo, Transsion)
            val oemKnownPackages = listOf(
                // Google Play Core & Framework
                "com.android.vending",
                "com.google.android.gms",
                "com.google.android.gsf",
                "com.android.chrome",

                // Google Pixel Core
                "com.google.android.dialer",
                "com.google.android.apps.messaging",
                "com.google.android.GoogleCamera",
                "com.google.android.apps.photos",

                // Samsung OneUI Core & Galaxy Store
                "com.samsung.android.dialer",
                "com.samsung.android.messaging",
                "com.sec.android.app.camera",
                "com.sec.android.gallery3d",
                "com.sec.android.app.samsungapps",

                // Xiaomi / MIUI / HyperOS Core & GetApps
                "com.android.contacts",
                "com.android.mms",
                "com.android.camera",
                "com.miui.gallery",
                "com.xiaomi.mipicks",

                // Huawei AppGallery
                "com.huawei.appmarket",

                // Realme / Oppo / OnePlus (ColorOS / OxygenOS)
                "com.coloros.phoneno",
                "com.oppo.camera",
                "com.coloros.gallery3d",
                "com.oppo.market",
                "com.heytap.market",

                // Vivo / Honor / Transsion App Stores
                "com.vivo.appstore",
                "com.hihonor.appmarket",
                "com.transsion.camera",
                "com.transsion.palmstore"
            )

            for (pkg in oemKnownPackages) {
                try {
                    com.drmohammed.antiporn.telemetry.SafeDpmGateway.enableSystemAppBlocking(context, pkg)
                } catch (t: Throwable) {
                    // Normal on devices from other manufacturers; silently proceed
                    Log.v(TAG, "OEM app $pkg not present or already active")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected outer exception in enableCoreSystemApps (safe guard)", t)
        }
    }
}
