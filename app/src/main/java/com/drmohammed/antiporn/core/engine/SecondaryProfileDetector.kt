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
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.DpmExecutionResult
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

    data class SecondaryCountResult(
        val count: Int,
        val reliable: Boolean
    )

    data class SingleProfileRemovalResult(
        val removedTarget: Boolean,
        val allSecondariesCleared: Boolean
    )

    @VisibleForTesting
    internal fun isRemovableSecondaryUserId(userId: Int, primaryUserId: Int = 0): Boolean {
        return userId > 0 && userId != primaryUserId
    }

    @StringRes
    fun getProfileTypeLabelRes(profileType: ProfileType): Int {
        return when (profileType) {
            ProfileType.CLONE_APP -> R.string.profile_type_clone_app
            ProfileType.WORK_PROFILE -> R.string.profile_type_work_profile
            ProfileType.PRIVATE_SPACE -> R.string.profile_type_private_space
            ProfileType.SECONDARY_USER -> R.string.profile_type_secondary_user
        }
    }

    fun formatProfileDisplayEntry(context: Context, info: UnaffiliatedProfileInfo): String {
        val typeLabel = context.getString(getProfileTypeLabelRes(info.profileType))
        val appsLabel = if (info.clonedAppNames.isNotEmpty()) {
            info.clonedAppNames.joinToString(", ")
        } else {
            context.getString(R.string.profile_empty_container_hint)
        }
        return context.getString(R.string.profile_entry_format, typeLabel, info.userId, appsLabel)
    }

    fun extractUserId(userHandle: UserHandle): Int {
        val parsed = Regex("""\d+""").find(userHandle.toString())?.value?.toIntOrNull()
        return parsed ?: userHandle.hashCode()
    }

    fun isPrimaryUser(userHandle: UserHandle): Boolean {
        val myUser = try {
            Process.myUserHandle()
        } catch (_: Throwable) {
            null
        }
        return extractUserId(userHandle) == 0 || (myUser != null && userHandle == myUser)
    }

    @VisibleForTesting
    internal fun aggregateSecondaryUserCount(
        userProfileIds: Iterable<Int>,
        userProfilesOk: Boolean,
        dpmSecondaryUserIds: Iterable<Int>,
        secondaryUsersOk: Boolean,
        primaryUserId: Int = 0
    ): SecondaryCountResult {
        val uniqueNonPrimaryIds = linkedSetOf<Int>()
        for (userId in userProfileIds) {
            if (userId == 0 || userId == primaryUserId) continue
            uniqueNonPrimaryIds.add(userId)
        }
        for (userId in dpmSecondaryUserIds) {
            if (userId == 0 || userId == primaryUserId) continue
            uniqueNonPrimaryIds.add(userId)
        }
        return SecondaryCountResult(
            count = uniqueNonPrimaryIds.size,
            reliable = userProfilesOk && secondaryUsersOk
        )
    }

    @VisibleForTesting
    internal fun mergeSecondaryUserSources(
        userProfileIds: Iterable<Int>,
        dpmSecondaryUserIds: Iterable<Int>,
        primaryUserId: Int = 0
    ): LinkedHashMap<Int, Boolean> {
        val discovered = linkedMapOf<Int, Boolean>()
        for (userId in userProfileIds) {
            if (userId == 0 || userId == primaryUserId) continue
            discovered.putIfAbsent(userId, false)
        }
        for (userId in dpmSecondaryUserIds) {
            if (userId == 0 || userId == primaryUserId) continue
            if (!discovered.containsKey(userId)) {
                discovered[userId] = true
            }
        }
        return discovered
    }

    @VisibleForTesting
    internal fun classifySecondaryProfileType(
        userId: Int,
        isFromDpmOnly: Boolean,
        isManagedProfile: Boolean = false,
        isPrivateSpace: Boolean = false
    ): ProfileType {
        if (isFromDpmOnly) {
            return ProfileType.SECONDARY_USER
        }
        val isClone = userId == 999 || userId == 95 || userId == 96
        return when {
            isClone -> ProfileType.CLONE_APP
            isManagedProfile -> ProfileType.WORK_PROFILE
            userId >= 10 && isPrivateSpace -> ProfileType.PRIVATE_SPACE
            else -> ProfileType.SECONDARY_USER
        }
    }

    fun countSecondaryUsersAndProfiles(context: Context): SecondaryCountResult {
        val myUser = try {
            Process.myUserHandle()
        } catch (_: Throwable) {
            null
        }
        val primaryUserId = myUser?.let { extractUserId(it) } ?: 0

        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        var userProfilesOk = false
        val profileIds = mutableListOf<Int>()
        if (userManager != null) {
            try {
                val profiles = userManager.userProfiles ?: emptyList()
                userProfilesOk = true
                for (handle in profiles) {
                    if (myUser != null && handle == myUser) continue
                    profileIds.add(extractUserId(handle))
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed querying userProfiles in countSecondaryUsersAndProfiles")
                userProfilesOk = false
            }
        }

        var secondaryUsersOk = true
        val dpmSecondaryIds = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && SafeDpmGateway.isDeviceOwner(context)) {
            val dpmRes = SafeDpmGateway.executeRawBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD,
                actionName = "getSecondaryUsers",
                recordSuccess = false
            ) { dpm, admin ->
                dpm.getSecondaryUsers(admin) ?: emptyList()
            }
            when (dpmRes) {
                is DpmExecutionResult.Success -> {
                    for (handle in dpmRes.data) {
                        if (myUser != null && handle == myUser) continue
                        dpmSecondaryIds.add(extractUserId(handle))
                    }
                }
                is DpmExecutionResult.Failure -> {
                    Timber.tag(TAG).w("Failed querying getSecondaryUsers via DPM: ${dpmRes.message}")
                    secondaryUsersOk = false
                }
            }
        }

        return aggregateSecondaryUserCount(
            userProfileIds = profileIds,
            userProfilesOk = userProfilesOk,
            dpmSecondaryUserIds = dpmSecondaryIds,
            secondaryUsersOk = secondaryUsersOk,
            primaryUserId = primaryUserId
        )
    }

    fun getSecondaryProfiles(context: Context): List<UnaffiliatedProfileInfo> {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val myUser = try {
            Process.myUserHandle()
        } catch (_: Throwable) {
            null
        }

        // Map userId -> (UserHandle, fromDpmSecondaryUsersOnly)
        val discovered = linkedMapOf<Int, Pair<UserHandle, Boolean>>()

        if (userManager != null) {
            val profiles = try {
                userManager.userProfiles ?: emptyList()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed querying userProfiles")
                emptyList()
            }
            for (handle in profiles) {
                val userId = extractUserId(handle)
                if ((myUser != null && handle == myUser) || userId == 0) continue
                discovered.putIfAbsent(userId, handle to false)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && SafeDpmGateway.isDeviceOwner(context)) {
            val dpmRes = SafeDpmGateway.executeRawBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD,
                actionName = "getSecondaryUsers",
                recordSuccess = false
            ) { dpm, admin ->
                dpm.getSecondaryUsers(admin) ?: emptyList()
            }
            if (dpmRes is DpmExecutionResult.Success) {
                for (handle in dpmRes.data) {
                    val userId = extractUserId(handle)
                    if ((myUser != null && handle == myUser) || userId == 0) continue
                    if (!discovered.containsKey(userId)) {
                        discovered[userId] = handle to true
                    }
                }
            } else if (dpmRes is DpmExecutionResult.Failure) {
                Timber.tag(TAG).w("Failed querying getSecondaryUsers via DPM in getSecondaryProfiles: ${dpmRes.message}")
            }
        }

        val secondaryList = mutableListOf<UnaffiliatedProfileInfo>()

        for ((userId, entry) in discovered) {
            val (handle, isFromDpmOnly) = entry

            val isManaged = if (!isFromDpmOnly) userManager?.let { isManagedProfileSafe(it, userId) } ?: false else false
            val isPrivateSpace = if (!isFromDpmOnly && userId >= 10 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                userManager?.let { isPrivateSpaceSafe(it, handle) } == true
            } else {
                false
            }
            val type = classifySecondaryProfileType(
                userId = userId,
                isFromDpmOnly = isFromDpmOnly,
                isManagedProfile = isManaged,
                isPrivateSpace = isPrivateSpace
            )

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

        val candidates = listOfNotNull(
            // OPPO / Realme / OnePlus (ColorOS 14/15 / Realme UI / OxygenOS App Cloner & System Cloner)
            Intent().setComponent(ComponentName("com.oplus.multiapp", "com.oplus.multiapp.slice.MultiAppSettingActivity")),
            runCatching { pm.getLaunchIntentForPackage("com.oplus.multiapp") }.getOrNull(),
            Intent().setComponent(ComponentName("com.coloros.multiapp", "com.coloros.multiapp.MainActivity")),
            runCatching { pm.getLaunchIntentForPackage("com.coloros.systemclone") }.getOrNull(),

            // Legacy OPPO / Realme / OnePlus (ColorOS / Realme UI / OxygenOS)
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
            if (candidate.resolveActivityInfo(pm, 0)?.exported == true) {
                return candidate
            }
        }

        return Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun removeSecondaryProfile(context: Context, userHandle: UserHandle): Boolean {
        val targetUserId = extractUserId(userHandle)
        val primaryUserId = try {
            extractUserId(Process.myUserHandle())
        } catch (_: Throwable) {
            0
        }
        if (!isRemovableSecondaryUserId(targetUserId, primaryUserId) || isPrimaryUser(userHandle)) {
            Timber.tag(TAG).e("Refusing removeSecondaryProfile for primary or invalid userId=$targetUserId")
            return false
        }
        if (!SafeDpmGateway.isDeviceOwner(context)) {
            Timber.tag(TAG).w("Cannot remove user: not Device Owner")
            return false
        }
        val result = SafeDpmGateway.executeRawBlocking(
            context = context,
            tag = BlackBoxRecorder.TAG_SHIELD,
            actionName = "removeSecondaryProfile",
            metadata = "user=$targetUserId"
        ) { dpm, admin ->
            dpm.removeUser(admin, userHandle)
        }
        val success = result.getOrNull() == true
        if (success) {
            Timber.tag(TAG).i("Successfully removed secondary profile $userHandle via DPM")
        } else {
            val failureMsg = (result as? DpmExecutionResult.Failure)?.message ?: "DPM failure"
            Timber.tag(TAG).w("Failed removing secondary profile $userHandle via DPM: $failureMsg")
        }
        return success
    }

    suspend fun removeSecondaryProfileAndAwait(
        context: Context,
        profile: UnaffiliatedProfileInfo,
        timeoutMs: Long = 3_000L,
        pollIntervalMs: Long = 150L
    ): SingleProfileRemovalResult {
        val requested = removeSecondaryProfile(context, profile.userHandle)
        if (!requested) {
            return SingleProfileRemovalResult(removedTarget = false, allSecondariesCleared = false)
        }
        val deadlineMs = (System.nanoTime() / 1_000_000L) + timeoutMs
        var removedTarget = false
        while ((System.nanoTime() / 1_000_000L) < deadlineMs) {
            val remaining = getSecondaryProfiles(context)
            if (remaining.none { it.userId == profile.userId }) {
                removedTarget = true
                break
            }
            kotlinx.coroutines.delay(pollIntervalMs)
        }
        if (!removedTarget) {
            removedTarget = getSecondaryProfiles(context).none { it.userId == profile.userId }
        }
        val finalCount = countSecondaryUsersAndProfiles(context)
        val allCleared = finalCount.reliable && finalCount.count == 0
        return SingleProfileRemovalResult(
            removedTarget = removedTarget,
            allSecondariesCleared = allCleared
        )
    }
}
