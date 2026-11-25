package com.drmohammed.antiporn.core.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Universal self-healing engine for pre-installed OEM app stores and system components.
 *
 * Guarantees that pre-installed OEM App Stores (Samsung Galaxy Store, Xiaomi GetApps,
 * Huawei AppGallery, Oppo/Realme Market, etc.) remain enabled and operational on
 * managed devices, self-healing any past enterprise provisioning omissions or inadvertent
 * suspensions in the background without user intervention.
 */
object SystemAppsHealingManager {

    private const val TAG = "AntiPorn_SystemAppsHealing"
    private const val SIX_HOURS_MS = 6L * 60L * 60L * 1000L

    private val lastMarketIntentHealElapsedMs = AtomicLong(Long.MIN_VALUE)

    private fun safeElapsedRealtimeMs(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: RuntimeException) {
        System.currentTimeMillis()
    }

    private val healingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @VisibleForTesting
    internal val isHealingRunning = AtomicBoolean(false)
    private val healSyncLock = Any()

    private fun isAppAlreadyEnabled(pm: PackageManager, pkg: String): Boolean {
        return try {
            val setting = pm.getApplicationEnabledSetting(pkg)
            val settingEnabled = setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
                setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            val appInfo = pm.getApplicationInfo(pkg, 0)
            settingEnabled && appInfo.enabled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Explicit list of known pre-installed OEM App Store package names across major manufacturers.
     */
    val PREINSTALLED_OEM_STORES = setOf(
        "com.sec.android.app.samsungapps", // Samsung Galaxy Store
        "com.xiaomi.mipicks",              // Xiaomi GetApps
        "com.huawei.appmarket",            // Huawei AppGallery
        "com.oppo.market",                 // Oppo Market
        "com.heytap.market",               // Realme / Oppo App Market
        "com.vivo.appstore",               // Vivo App Store
        "com.hihonor.appmarket",           // Honor App Market
        "com.transsion.palmstore"          // Transsion Palm Store
    )

    /**
     * Triggers asynchronous self-healing in the background without blocking callers or UI thread.
     */
    fun healPreInstalledStoresAsync(context: Context, force: Boolean = false) {
        if (!isHealingRunning.compareAndSet(false, true)) {
            Timber.tag(TAG).d("Store self-healing already in progress; skipping duplicate trigger.")
            return
        }
        val appContext = context.applicationContext
        healingScope.launch {
            try {
                healPreInstalledStoresSafely(appContext, force = force)
            } finally {
                isHealingRunning.set(false)
            }
        }
    }

    @VisibleForTesting
    internal fun isPackagePresent(pm: PackageManager, packageName: String): Boolean {
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
            Timber.tag(TAG).w(e, "Unexpected error checking package presence for $packageName: ${e.message}")
            false
        }
    }

    /**
     * Executes safe, idempotent self-healing of pre-installed system stores:
     * 1. Dynamic intent-based re-enablement via CATEGORY_APP_MARKET
     * 2. Direct package-based re-enablement for all known OEM stores
     * 3. Clean daytime unsuspend if not within active curfew hours
     */
    fun healPreInstalledStoresSafely(context: Context, force: Boolean = false) = synchronized(healSyncLock) {
        try {
            if (!SafeDpmGateway.isDeviceOwner(context)) {
                Timber.tag(TAG).d("Device Owner not active; skipping store self-healing.")
                return
            }

            val pm = context.packageManager ?: return

            // 1. Dynamic intent-based enablement
            val nowElapsed = safeElapsedRealtimeMs()
            val prevElapsed = lastMarketIntentHealElapsedMs.get()
            val shouldHealMarketIntent = force || prevElapsed == Long.MIN_VALUE || (nowElapsed - prevElapsed > SIX_HOURS_MS)
            if (shouldHealMarketIntent) {
                try {
                    val marketIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MARKET)
                    SafeDpmGateway.enableSystemAppBlocking(context, marketIntent)
                    lastMarketIntentHealElapsedMs.set(nowElapsed)
                    val resolved = pm.queryIntentActivities(
                        marketIntent,
                        PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_UNINSTALLED_PACKAGES
                    )
                    for (info in resolved.orEmpty()) {
                        val pkg = info.activityInfo?.packageName
                        if (!pkg.isNullOrBlank() && pkg != context.packageName) {
                            if (!isAppAlreadyEnabled(pm, pkg)) {
                                SafeDpmGateway.enableSystemAppBlocking(context, pkg)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "Dynamic market intent resolution skipped: ${t.message}")
                }
            }

            // 2. Direct package enablement for known OEM stores
            for (pkg in PREINSTALLED_OEM_STORES) {
                if (!isPackagePresent(pm, pkg)) continue
                if (isAppAlreadyEnabled(pm, pkg)) continue
                try {
                    SafeDpmGateway.enableSystemAppBlocking(context, pkg)
                } catch (t: Throwable) {
                    Timber.tag(TAG).d(t, "Skipping OEM store enablement for $pkg")
                }
            }

            // 3. Clear any lingering suspension if curfew is NOT currently active
            val isCurfewCurrentlyBlocking = try {
                CurfewEnforcer.isEffectiveBlockActive(context)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed evaluating curfew block state; preserving store suspension state")
                true
            }

            if (!isCurfewCurrentlyBlocking) {
                for (pkg in PREINSTALLED_OEM_STORES) {
                    if (!isPackagePresent(pm, pkg)) continue
                    try {
                        val isSuspended = SafeDpmGateway.isPackageSuspendedBlocking(context, pkg).getOrNull() == true
                        if (isSuspended) {
                            SafeDpmGateway.setPackagesSuspendedBlocking(context, arrayOf(pkg), false)
                            Timber.tag(TAG).i("Self-healing: Cleared legacy/stuck suspension for $pkg")
                        }
                    } catch (t: Throwable) {
                        Timber.tag(TAG).w(t, "Failed to check/clear suspension for $pkg: ${t.message}")
                    }
                }
            }

            Timber.tag(TAG).i("Pre-installed OEM stores self-healing completed successfully.")
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Unexpected error during stores self-healing")
        }
    }
}
