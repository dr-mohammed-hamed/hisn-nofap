package com.drmohammed.antiporn.provisioning.ui

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.BidiFormatter
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.provisioning.engine.AccountAuditor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializable payload describing accounts that block Device Owner promotion
 * when automated account bypass is rejected by the OS/OEM.
 */
data class BlockingAccountsPayload(
    val accountLabels: List<String>,
    val accountTypes: List<String>,
    val message: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put(KEY_ACCOUNT_LABELS, JSONArray(accountLabels))
            put(KEY_ACCOUNT_TYPES, JSONArray(accountTypes))
            put(KEY_MESSAGE, message)
        }.toString()
    }

    companion object {
        private const val KEY_ACCOUNT_LABELS = "accountLabels"
        private const val KEY_ACCOUNT_TYPES = "accountTypes"
        private const val KEY_MESSAGE = "message"

        fun fromJson(raw: String?): BlockingAccountsPayload? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val obj = JSONObject(raw)
                val labels = parseStringArray(obj.optJSONArray(KEY_ACCOUNT_LABELS))
                val types = parseStringArray(obj.optJSONArray(KEY_ACCOUNT_TYPES))
                val msg = obj.optString(KEY_MESSAGE, "")
                if (labels.isEmpty() && types.isEmpty() && msg.isBlank()) null
                else BlockingAccountsPayload(
                    accountLabels = labels,
                    accountTypes = types,
                    message = msg
                )
            }.getOrNull()
        }

        private fun parseStringArray(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.optString(i, "").trim()
                    if (item.isNotEmpty()) add(item)
                }
            }
        }
    }
}

/**
 * Interactive Material 3 guidance dialog shown when active device accounts block
 * Device Owner promotion and cannot be bypassed automatically.
 */
internal class ProvisioningAccountsBlockingDialogHelper {

    private var activeDialog: AlertDialog? = null
    private var activePayload: BlockingAccountsPayload? = null

    fun isShowing(): Boolean = activeDialog?.isShowing == true

    fun currentPayload(): BlockingAccountsPayload? = if (isShowing()) activePayload else null

    fun show(
        activity: AppCompatActivity,
        payload: BlockingAccountsPayload,
        onRetryNow: () -> Unit,
        onDismissed: () -> Unit = {}
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activeDialog?.isShowing == true && activePayload == payload) {
            return
        }

        dismiss()
        activePayload = payload

        val labels = payload.accountLabels
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(activity.getString(R.string.provisioning_accounts_fallback_label)) }

        val bidiFormatter = BidiFormatter.getInstance()
        val formattedMessage = buildString {
            append(activity.getString(R.string.provisioning_accounts_dialog_intro))
            append("\n\n")
            append(activity.getString(R.string.provisioning_accounts_dialog_list_header))
            append("\n")
            labels.forEach { label ->
                append(activity.getString(R.string.provisioning_accounts_list_item, bidiFormatter.unicodeWrap(label)))
                append("\n")
            }
            if (payload.accountLabels.isEmpty() && payload.message.isNotBlank()) {
                append("\n")
                append(payload.message)
            }
            append("\n")
            append(activity.getString(R.string.provisioning_accounts_dialog_steps))
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.provisioning_accounts_dialog_title)
            .setMessage(formattedMessage)
            .setPositiveButton(R.string.provisioning_accounts_btn_retry_now) { _, _ ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    onRetryNow()
                }
            }
            .setNeutralButton(R.string.provisioning_accounts_btn_open_settings, null)
            .setNegativeButton(R.string.btn_back, null)
            .setCancelable(true)
            .create()

        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
                activePayload = null
                onDismissed()
            }
        }

        activeDialog = dialog
        dialog.show()

        // Override neutral button after show() so jumping to Account Settings keeps the dialog open
        // and leaves "Retry Now" immediately ready when the user presses Back.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            if (!activity.isFinishing && !activity.isDestroyed) {
                val launched = AccountAuditor.openAccountSettings(
                    context = activity,
                    launchAdjacent = activity.isInMultiWindowMode
                )
                if (!launched) {
                    Toast.makeText(activity, R.string.toast_open_settings_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun dismiss() {
        activeDialog?.let { dialog ->
            activeDialog = null
            activePayload = null
            if (dialog.isShowing) {
                runCatching { dialog.dismiss() }
            }
        }
    }
}
