package com.drmohammed.antiporn.provisioning.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.provisioning.engine.AdbSecondaryUserInspector
import com.drmohammed.antiporn.provisioning.engine.BlockingSecondaryUser
import com.drmohammed.antiporn.provisioning.engine.OemProvisioningHelper
import com.drmohammed.antiporn.provisioning.engine.ProvisioningAckStore
import com.drmohammed.antiporn.provisioning.service.AdbPairingNotificationService
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import timber.log.Timber
import java.util.Collections
import java.util.WeakHashMap

/**
 * Manages pre-activation OEM danger acknowledgments, developer-options prompts,
 * and wireless debugging settings navigation for [ProvisioningWizardActivity].
 */
internal class ProvisioningDangerDialogHelper(
    private val onConflictingProfileCleared: () -> Unit = {}
) {

    private var dangerDialog: AlertDialog? = null
    private var enableDevDialog: AlertDialog? = null
    private val suppressedDialogs = Collections.newSetFromMap(WeakHashMap<AlertDialog, Boolean>())

    fun ensureDangerAcknowledgedBeforeAction(activity: AppCompatActivity, onReady: () -> Unit) {
        if (!OemProvisioningHelper.requiresPreActivationDangerAck(activity) || ProvisioningAckStore.hasValidAck(activity)) {
            onReady()
            return
        }
        showDangerPrivateSpaceDialog(
            activity = activity,
            onProceed = {
                ProvisioningAckStore.recordAck(activity)
                AdbPairingNotificationService.refreshNotificationState(activity)
                onReady()
            },
            onCancel = {
                ProvisioningAckStore.clearAck(activity)
            }
        )
    }

    fun showDangerPrivateSpaceDialog(
        activity: AppCompatActivity,
        onProceed: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        dismissDangerDialogOnly()

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_private_space_danger, null)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dangerDialog = dialog

        val copy = OemProvisioningHelper.getDangerDialogStrings(activity)
        dialogView.findViewById<TextView>(R.id.tvDangerTitle).setText(copy.titleRes)
        dialogView.findViewById<TextView>(R.id.tvDangerDesc).setText(copy.descRes)

        val chkAcknowledgeDanger = dialogView.findViewById<MaterialCheckBox>(R.id.chkAcknowledgeDanger)
        val btnDialogProceed = dialogView.findViewById<MaterialButton>(R.id.btnDialogProceed)
        val btnDialogRecheck = dialogView.findViewById<MaterialButton>(R.id.btnDialogRecheck)
        val btnDialogCancel = dialogView.findViewById<MaterialButton>(R.id.btnDialogCancel)

        btnDialogRecheck.visibility = if (copy.showRecheckButton) View.VISIBLE else View.GONE
        chkAcknowledgeDanger.setOnCheckedChangeListener { _, isChecked ->
            btnDialogProceed.isEnabled = isChecked
        }

        var proceeded = false
        var canceled = false
        val notifyCancel = {
            if (!canceled && !proceeded) {
                canceled = true
                onCancel?.invoke()
            }
        }

        btnDialogProceed.setOnClickListener {
            if (proceeded) return@setOnClickListener
            proceeded = true
            btnDialogProceed.isEnabled = false
            if (!activity.isFinishing && !activity.isDestroyed) {
                suppressedDialogs.add(dialog)
                dialog.dismiss()
            }
            onProceed()
        }

        btnDialogRecheck.setOnClickListener {
            if (!OemProvisioningHelper.hasConflictingProfile(activity)) {
                Toast.makeText(activity, R.string.dialog_danger_private_space_cleared, Toast.LENGTH_LONG).show()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    dialog.dismiss()
                }
                onConflictingProfileCleared()
            } else {
                Toast.makeText(activity, R.string.dialog_danger_private_space_still_exists, Toast.LENGTH_LONG).show()
            }
        }

        btnDialogCancel.setOnClickListener {
            notifyCancel()
            if (!activity.isFinishing && !activity.isDestroyed) {
                suppressedDialogs.add(dialog)
                dialog.dismiss()
            }
        }

        dialog.setOnDismissListener {
            if (dangerDialog === dialog) {
                dangerDialog = null
            }
            val suppress = suppressedDialogs.remove(dialog)
            if (!suppress) {
                notifyCancel()
            }
        }

        dialog.show()
    }

    fun showEnableDevOptionsDialog(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        enableDevDialog?.let { existing ->
            if (existing.isShowing) {
                runCatching { existing.dismiss() }
            }
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dialog_enable_dev_title)
            .setMessage(OemProvisioningHelper.getDevOptionsDialogDescription())
            .setPositiveButton(R.string.dialog_enable_dev_btn_open) { _, _ ->
                startSettingsWithFallback(
                    activity,
                    listOf(
                        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
                        Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    )
                )
            }
            .setNegativeButton(R.string.btn_back, null)
            .create()
        dialog.setOnDismissListener {
            if (enableDevDialog === dialog) {
                enableDevDialog = null
            }
        }
        enableDevDialog = dialog
        dialog.show()
    }

    fun launchWirelessDebuggingScreen(context: Context) {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(
                    Intent("android.service.quicksettings.action.QS_TILE_PREFERENCES").apply {
                        putExtra(
                            Intent.EXTRA_COMPONENT_NAME,
                            ComponentName(
                                "com.android.settings",
                                "com.android.settings.development.qstile.DevelopmentTiles\$WirelessDebugging"
                            )
                        )
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            }
            add(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            add(Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        }
        startSettingsWithFallback(context, candidates)
    }

    fun launchAdjacentDevSettings(context: Context) {
        val adjacentFlags = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK
        val highlighted = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = adjacentFlags
            val args = Bundle().apply {
                putString(":settings:fragment_args_key", "toggle_adb_wireless")
            }
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            putExtra(":settings:show_fragment_args", args)
        }
        val plain = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply { flags = adjacentFlags }
        val settings = Intent(Settings.ACTION_SETTINGS).apply { flags = adjacentFlags }
        startSettingsWithFallback(context, listOf(highlighted, plain, settings))
    }

    fun openWifiConnectivitySettings(context: Context) {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            }
            add(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        startSettingsWithFallback(context, candidates)
    }

    fun handleDevOptionsAction(activity: AppCompatActivity, isWifiValid: Boolean) {
        if (!isWifiValid) {
            Toast.makeText(activity, R.string.provisioning_wifi_required_title, Toast.LENGTH_SHORT).show()
            return
        }
        ensureDangerAcknowledgedBeforeAction(activity) {
            if (!isDevOptionsEnabled(activity)) showEnableDevOptionsDialog(activity)
            else launchWirelessDebuggingScreen(activity)
        }
    }

    fun isDevOptionsEnabled(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    } catch (_: Exception) {
        false
    }

    fun openBlockingUsersSettings(context: Context, users: List<BlockingSecondaryUser>) {
        try {
            context.startActivity(AdbSecondaryUserInspector.resolveSettingsIntentForUsers(context, users))
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed opening settings for blocking secondary users")
            Toast.makeText(context, R.string.toast_open_settings_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun isAnyDialogShowing(): Boolean = dangerDialog?.isShowing == true || enableDevDialog?.isShowing == true

    fun dismiss() {
        dismissDangerDialogOnly()
        enableDevDialog?.let { existing ->
            if (existing.isShowing) {
                runCatching { existing.dismiss() }
            }
        }
        enableDevDialog = null
    }

    private fun dismissDangerDialogOnly() {
        dangerDialog?.let { existing ->
            if (existing.isShowing) {
                suppressedDialogs.add(existing)
                runCatching { existing.dismiss() }
            }
        }
        dangerDialog = null
    }

    private fun startSettingsWithFallback(context: Context, candidates: List<Intent>) {
        Companion.startSettingsWithFallback(context, candidates)
    }

    companion object {
        private const val TAG = "AntiPorn_DangerDlg"

        internal fun startSettingsWithFallback(context: Context, candidates: List<Intent>) {
            for (intent in candidates) {
                try {
                    context.startActivity(intent)
                    return
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Settings launch attempt failed for ${intent.action}")
                }
            }
            Toast.makeText(context, R.string.toast_open_settings_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
