package com.drmohammed.antiporn.core.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import timber.log.Timber

/**
 * Enterprise-grade detector and resolver for secondary Android profiles
 * (App Cloner / Dual Apps / Dual Messenger / Work Profiles / Private Spaces).
 *
 * Provides:
 * 1. Deep discovery of secondary profiles and their exact User IDs.
 * 2. Surgical extraction of installed cloned apps (e.g. "WhatsApp", "Telegram") via [LauncherApps].
 * 3. OEM-aware settings intent resolution (OPPO, Xiaomi, Samsung, Vivo, Stock Android).
 * 4. Device Owner programmatic user removal via [android.app.admin.DevicePolicyManager.removeUser].
 */
object SecondaryProfileDetector {

    private const val TAG = "AntiPorn_ProfileDetect"

    enum class ProfileType {
        CLONE_APP,
        WORK_PROFILE,
        PRIVATE_SPACE,
        SECONDARY_USER
    }

    data class UnaffiliatedProfileInfo(
        val userHandle: UserHandle,
        val userId: Int,
        val profileType: ProfileType,
        val clonedAppNames: List<String>,
        val clonedPackageNames: List<String>
    )

    fun extractUserId(userHandle: UserHandle): Int {
        val parsed = Regex("""\d+""").find(userHandle.toString())?.value?.toIntOrNull()
        return parsed ?: userHandle.hashCode()
    }

    fun isPrimaryUser(userHandle: UserHandle): Boolean {
        return extractUserId(userHandle) == 0 || userHandle == Process.myUserHandle()
    }

    fun getSecondaryProfiles(context: Context): List<UnaffiliatedProfileInfo> {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return emptyList()
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val profiles = try {
            userManager.userProfiles ?: emptyList()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed querying userProfiles")
            return emptyList()
        }

        val myUser = Process.myUserHandle()
        val secondaryList = mutableListOf<UnaffiliatedProfileInfo>()

        for (handle in profiles) {
            val userId = extractUserId(handle)
            if (handle == myUser || userId == 0) continue

            val isClone = userId == 999 || userId == 95 || userId == 96
            val isManaged = isManagedProfileSafe(userManager, userId)

            val type = when {
                isClone -> ProfileType.CLONE_APP
                isManaged -> ProfileType.WORK_PROFILE
                userId >= 10 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && isPrivateSpaceSafe(userManager, handle) -> ProfileType.PRIVATE_SPACE
                else -> ProfileType.SECONDARY_USER
            }

            val appNames = mutableListOf<String>()
            val pkgNames = mutableListOf<String>()

            if (launcherApps != null) {
                try {
                    val activityList = launcherApps.getActivityList(null, handle)
                    for (activity in activityList) {
                        val label = activity.label?.toString()?.trim()
                        val pkg = activity.applicationInfo.packageName
                        if (!label.isNullOrBlank() && !appNames.contains(label)) {
                            appNames.add(label)
                        }
                        if (!pkgNames.contains(pkg)) {
                            pkgNames.add(pkg)
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed listing activities for user $userId via LauncherApps")
                }
            }

            secondaryList.add(
                UnaffiliatedProfileInfo(
                    userHandle = handle,
                    userId = userId,
                    profileType = type,
                    clonedAppNames = appNames,
                    clonedPackageNames = pkgNames
                )
            )
        }

        return secondaryList
    }

    private fun isManagedProfileSafe(userManager: UserManager, userId: Int): Boolean {
        return try {
            val method = userManager.javaClass.getMethod("isManagedProfile", Int::class.javaPrimitiveType)
            method.invoke(userManager, userId) as? Boolean ?: false
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "isManagedProfile reflection check failed for user $userId")
            false
        }
    }

    private fun isPrivateSpaceSafe(userManager: UserManager, handle: UserHandle): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                val method = userManager.javaClass.getMethod("getUserTypeForUser", UserHandle::class.java)
                val userType = method.invoke(userManager, handle) as? String
                userType == "android.os.usertype.profile.PRIVATE"
            } else {
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "isPrivateSpace reflection check failed for user $handle")
            false
        }
    }

    fun formatClonedAppsSummary(context: Context): String? {
        val secondaries = getSecondaryProfiles(context)
        if (secondaries.isEmpty()) return null

        val allApps = secondaries.flatMap { it.clonedAppNames }.distinct()
        return if (allApps.isNotEmpty()) {
            allApps.joinToString(", ")
        } else {
            null
        }
    }

    fun resolveCloneSettingsIntent(context: Context): Intent {
        val pm = context.packageManager

        val candidates = listOf(
            // OPPO / Realme / OnePlus (ColorOS / Realme UI / OxygenOS)
            Intent().setComponent(ComponentName("com.coloros.clone", "com.coloros.clone.CloneSettingsActivity")),
            Intent().setComponent(ComponentName("com.oplus.clone", "com.oplus.clone.CloneSettingsActivity")),
            Intent("com.coloros.clone.action.CLONE_SETTINGS"),
            Intent("com.oplus.clone.action.CLONE_SETTINGS"),

            // Xiaomi / Redmi / POCO (MIUI / HyperOS)
            Intent().setComponent(ComponentName("com.miui.securityadd", "com.miui.securityadd.dualapp.DualAppSettingsActivity")),
            Intent("com.miui.securityadd.action.DUAL_APP"),
            Intent("com.miui.securitycenter.action.DUAL_APP_SETTINGS"),

            // Samsung (One UI)
            Intent().setComponent(ComponentName("com.samsung.android.da.daagent", "com.samsung.android.da.daagent.MainActivity")),
            Intent().setComponent(ComponentName("com.samsung.knox.securefolder", "com.samsung.knox.securefolder.presentation.switcher.SwitchActivity")),

            // Vivo / iQOO (Funtouch OS / OriginOS)
            Intent().setComponent(ComponentName("com.vivo.doubleinstance", "com.vivo.doubleinstance.activity.DoubleInstanceActivity")),

            // Generic Stock Android / Multi-User
            Intent("android.settings.USER_SETTINGS"),
            Intent(Settings.ACTION_SYNC_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (candidate in candidates) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (candidate.resolveActivity(pm) != null) {
                return candidate
            }
        }

        return Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun removeSecondaryProfile(context: Context, userHandle: UserHandle): Boolean {
        if (!SafeDpmGateway.isDeviceOwner(context)) {
            Timber.tag(TAG).w("Cannot remove user: not Device Owner")
            return false
        }
        val result = SafeDpmGateway.executeRawBlocking(
            context = context,
            tag = BlackBoxRecorder.TAG_SHIELD,
            actionName = "removeSecondaryProfile",
            metadata = "user=${extractUserId(userHandle)}"
        ) { dpm, admin ->
            dpm.removeUser(admin, userHandle)
        }
        val success = result.getOrNull() == true
        if (success) {
            Timber.tag(TAG).i("Successfully removed secondary profile $userHandle via DPM")
        } else {
            val failureMsg = (result as? com.drmohammed.antiporn.telemetry.DpmExecutionResult.Failure)?.message ?: "DPM failure"
            Timber.tag(TAG).w("Failed removing secondary profile $userHandle via DPM: $failureMsg")
        }
        return success
    }
}
