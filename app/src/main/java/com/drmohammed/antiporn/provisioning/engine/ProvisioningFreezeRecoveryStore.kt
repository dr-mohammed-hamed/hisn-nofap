package com.drmohammed.antiporn.provisioning.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Human-readable metadata for a package that remains disabled after a freeze-based promotion attempt.
 */
data class RecoveryPackageInfo(
    val packageName: String,
    val appLabel: String
)

/**
 * Persistent ledger of packages temporarily frozen during Device Owner promotion.
 */
data class FreezeRecoveryLedger(
    val userId: Int,
    val packages: List<String>,
    val recordedAtMs: Long
)

/**
 * Synchronous SharedPreferences ledger tracking packages frozen during legacy ADB promotion.
 *
 * Uses [SharedPreferences.Editor.commit] so the ledger survives abrupt process termination,
 * ADB disconnection, or reboot, enabling deterministic healing on the next attempt or wizard launch.
 */
object ProvisioningFreezeRecoveryStore {

    private const val TAG = "AntiPorn_FreezeStore"
    const val PREFS_NAME = "provisioning_freeze_recovery_prefs"
    private const val KEY_LEDGER_JSON = "freeze_recovery_ledger_json"
    private val PACKAGE_NAME_REGEX = AdbShellValidation.PACKAGE_NAME_REGEX

    @VisibleForTesting
    internal var disabledStateCheckerOverride: ((Context, String) -> Boolean)? = null

    @VisibleForTesting
    internal var appLabelResolverOverride: ((Context, String) -> String)? = null

    @VisibleForTesting
    internal var processUserIdOverride: (() -> Int?)? = null

    private fun getPrefs(context: Context): SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @VisibleForTesting
    @Synchronized
    internal fun resetForTesting() {
        disabledStateCheckerOverride = null
        appLabelResolverOverride = null
        processUserIdOverride = null
    }

    /**
     * Synchronously persists the list of valid packages about to be frozen for [userId].
     * Clears the ledger if no valid package names remain after regex sanitization.
     */
    @Synchronized
    fun recordPendingFreeze(context: Context, userId: Int, packages: Collection<String>) {
        val validPackages = packages.distinct().filter { PACKAGE_NAME_REGEX.matches(it) }
        if (userId < 0 || validPackages.isEmpty()) {
            clearLedger(context)
            return
        }
        val recordedAt = runCatching { TrustedTimeProvider.currentTimeMillis(context) }
            .getOrElse { System.currentTimeMillis() }
        persistLedger(context, userId, validPackages, recordedAt)
    }

    /**
     * Merges newly frozen packages with any existing pending entries for the same [userId]
     * so previously stranded packages are never dropped across retries.
     */
    @Synchronized
    fun mergePendingFreeze(context: Context, userId: Int, newPackages: Collection<String>) {
        val existing = getPendingLedger(context)
            ?.takeIf { it.userId == userId }
            ?.packages
            .orEmpty()
        recordPendingFreeze(context, userId, existing + newPackages)
    }

    /**
     * Reads and parses the current freeze recovery ledger, or null if none is stored or valid.
     */
    @Synchronized
    fun getPendingLedger(context: Context): FreezeRecoveryLedger? {
        return try {
            val raw = getPrefs(context).getString(KEY_LEDGER_JSON, null)
            if (raw.isNullOrBlank()) return null
            val obj = JSONObject(raw)
            val userId = obj.optInt("userId", -1)
            val recordedAtMs = obj.optLong("recordedAtMs", 0L)
            val arr = obj.optJSONArray("packages") ?: return null
            val packages = buildList {
                for (i in 0 until arr.length()) {
                    if (arr.isNull(i)) continue
                    val pkg = arr.optString(i, "").trim()
                    if (PACKAGE_NAME_REGEX.matches(pkg)) add(pkg)
                }
            }.distinct()
            if (userId < 0 || packages.isEmpty()) null else FreezeRecoveryLedger(userId, packages, recordedAtMs)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed reading freeze recovery ledger")
            null
        }
    }

    /**
     * Synchronously clears any pending freeze recovery ledger.
     */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun clearLedger(context: Context) {
        try {
            val committed = getPrefs(context).edit().remove(KEY_LEDGER_JSON).commit()
            if (!committed) {
                Timber.tag(TAG).e("SharedPreferences.commit() returned false clearing freeze recovery ledger")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed clearing freeze recovery ledger")
        }
    }

    /**
     * Inspects all tracked packages in the ledger against [PackageManager], automatically prunes
     * packages that are now enabled or uninstalled, and returns metadata for any still-disabled ones.
     */
    @Synchronized
    fun findStillDisabledTrackedPackages(context: Context): List<RecoveryPackageInfo> {
        val appContext = context.applicationContext ?: context
        val ledger = getPendingLedger(appContext) ?: return emptyList()

        val processUserId = processUserIdOverride?.invoke() ?: runCatching {
            SecondaryProfileDetector.extractUserId(Process.myUserHandle())
        }.getOrNull()
        val isCrossUserOrUnavailablePm = disabledStateCheckerOverride == null && (
            (processUserId != null && ledger.userId != processUserId) ||
                (processUserId == null && ledger.userId != 0) ||
                appContext.packageManager == null
            )
        if (isCrossUserOrUnavailablePm) {
            // Local PackageManager only sees the calling user; preserve cross-user ledger for ADB healing.
            return ledger.packages.map { pkg ->
                val label = appLabelResolverOverride?.invoke(appContext, pkg) ?: pkg
                RecoveryPackageInfo(packageName = pkg, appLabel = label)
            }
        }

        val stillDisabled = ledger.packages.filter { pkg -> isPackageStillDisabled(appContext, pkg) }
        if (stillDisabled.isEmpty()) {
            clearLedger(appContext)
            return emptyList()
        }
        if (stillDisabled.size < ledger.packages.size) {
            persistLedger(appContext, ledger.userId, stillDisabled, ledger.recordedAtMs)
        }
        return stillDisabled.map { pkg ->
            val label = appLabelResolverOverride?.invoke(appContext, pkg) ?: resolveAppLabel(appContext, pkg)
            RecoveryPackageInfo(packageName = pkg, appLabel = label)
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun persistLedger(context: Context, userId: Int, packages: List<String>, recordedAtMs: Long) {
        try {
            val payload = JSONObject().apply {
                put("userId", userId)
                put("recordedAtMs", recordedAtMs)
                put("packages", JSONArray(packages))
            }.toString()
            val committed = getPrefs(context).edit().putString(KEY_LEDGER_JSON, payload).commit()
            if (!committed) {
                Timber.tag(TAG).e("SharedPreferences.commit() returned false persisting freeze recovery ledger")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed persisting freeze recovery ledger")
        }
    }

    private fun isPackageStillDisabled(context: Context, packageName: String): Boolean {
        disabledStateCheckerOverride?.let { return it.invoke(context, packageName) }
        val pm = context.packageManager ?: return false
        return try {
            val appInfo = getApplicationInfoCompat(pm, packageName) ?: return false
            val enabledSetting = pm.getApplicationEnabledSetting(packageName)
            val isDisabledSetting =
                enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                    enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            isDisabledSetting || !appInfo.enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed inspecting disabled state for $packageName")
            false
        }
    }

    private fun resolveAppLabel(context: Context, packageName: String): String {
        val pm = context.packageManager ?: return packageName
        return try {
            val appInfo = getApplicationInfoCompat(pm, packageName) ?: return packageName
            val rawLabel: CharSequence? = pm.getApplicationLabel(appInfo)
            rawLabel?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: packageName
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Could not resolve label for $packageName")
            packageName
        }
    }

    private fun getApplicationInfoCompat(pm: PackageManager, packageName: String): ApplicationInfo? {
        @Suppress("DEPRECATION")
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, flags)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
