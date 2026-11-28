package com.drmohammed.antiporn.notification.router

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.fortress.engine.EffectiveRestrictionResolver
import com.drmohammed.antiporn.notification.model.InAppNotification
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.ui.accountability.GuardianControlActivity
import com.drmohammed.antiporn.ui.control.web.LocalDnsFallbackConsentActivity

/**
 * Smart Action Router responsible for executing appropriate deep-linking
 * and screen navigation when in-app notifications are tapped.
 */
object NotificationActionRouter {

    private const val TAG = "AntiPorn_NotifRouter"
    const val EXTRA_NAVIGATE_TAB = "extra_navigate_tab"
    const val TAB_HOME = 0
    const val TAB_SELF_CONTROL = 1
    const val TAB_ACCOUNTABILITY = 2
    const val TAB_SETTINGS = 3

    /**
     * Executes the navigation action associated with an in-app notification.
     */
    fun route(context: Context, notification: InAppNotification) {
        route(context, notification.actionType, notification.actionPayload)
    }

    /**
     * Dispatches navigation based on action type key and optional payload.
     */
    fun route(context: Context, actionTypeKey: String, payload: String?) {
        route(context, NotificationActionType.fromKey(actionTypeKey), payload)
    }

    /**
     * Dispatches navigation based on action type and optional payload.
     */
    fun route(context: Context, actionType: NotificationActionType, payload: String?) {
        Log.i(TAG, "Routing notification action: ${actionType.key} with payload: $payload")
        when (actionType) {
            NotificationActionType.OPEN_SELF_APPROVALS,
            NotificationActionType.OPEN_CURFEW -> {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_NAVIGATE_TAB, TAB_SELF_CONTROL)
                }
                context.startActivity(intent)
            }

            NotificationActionType.OPEN_APPROVALS_HUB -> {
                val intent = Intent(context, com.drmohammed.antiporn.ui.accountability.GuardianApprovalsHubActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    if (!payload.isNullOrBlank()) {
                        if (payload.contains("|")) {
                            val parts = payload.split("|")
                            putExtra(com.drmohammed.antiporn.ui.accountability.GuardianApprovalsHubActivity.EXTRA_WARD_ID, parts[0])
                            if (parts.size > 1) {
                                putExtra(com.drmohammed.antiporn.ui.accountability.GuardianApprovalsHubActivity.EXTRA_TARGET_PACKAGE, parts[1])
                            }
                        } else {
                            putExtra(com.drmohammed.antiporn.ui.accountability.GuardianApprovalsHubActivity.EXTRA_WARD_ID, payload)
                        }
                    }
                }
                context.startActivity(intent)
            }

            NotificationActionType.OPEN_GUARDIAN_PANEL -> {
                if (payload != null && payload.contains("|")) {
                    route(context, NotificationActionType.OPEN_APPROVALS_HUB, payload)
                    return
                }
                val intent = Intent(context, GuardianControlActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    if (!payload.isNullOrBlank()) {
                        putExtra(GuardianControlActivity.EXTRA_WARD_ID, payload)
                        try {
                            val partner = com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
                                .getInstance(context).partnerDao().getPartnerByIdAndRoleBlocking(
                                    payload,
                                    com.drmohammed.antiporn.data.local.db.entity.PartnerEntity.ROLE_WARD
                                )
                            if (partner != null && partner.displayName.isNotBlank()) {
                                putExtra(GuardianControlActivity.EXTRA_WARD_NAME, partner.displayName)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed pre-fetching ward name for notification route", e)
                        }
                    }
                }
                context.startActivity(intent)
            }

            NotificationActionType.OPEN_OTA -> {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_NAVIGATE_TAB, TAB_SETTINGS)
                }
                context.startActivity(intent)
            }

            NotificationActionType.LAUNCH_APP -> {
                val packageName = payload
                if (!packageName.isNullOrBlank()) {
                    val isBlockActive = CurfewEnforcer.isEffectiveBlockActive(context)
                    val isWhitelisted = if (isBlockActive) {
                        val effectiveAllowed = EffectiveRestrictionResolver.resolve(context).effectiveAllowedUserApps +
                            CurfewAppResolver.resolveEssentialImmunePackages(context)
                        effectiveAllowed.contains(packageName)
                    } else {
                        true
                    }

                    if (isBlockActive && !isWhitelisted) {
                        Toast.makeText(
                            context,
                            R.string.notif_apps_suspended_title,
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                        } else {
                            Toast.makeText(
                                context,
                                R.string.toast_open_app_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }

            NotificationActionType.APPROVE_LOCAL_DNS_FALLBACK -> {
                val intent = Intent(context, LocalDnsFallbackConsentActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    if (!payload.isNullOrBlank()) {
                        putExtra(LocalDnsFallbackConsentActivity.EXTRA_NET_ID, payload)
                    }
                }
                context.startActivity(intent)
            }

            NotificationActionType.NONE -> {
                // Informational notification, no navigation required
            }
        }
    }
}
