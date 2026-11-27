package com.drmohammed.antiporn.curfew

import android.content.Context
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.fortress.engine.CurfewWindowOverrideStore
import com.drmohammed.antiporn.fortress.engine.EffectiveRestrictionResolver
import timber.log.Timber
import java.util.Calendar
import java.util.TimeZone

/**
 * Configuration and immutable constants for the Scheduled Night Curfew and enforcement preferences.
 */
object CurfewConfig {

    const val DEFAULT_TIMEZONE_ID = "Africa/Cairo"
    const val CAIRO_TIMEZONE_ID = "Africa/Cairo"
    const val MAKKAH_TIMEZONE_ID = "Asia/Riyadh"

    fun getDefaultFallbackTimeZone(): String = try {
        val localId = TimeZone.getDefault().id
        if (!localId.isNullOrBlank() && com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry.isValidIanaId(localId)) {
            localId
        } else {
            DEFAULT_TIMEZONE_ID
        }
    } catch (_: Exception) {
        DEFAULT_TIMEZONE_ID
    }

    private fun safeLogFallback(isError: Boolean, message: String, throwable: Throwable) {
        if (isError) Timber.tag("AntiPorn_CurfewConfig").e(throwable, message)
        else Timber.tag("AntiPorn_CurfewConfig").w(throwable, message)
    }

    private inline fun <T> readPolicyOrFallback(
        context: Context,
        defaultValue: T,
        isError: Boolean = false,
        selector: (DevicePolicyBundle) -> T
    ): T = try {
        selector(PolicyRepository.getInstance(context).getCurrentPolicy())
    } catch (e: Exception) {
        safeLogFallback(isError, "Failed reading config, falling back to default", e)
        defaultValue
    }

    fun getTargetTimeZoneId(context: Context): String {
        val tzId = readPolicyOrFallback(context, getDefaultFallbackTimeZone()) { it.timeZoneId }
        return if (tzId.isNotBlank() && com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry.isValidIanaId(tzId)) {
            tzId
        } else {
            getDefaultFallbackTimeZone()
        }
    }

    fun getCurfewCalendar(): Calendar = TrustedTimeProvider.getCurfewCalendar()
    fun getCurfewCalendar(context: Context): Calendar = TrustedTimeProvider.getCurfewCalendar(context)

    const val START_HOUR = 20
    const val START_MINUTE = 0
    const val END_HOUR = 7
    const val END_MINUTE = 0

    fun getCurfewStartHour(context: Context): Int = readPolicyOrFallback(context, START_HOUR) { it.curfewStartHour }
    fun getCurfewStartMinute(context: Context): Int = readPolicyOrFallback(context, START_MINUTE) { it.curfewStartMinute }
    fun getCurfewEndHour(context: Context): Int = readPolicyOrFallback(context, END_HOUR) { it.curfewEndHour }
    fun getCurfewEndMinute(context: Context): Int = readPolicyOrFallback(context, END_MINUTE) { it.curfewEndMinute }
    fun isCurfewEnabled(context: Context): Boolean = readPolicyOrFallback(context, false, isError = true) { it.isCurfewEnabled }
    fun isCurfewEnabledFailSecure(context: Context): Boolean = readPolicyOrFallback(context, true, isError = true) { it.isCurfewEnabled }
    fun isInstantInternetKillEnabled(context: Context): Boolean = readPolicyOrFallback(context, false) { it.isInstantInternetKillEnabled }
    fun isWeekendCurfewEnabled(context: Context): Boolean = readPolicyOrFallback(context, false) { it.isWeekendCurfewEnabled }
    fun getWeekendCurfewStartHour(context: Context): Int = readPolicyOrFallback(context, START_HOUR) { it.weekendCurfewStartHour }
    fun getWeekendCurfewStartMinute(context: Context): Int = readPolicyOrFallback(context, START_MINUTE) { it.weekendCurfewStartMinute }
    fun getWeekendCurfewEndHour(context: Context): Int = readPolicyOrFallback(context, END_HOUR) { it.weekendCurfewEndHour }
    fun getWeekendCurfewEndMinute(context: Context): Int = readPolicyOrFallback(context, END_MINUTE) { it.weekendCurfewEndMinute }

    const val DEFAULT_VPN_BLACKHOLE_ENABLED = true

    fun isVpnBlackholeEnabled(context: Context): Boolean =
        readPolicyOrFallback(context, DEFAULT_VPN_BLACKHOLE_ENABLED) { it.isVpnBlackholeEnabled }

    const val PREFS_NAME = "antiporn_curfew_prefs"
    const val KEY_CURFEW_ENABLED = "key_curfew_enabled"
    const val KEY_CURFEW_SUSPENDED_PACKAGES = "key_curfew_suspended_packages"
    const val KEY_MANUAL_TEST_ACTIVE = "key_manual_test_active"
    const val KEY_CURFEW_VPN_BYPASS_PACKAGES = "key_curfew_vpn_bypass_packages"
    const val KEY_CURFEW_SNOOZE_END_WALL = "key_curfew_snooze_end_wall"
    const val KEY_LAST_APPLIED_EFFECTIVE_SIGNATURE = "last_applied_effective_signature"
    private const val KEY_LAST_BLOCK_ENFORCED = "last_block_enforced"
    private const val KEY_DNS_RESTORE_PENDING = "curfew_dns_restore_pending"
    private const val KEY_PRIVATE_DNS_BLOCKED_UNAFFILIATED = "curfew_private_dns_blocked_unaffiliated"
    private const val KEY_PENDING_DPM_PRIVATE_DNS_SYNC = "pending_dpm_private_dns_sync"
    private const val KEY_PENDING_DPM_PRIVATE_DNS_HOST = "pending_dpm_private_dns_host"
    const val ACTION_CURFEW_SNOOZE = "com.drmohammed.antiporn.ACTION_CURFEW_SNOOZE"
    const val ACTION_CURFEW_SNOOZE_END = "com.drmohammed.antiporn.ACTION_CURFEW_SNOOZE_END"

    fun isLastBlockEnforced(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_LAST_BLOCK_ENFORCED, false)

    fun setLastBlockEnforced(context: Context, enforced: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LAST_BLOCK_ENFORCED, enforced).commit()
    }

    fun getLastAppliedEffectiveSignature(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_APPLIED_EFFECTIVE_SIGNATURE, "") ?: ""

    fun setLastAppliedEffectiveSignature(context: Context, sig: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_APPLIED_EFFECTIVE_SIGNATURE, sig).commit()
    }

    fun isDnsRestorePending(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_DNS_RESTORE_PENDING, false)

    fun setDnsRestorePending(context: Context, pending: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DNS_RESTORE_PENDING, pending).commit()
    }

    fun isPrivateDnsBlockedByUnaffiliatedUsers(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PRIVATE_DNS_BLOCKED_UNAFFILIATED, false)

    fun setPrivateDnsBlockedByUnaffiliatedUsers(context: Context, blocked: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PRIVATE_DNS_BLOCKED_UNAFFILIATED, blocked).commit()
    }

    fun isPendingDpmPrivateDnsSync(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PENDING_DPM_PRIVATE_DNS_SYNC, false)
    } catch (e: Exception) {
        Timber.tag("AntiPorn_CurfewConfig").w(e, "Failed reading KEY_PENDING_DPM_PRIVATE_DNS_SYNC")
        false
    }

    fun getPendingDpmPrivateDnsHost(context: Context): String? = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_DPM_PRIVATE_DNS_HOST, null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Timber.tag("AntiPorn_CurfewConfig").w(e, "Failed reading KEY_PENDING_DPM_PRIVATE_DNS_HOST")
        null
    }

    fun setPendingDpmPrivateDnsSync(context: Context, pending: Boolean, host: String? = null) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit().putBoolean(KEY_PENDING_DPM_PRIVATE_DNS_SYNC, pending)
            if (pending && !host.isNullOrBlank()) {
                editor.putString(KEY_PENDING_DPM_PRIVATE_DNS_HOST, host.trim())
            } else if (!pending) {
                editor.remove(KEY_PENDING_DPM_PRIVATE_DNS_HOST)
            }
            if (!editor.commit()) {
                Timber.tag("AntiPorn_CurfewConfig").w("SharedPreferences commit() returned false in setPendingDpmPrivateDnsSync")
            }
        } catch (e: Exception) {
            Timber.tag("AntiPorn_CurfewConfig").w(e, "Failed updating KEY_PENDING_DPM_PRIVATE_DNS_SYNC")
        }
    }

    fun getTrackedSuspendedPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_CURFEW_SUSPENDED_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    fun addTrackedSuspendedPackages(context: Context, packages: Collection<String>) {
        if (packages.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = prefs.getStringSet(KEY_CURFEW_SUSPENDED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (updated.addAll(packages)) {
            prefs.edit().putStringSet(KEY_CURFEW_SUSPENDED_PACKAGES, updated).commit()
        }
    }

    fun removeTrackedSuspendedPackages(context: Context, packages: Collection<String>) {
        if (packages.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = prefs.getStringSet(KEY_CURFEW_SUSPENDED_PACKAGES, emptySet())?.toMutableSet() ?: return
        if (updated.removeAll(packages.toSet())) {
            val editor = prefs.edit()
            if (updated.isEmpty()) editor.remove(KEY_CURFEW_SUSPENDED_PACKAGES)
            else editor.putStringSet(KEY_CURFEW_SUSPENDED_PACKAGES, updated)
            editor.commit()
        }
    }

    fun clearTrackedSuspendedPackages(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CURFEW_SUSPENDED_PACKAGES)) {
            prefs.edit().remove(KEY_CURFEW_SUSPENDED_PACKAGES).commit()
        }
    }

    /**
     * Clears transient curfew, DNS-restore, signature, override, and manual-test flags during DO teardown.
     */
    fun clearTransientFlagsForTeardown(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_MANUAL_TEST_ACTIVE, false)
            .putBoolean(KEY_LAST_BLOCK_ENFORCED, false)
            .putBoolean(KEY_DNS_RESTORE_PENDING, false)
            .putBoolean(KEY_PRIVATE_DNS_BLOCKED_UNAFFILIATED, false)
            .putBoolean(KEY_PENDING_DPM_PRIVATE_DNS_SYNC, false)
            .remove(KEY_PENDING_DPM_PRIVATE_DNS_HOST)
            .remove(KEY_CURFEW_SUSPENDED_PACKAGES)
            .remove(KEY_CURFEW_SNOOZE_END_WALL)
            .remove(KEY_LAST_APPLIED_EFFECTIVE_SIGNATURE)
            .commit()
        CurfewWindowOverrideStore.clearOverride(context)
    }

    /**
     * Packages explicitly permitted to bypass the blackhole VPN under Strictest-Wins resolution.
     */
    fun getVpnBypassedPackages(context: Context): Set<String> =
        EffectiveRestrictionResolver.resolve(context).effectiveVpnBypassPackages

    fun setVpnBypassedPackages(context: Context, packages: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_CURFEW_VPN_BYPASS_PACKAGES, packages - context.packageName).commit()
    }

    fun addVpnBypassedPackage(context: Context, packageName: String) {
        if (packageName == context.packageName) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_CURFEW_VPN_BYPASS_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_CURFEW_VPN_BYPASS_PACKAGES, current).commit()
    }

    fun removeVpnBypassedPackage(context: Context, packageName: String) {
        if (packageName == context.packageName) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_CURFEW_VPN_BYPASS_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_CURFEW_VPN_BYPASS_PACKAGES, current).commit()
    }

    val STATIC_WHITELIST: Set<String> get() = AppPolicyRegistry.getCurfewStaticWhitelist()

    val KNOWN_CALCULATOR_PACKAGES: Set<String> = setOf(
        "com.google.android.calculator", "com.coloros.calculator", "com.oplus.calculator",
        "com.sec.android.app.popupcalculator", "com.android.calculator2", "com.miui.calculator"
    )

    val KNOWN_CLOCK_PACKAGES: Set<String> = setOf(
        "com.google.android.deskclock", "com.coloros.alarmclock", "com.oplus.alarmclock",
        "com.sec.android.app.clockpackage", "com.android.deskclock", "com.asus.deskclock"
    )

    val KNOWN_RECORDER_PACKAGES: Set<String> = setOf(
        "com.google.android.apps.recorder", "com.coloros.soundrecorder", "com.oplus.soundrecorder",
        "com.sec.android.app.voicenote", "com.android.soundrecorder"
    )

    val EXCLUDED_GALLERY_PACKAGES: Set<String> = setOf(
        "com.coloros.gallery3d", "com.oplus.gallery", "com.google.android.apps.photos",
        "com.sec.android.gallery3d", "com.android.gallery3d", "com.miui.gallery"
    )

    val SYSTEM_CRITICAL_PACKAGES: Set<String> get() = AppPolicyRegistry.getSystemCriticalPackages()

    const val GOOGLE_PLAY_STORE_PACKAGE = "com.android.vending"
    const val GOOGLE_SEARCH_PACKAGE = "com.google.android.googlequicksearchbox"

    val OEM_APP_STORES: Set<String> get() = AppPolicyRegistry.getOemAppStores()
    val KNOWN_APP_STORES: Set<String> = OEM_APP_STORES + GOOGLE_PLAY_STORE_PACKAGE
    val CURFEW_SEARCH_APPS: Set<String> = setOf("com.google.android.apps.searchlite")
}
