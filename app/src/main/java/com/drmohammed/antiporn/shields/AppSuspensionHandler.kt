package com.drmohammed.antiporn.shields

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Handles DevicePolicyManager suspension and blacklist scanning for [AppSuspensionShield].
 */
internal object AppSuspensionHandler {

    private const val TAG = "AntiPorn_AppSuspension"
    private const val IN_FLIGHT_WAIT_TIMEOUT_SECONDS = 15L
    private val shieldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightPackages = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

    @androidx.annotation.VisibleForTesting
    internal var isPackageInstalledProvider: (PackageManager?, String) -> Boolean = { pm, pkg ->
        pm != null && isPackageInstalled(pm, pkg)
    }

    fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error checking install state for $packageName", e)
            false
        }
    }

    /**
     * Suspends a blacklisted package via [dpm] while clearing any legacy uninstall lock.
     * Returns true if the package is verified suspended or skipped due to immunity/uninstalled state,
     * false if DPM suspension fails or is rejected.
     */
    fun freezePackage(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        packageName: String,
        shieldId: String
    ): Boolean {
        if (packageName == context.packageName || AppSuspensionShield.isImmune(packageName)) {
            Log.w(TAG, "Immunity guard: Cannot freeze immune or self package ($packageName)")
            return true
        }
        val pm = context.packageManager
        if (pm == null || !isPackageInstalled(pm, packageName)) {
            Log.d(TAG, "Package $packageName is not installed; skipping freeze")
            return true
        }
        val newFuture = CompletableFuture<Boolean>()
        val existingFuture = inFlightPackages.putIfAbsent(packageName, newFuture)
        if (existingFuture != null) {
            Log.d(TAG, "Package $packageName is already being processed; awaiting in-flight suspension result")
            return try {
                existingFuture.get(IN_FLIGHT_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Interrupted awaiting in-flight freeze for $packageName", e)
                false
            } catch (e: Exception) {
                Log.w(TAG, "Failed awaiting in-flight freeze for $packageName", e)
                false
            }
        }

        var result = false
        return try {
            try {
                if (dpm.isUninstallBlocked(admin, packageName)) {
                    dpm.setUninstallBlocked(admin, packageName, false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not unblock uninstall for $packageName", e)
            }

            result = try {
                val isSuspended = dpm.isPackageSuspended(admin, packageName)
                if (!isSuspended) {
                    val failedPackages = dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
                    if (failedPackages?.contains(packageName) == true) {
                        Log.w(TAG, "Failed to suspend package: $packageName")
                        BlackBoxRecorder.recordShield(
                            context = context,
                            shieldId = shieldId,
                            isSuccess = false,
                            details = "pkg=$packageName",
                            error = "setPackagesSuspended rejected $packageName"
                        )
                        false
                    } else {
                        Log.i(TAG, "Suspended package: $packageName")
                        BlackBoxRecorder.recordShield(
                            context = context,
                            shieldId = shieldId,
                            isSuccess = true,
                            details = "Suspended pkg=$packageName"
                        )
                        true
                    }
                } else {
                    Log.d(TAG, "Package $packageName is already suspended; skipping setPackagesSuspended")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to suspend package: $packageName", e)
                BlackBoxRecorder.recordShield(
                    context = context,
                    shieldId = shieldId,
                    isSuccess = false,
                    details = "pkg=$packageName",
                    error = "${e.javaClass.simpleName}: ${e.message}"
                )
                false
            }
            result
        } finally {
            inFlightPackages.remove(packageName, newFuture)
            newFuture.complete(result)
        }
    }

    fun resolveTargetsToEnforce(context: Context): List<String> {
        val appContext = context.applicationContext ?: context
        val pm = appContext.packageManager
        val candidates = linkedSetOf<String>()
        val approvals = try {
            ApprovalRepository.getInstance(appContext).getLocalPendingApprovals()
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading pending approvals in AppSuspensionHandler", e)
            emptyList()
        }

        val policy = try {
            PolicyRepository.getInstance(appContext).getCurrentPolicy()
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading policy in AppSuspensionHandler", e)
            null
        }
        candidates.addAll(policy?.customBlacklistPackages ?: emptySet())

        for (approval in approvals) {
            if (approval.isRejected || (policy?.lockNewInstallsUntilApproved == true && approval.isPending)) {
                candidates.add(approval.packageName)
            }
        }

        return candidates.filter { pkg ->
            pkg != appContext.packageName &&
                !AppSuspensionShield.isImmune(pkg) &&
                isPackageInstalledProvider(pm, pkg)
        }
    }

    fun handlePackageAdded(context: Context, packageName: String, shieldId: String) {
        val appContext = context.applicationContext ?: context
        val policy = PolicyRepository.getInstance(appContext).getCurrentPolicy()
        val isCustomBlacklisted = policy.customBlacklistPackages.contains(packageName)
        if (!policy.lockNewInstallsUntilApproved && !isCustomBlacklisted) {
            return
        }
        if (AppSuspensionShield.isImmune(packageName)) {
            return
        }
        if (!isCustomBlacklisted) {
            val existingApproval = try {
                ApprovalRepository.getInstance(appContext)
                    .getLocalPendingApprovals()
                    .firstOrNull { it.packageName == packageName }
            } catch (e: Exception) {
                Log.w(TAG, "Failed checking approval state in handlePackageAdded for $packageName", e)
                null
            }
            if (existingApproval?.isApproved == true) {
                return
            }
        }
        if (!SafeDpmGateway.isDeviceOwner(appContext)) {
            Log.w(TAG, "Cannot freeze $packageName: App is not Device/Profile Owner")
            return
        }
        Log.i(TAG, "Freezing blacklisted or unapproved app on install: $packageName")
        SafeDpmGateway.executeRawBlocking(
            context = appContext,
            tag = BlackBoxRecorder.TAG_SHIELD,
            actionName = "handleBlacklistedPackageAdded",
            metadata = "pkg=$packageName"
        ) { dpm, admin ->
            freezePackage(appContext, dpm, admin, packageName, shieldId)
        }
    }

    fun enforceBlacklistFast(context: Context, shieldId: String) {
        val appContext = context.applicationContext ?: context
        if (!SafeDpmGateway.isDeviceOwner(appContext)) {
            Log.w(TAG, "enforceBlacklistFast skipped: not Device/Profile Owner")
            return
        }

        val targets = resolveTargetsToEnforce(appContext)
        if (targets.isEmpty()) return
        SafeDpmGateway.executeRawBlocking(
            context = appContext,
            tag = BlackBoxRecorder.TAG_SHIELD,
            actionName = "enforceBlacklistFast",
            recordSuccess = false
        ) { dpm, admin ->
            for (pkg in targets) {
                freezePackage(appContext, dpm, admin, pkg, shieldId)
            }
        }
    }

    fun enforceBlacklistFastAsync(context: Context, shieldId: String) {
        val appContext = context.applicationContext ?: context
        shieldScope.launch {
            try {
                enforceBlacklistFast(appContext, shieldId)
            } catch (e: Exception) {
                Log.e(TAG, "Error in enforceBlacklistFastAsync", e)
            }
        }
    }
}
