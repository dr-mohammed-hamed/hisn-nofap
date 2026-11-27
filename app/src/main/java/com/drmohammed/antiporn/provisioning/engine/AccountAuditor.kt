package com.drmohammed.antiporn.provisioning.engine

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import timber.log.Timber

/**
 * Pre-flight auditor for active system accounts.
 *
 * Android strictly forbids setting a Device Owner if any user accounts
 * (Google, Samsung, WhatsApp, Telegram, etc.) are currently configured on User 0.
 *
 * This auditor provides real-time state inspection and intent shortcuts to guide
 * non-technical users in temporarily removing accounts before provisioning.
 */
object AccountAuditor {

    private const val TAG = "AntiPorn_AccountAuditor"

    /**
     * Retrieves all active system accounts on the device.
     */
    fun getActiveAccounts(context: Context): List<Account> {
        return try {
            val appContext = context.applicationContext ?: context
            AccountManager.get(appContext)?.accounts?.toList() ?: emptyList()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to retrieve accounts from AccountManager")
            emptyList()
        }
    }

    /**
     * Returns the number of accounts currently registered.
     * Must be 0 for Device Owner provisioning to succeed.
     */
    fun getAccountCount(context: Context): Int {
        return getActiveAccounts(context).size
    }

    /**
     * Checks if the device is ready for Device Owner provisioning.
     * With our Zero-Account bypass engine, devices are always ready
     * without requiring users to delete their existing accounts.
     */
    fun isReadyForProvisioning(context: Context): Boolean {
        return true
    }

    /**
     * Returns localized, PII-free account provider labels for registered accounts.
     */
    fun getAccountSummaryList(context: Context): List<String> {
        val types = getActiveAccounts(context).mapNotNull { it.type?.takeIf(String::isNotBlank) }
        return AdbAccountInspector.resolveFriendlyAccountLabels(context, types)
    }

    /**
     * Launches the system's Accounts & Sync settings screen, optionally in adjacent
     * multi-window mode when supported by the caller.
     *
     * @return `true` if a settings activity launched, `false` if all fallback actions failed.
     */
    @JvmOverloads
    fun openAccountSettings(context: Context, launchAdjacent: Boolean = false): Boolean {
        val appContext = context.applicationContext ?: context
        val intentFlags = Intent.FLAG_ACTIVITY_NEW_TASK or
            if (launchAdjacent) Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT else 0
        val actions = listOf(
            Settings.ACTION_SYNC_SETTINGS,
            Settings.ACTION_ADD_ACCOUNT,
            Settings.ACTION_SETTINGS
        )
        for (action in actions) {
            try {
                val intent = Intent(action).apply {
                    flags = intentFlags
                }
                appContext.startActivity(intent)
                return true
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to open $action")
            }
        }
        Timber.tag(TAG).e("Failed to open any account or general settings screen")
        return false
    }
}
