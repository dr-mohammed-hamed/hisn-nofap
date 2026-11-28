package com.drmohammed.antiporn.curfew

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import timber.log.Timber

/**
 * One-time idempotent migrator that materializes legacy static curfew-exempt installed apps
 * into [DevicePolicyBundle.customCurfewWhitelistPackages] for existing installations while
 * preserving 100% of existing whitelist and VPN bypass entries.
 */
object LegacyCurfewWhitelistMigrator {

    private const val TAG = "AntiPorn_CurfewMigration"
    internal const val PREFS_NAME = "antiporn_policy_migrations"
    internal const val KEY_LEGACY_CURFEW_EXEMPT_MIGRATED = "key_legacy_curfew_exempt_migrated"
    internal const val KEY_PRE_MIGRATION_POLICY_BACKUP = "key_pre_migration_policy_backup"
    internal const val KEY_MIGRATED_EXEMPT_PACKAGES = "key_migrated_exempt_packages"
    internal const val KEY_MIGRATED_AT_POLICY_VERSION = "key_migrated_at_policy_version"

    data class MigrationResult(
        val policy: DevicePolicyBundle,
        val migrated: Boolean,
        val addedPackages: Set<String>
    )

    @VisibleForTesting
    internal fun computeMigratedPolicy(
        policy: DevicePolicyBundle,
        installedPackages: Set<String>,
        selfPackageName: String,
        alreadyMigrated: Boolean,
        hasPersistedPolicy: Boolean
    ): MigrationResult {
        if (alreadyMigrated || !hasPersistedPolicy) {
            return MigrationResult(policy, migrated = false, addedPackages = emptySet())
        }

        val exemptInstalled = installedPackages.filterTo(linkedSetOf()) { pkg ->
            pkg.isNotBlank() &&
                pkg != selfPackageName &&
                !AppPolicyRegistry.isImmune(pkg) &&
                AppPolicyRegistry.isExemptFromCurfew(pkg) &&
                !policy.customBlacklistPackages.contains(pkg) &&
                !AppPolicyRegistry.isRestrictedBrowser(pkg) &&
                !CurfewConfig.KNOWN_APP_STORES.contains(pkg) &&
                !CurfewConfig.CURFEW_SEARCH_APPS.contains(pkg)
        }

        val added = exemptInstalled - policy.customCurfewWhitelistPackages
        return if (added.isNotEmpty()) {
            MigrationResult(
                policy = policy.copy(
                    customCurfewWhitelistPackages = policy.customCurfewWhitelistPackages + added
                ),
                migrated = true,
                addedPackages = added
            )
        } else {
            MigrationResult(policy, migrated = true, addedPackages = emptySet())
        }
    }

    fun migrateIfNeeded(
        context: Context,
        policy: DevicePolicyBundle,
        hasPersistedPolicy: Boolean
    ): MigrationResult {
        val appContext = context.applicationContext ?: context
        val prefs = try {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "SharedPreferences unavailable; deferring legacy curfew whitelist migration")
            return MigrationResult(policy, migrated = false, addedPackages = emptySet())
        } ?: return MigrationResult(policy, migrated = false, addedPackages = emptySet())
        if (prefs.getBoolean(KEY_LEGACY_CURFEW_EXEMPT_MIGRATED, false)) {
            return MigrationResult(policy, migrated = false, addedPackages = emptySet())
        }

        if (!hasPersistedPolicy) {
            prefs.edit().putBoolean(KEY_LEGACY_CURFEW_EXEMPT_MIGRATED, true).commit()
            return MigrationResult(policy, migrated = false, addedPackages = emptySet())
        }

        val installedPackages = queryInstalledPackages(appContext)
        if (installedPackages.isEmpty()) {
            Timber.tag(TAG).w("PackageManager query failed or returned empty; deferring legacy curfew whitelist migration to next boot")
            return MigrationResult(policy, migrated = false, addedPackages = emptySet())
        }

        prefs.edit().putString(KEY_PRE_MIGRATION_POLICY_BACKUP, policy.toJson()).commit()

        val result = computeMigratedPolicy(
            policy = policy,
            installedPackages = installedPackages,
            selfPackageName = appContext.packageName,
            alreadyMigrated = false,
            hasPersistedPolicy = true
        )

        val editor = prefs.edit().putBoolean(KEY_LEGACY_CURFEW_EXEMPT_MIGRATED, true)
        if (result.addedPackages.isNotEmpty()) {
            val exemptAdded = result.addedPackages.filterTo(linkedSetOf()) { pkg ->
                AppPolicyRegistry.isExemptFromCurfew(pkg)
            }
            if (exemptAdded.isNotEmpty()) {
                editor.putStringSet(KEY_MIGRATED_EXEMPT_PACKAGES, exemptAdded)
                editor.putLong(KEY_MIGRATED_AT_POLICY_VERSION, policy.version)
            }
        }
        editor.commit()

        BlackBoxRecorder.recordCloudSync(
            appContext,
            "LEGACY_CURFEW_EXEMPT_MIGRATED",
            isSuccess = true,
            details = "added=${result.addedPackages.size}, wlBefore=${policy.customCurfewWhitelistPackages.size}, wlAfter=${result.policy.customCurfewWhitelistPackages.size}, vpnBypass=${result.policy.curfewVpnBypassPackages.size}"
        )

        return result
    }

    fun getPendingMigratedExemptPackages(context: Context, cloudVersion: Long): Set<String> {
        val appContext = context.applicationContext ?: context
        val prefs = try {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "SharedPreferences unavailable; returning empty migrated exempt packages")
            return emptySet()
        } ?: return emptySet()

        val migratedAtVersion = prefs.getLong(KEY_MIGRATED_AT_POLICY_VERSION, 0L)
        if (cloudVersion > migratedAtVersion) {
            if (migratedAtVersion > 0L) {
                clearPendingMigratedExemptPackages(appContext)
            }
            return emptySet()
        }

        val stored = prefs.getStringSet(KEY_MIGRATED_EXEMPT_PACKAGES, null) ?: return emptySet()
        return stored.filterTo(linkedSetOf()) { pkg -> AppPolicyRegistry.isExemptFromCurfew(pkg) }
    }

    fun clearPendingMigratedExemptPackages(context: Context) {
        val appContext = context.applicationContext ?: context
        try {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.remove(KEY_MIGRATED_EXEMPT_PACKAGES)
                ?.remove(KEY_MIGRATED_AT_POLICY_VERSION)
                ?.commit()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed clearing pending migrated exempt packages")
        }
    }

    private fun queryInstalledPackages(context: Context): Set<String> = try {
        val pm = context.packageManager
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        apps.mapNotNullTo(linkedSetOf()) { it.packageName?.takeIf(String::isNotBlank) }
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Failed querying installed applications for legacy curfew whitelist migration")
        emptySet()
    }
}
