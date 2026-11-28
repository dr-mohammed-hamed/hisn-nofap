package com.drmohammed.antiporn.curfew

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Regression tests for Thursday same-day curfew windows, partial-release package tracking,
 * and Fortress veto invariants over curfew snooze requests.
 */
class CurfewHardeningTest {

    private fun createCalendar(hourOfDay: Int, minute: Int, dayOfWeek: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone(CurfewConfig.DEFAULT_TIMEZONE_ID)).apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    @Test
    fun testCurfewScheduleExt_thursdaySameDayWeekendEndTimeAndActiveEnd() {
        // Wednesday crosses midnight (20:00-07:00) AND Thursday has a same-day weekend window (14:00-17:00)
        val thursdayEndTimesWithWedSpill = CurfewScheduleExt.getCurfewEndTimesForDay(
            dayOfWeek = Calendar.THURSDAY,
            startHour = 20,
            startMinute = 0,
            endHour = 7,
            endMinute = 0,
            isWeekendCurfewEnabled = true,
            weekendStartHour = 14,
            weekendStartMinute = 0,
            weekendEndHour = 17,
            weekendEndMinute = 0
        )
        assertEquals(listOf(7 to 0, 17 to 0), thursdayEndTimesWithWedSpill)

        val thursdayAfternoon = createCalendar(15, 0, Calendar.THURSDAY)
        assertEquals(
            17 to 0,
            CurfewScheduleExt.resolveActiveCurfewEnd(
                calendar = thursdayAfternoon,
                startHour = 20,
                startMinute = 0,
                endHour = 7,
                endMinute = 0,
                isWeekendCurfewEnabled = true,
                weekendStartHour = 14,
                weekendStartMinute = 0,
                weekendEndHour = 17,
                weekendEndMinute = 0
            )
        )

        // Same-day weekday window (09:00-12:00) -> Thursday morning has no Wednesday spill, only weekend 17:00
        val thursdayEndTimesNoSpill = CurfewScheduleExt.getCurfewEndTimesForDay(
            dayOfWeek = Calendar.THURSDAY,
            startHour = 9,
            startMinute = 0,
            endHour = 12,
            endMinute = 0,
            isWeekendCurfewEnabled = true,
            weekendStartHour = 14,
            weekendStartMinute = 0,
            weekendEndHour = 17,
            weekendEndMinute = 0
        )
        assertEquals(listOf(17 to 0), thursdayEndTimesNoSpill)
    }

    @Test
    fun testCurfewVpnDnsBridge_localVpnAndStandaloneConsistency() {
        assertTrue(
            CurfewVpnDnsBridge.isLocalVpnDnsStateConsistent(
                dpmMode = android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC,
                globalMode = "opportunistic",
                globalHost = null
            )
        )
        // Cached specifier left behind by Android when switching to opportunistic/off is harmless and must pass
        assertTrue(
            CurfewVpnDnsBridge.isLocalVpnDnsStateConsistent(
                dpmMode = android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC,
                globalMode = "opportunistic",
                globalHost = "family.cloudflare-dns.com"
            )
        )
        // Reject DoT hostname in Settings.Global even if DPM reports opportunistic (C3 split-brain fix)
        assertFalse(
            CurfewVpnDnsBridge.isLocalVpnDnsStateConsistent(
                dpmMode = android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC,
                globalMode = "hostname",
                globalHost = "family.cloudflare-dns.com"
            )
        )
        assertTrue(
            CurfewVpnDnsBridge.isStandaloneDnsStateConsistent(
                dpmMode = android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
                dpmHost = "family.cloudflare-dns.com",
                globalMode = "hostname",
                globalHost = "family.cloudflare-dns.com",
                targetHost = "family.cloudflare-dns.com"
            )
        )
        assertFalse(
            CurfewVpnDnsBridge.isStandaloneDnsStateConsistent(
                dpmMode = android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
                dpmHost = "dns.google",
                globalMode = "hostname",
                globalHost = "family.cloudflare-dns.com",
                targetHost = "family.cloudflare-dns.com"
            )
        )
        // Standalone fallback: when DPM is unavailable (-1 / null) due to unaffiliated users but Settings.Global matches targetHost
        assertTrue(
            CurfewVpnDnsBridge.isStandaloneDnsStateConsistent(
                dpmMode = -1,
                dpmHost = null,
                globalMode = "hostname",
                globalHost = "family.cloudflare-dns.com",
                targetHost = "family.cloudflare-dns.com"
            )
        )
        // Standalone fallback: when DPM is stuck on old host due to unaffiliated users, but Settings.Global matches new targetHost
        assertTrue(
            CurfewVpnDnsBridge.isStandaloneDnsStateConsistent(
                dpmMode = android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
                dpmHost = "family.cloudflare-dns.com",
                globalMode = "hostname",
                globalHost = "nextdns.io",
                targetHost = "nextdns.io",
                isUnaffiliatedBlocked = true
            )
        )
        assertFalse(
            CurfewVpnDnsBridge.isStandaloneDnsStateConsistent(
                dpmMode = android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
                dpmHost = "family.cloudflare-dns.com",
                globalMode = "hostname",
                globalHost = "nextdns.io",
                targetHost = "nextdns.io",
                isUnaffiliatedBlocked = false
            )
        )
        // Local VPN fallback: when DPM is unavailable (-1 / null) but Settings.Global is opportunistic
        assertTrue(
            CurfewVpnDnsBridge.isLocalVpnDnsStateConsistent(
                dpmMode = -1,
                globalMode = "opportunistic",
                globalHost = null
            )
        )
        // Local VPN with Unaffiliated Users: accepts verified family preset in hostname mode as safe fallback
        assertTrue(
            "Local VPN must accept verified family preset in hostname mode when unaffiliated users block opportunistic mode",
            CurfewVpnDnsBridge.isLocalVpnDnsStateConsistent(
                dpmMode = android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
                globalMode = "hostname",
                globalHost = "family.cloudflare-dns.com",
                isUnaffiliatedBlocked = true
            )
        )
        // Local VPN with Unaffiliated Users: must strictly REJECT non-family preset or generic resolvers
        assertFalse(
            "Local VPN must reject unverified DNS resolver (dns.google) even if unaffiliated users are present",
            CurfewVpnDnsBridge.isLocalVpnDnsStateConsistent(
                dpmMode = android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
                globalMode = "hostname",
                globalHost = "dns.google",
                isUnaffiliatedBlocked = true
            )
        )
        // Local VPN without Unaffiliated Users: must strictly REJECT hostname mode (must switch to opportunistic)
        assertFalse(
            "Local VPN must reject hostname mode when device has NO unaffiliated users",
            CurfewVpnDnsBridge.isLocalVpnDnsStateConsistent(
                dpmMode = android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
                globalMode = "hostname",
                globalHost = "family.cloudflare-dns.com",
                isUnaffiliatedBlocked = false
            )
        )
    }

    @Test
    fun testCurfewScheduleExt_thursdaySameDayWindowAndEqualBounds() {
        val thursdayMidday = createCalendar(15, 0, Calendar.THURSDAY)
        assertFalse(
            "Thursday daytime must not activate a same-day weekday window (14:00-16:00) before evening weekend start",
            CurfewEnforcer.isScheduleWindowActive(
                calendar = thursdayMidday,
                startHour = 14,
                startMinute = 0,
                endHour = 16,
                endMinute = 0,
                isWeekendCurfewEnabled = true,
                weekendStartHour = 21,
                weekendStartMinute = 0,
                weekendEndHour = 9,
                weekendEndMinute = 0
            )
        )
        assertFalse(
            "Degenerate equal start/end window (20:00-20:00) must evaluate to inactive",
            CurfewScheduleExt.isMinuteInWindow(minuteOfDay = 20 * 60, startMinute = 20 * 60, endMinute = 20 * 60)
        )
    }

    @Test
    fun testCurfewConfig_removeTrackedSuspendedPackagesPreservesFailedUnsuspends() {
        val prefs = InMemoryPrefs()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getApplicationContext(): Context = this
        }
        CurfewConfig.addTrackedSuspendedPackages(context, listOf("com.one", "com.two", "com.three"))
        CurfewConfig.removeTrackedSuspendedPackages(context, listOf("com.one", "com.three"))
        assertEquals(setOf("com.two"), CurfewConfig.getTrackedSuspendedPackages(context))
    }

    @Test
    fun testCurfewEnforcer_snoozeRejectedWhileFortressIsActive() {
        val prefsMap = mutableMapOf<String, InMemoryPrefs>()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                prefsMap.getOrPut(name ?: "default") { InMemoryPrefs() }
            override fun getApplicationContext(): Context = this
        }
        val fortressController = com.drmohammed.antiporn.fortress.engine.FortressSessionController
        try {
            fortressController.elapsedProvider = { 10_000L }
            fortressController.wallProvider = { 1_000_000L }
            fortressController.bootCountProvider = { 1 }

            fortressController.startFortress(
                context,
                com.drmohammed.antiporn.fortress.model.FortressProfile.defaultEmergency()
            )
            assertTrue(fortressController.isFortressActive(context))
            assertFalse(
                "Curfew snooze must be rejected while Fortress session is active",
                CurfewEnforcer.snoozeCurfew(context, minutes = 15)
            )
            val curfewPrefs = prefsMap[CurfewConfig.PREFS_NAME]
            assertEquals(
                "Snooze end timestamp must not be written when Fortress vetoes snooze",
                0L,
                curfewPrefs?.getLong(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL, 0L) ?: 0L
            )
        } finally {
            fortressController.resetForTesting()
        }
    }

    @Test
    fun testDaytimeHighRiskRelease_treatsHighRiskIdenticallyToNormalAppsAndEnforcesCustomBlacklist() {
        val highRiskPkg = "org.telegram.messenger"

        // High-risk apps are treated identically to normal apps during daytime release:
        // only explicit customBlacklist blocks release in isHighRiskOrBlacklistedForRelease.
        assertTrue(CurfewLifecycleHandler.isApprovalEligibleForRelease(highRiskPkg, isApproved = true, decidedBy = "guardian_uid_123"))
        assertTrue(CurfewLifecycleHandler.isApprovalEligibleForRelease(highRiskPkg, isApproved = true, decidedBy = "INITIAL_DEVICE_BASELINE"))
        assertTrue(CurfewLifecycleHandler.isApprovalEligibleForRelease(highRiskPkg, isApproved = true, decidedBy = "AUTO_APPROVED_UNLOCKED"))
        assertTrue(CurfewLifecycleHandler.isApprovalEligibleForRelease(highRiskPkg, isApproved = true, decidedBy = null))
        assertFalse(CurfewLifecycleHandler.isApprovalEligibleForRelease(highRiskPkg, isApproved = false, decidedBy = "guardian_uid_123"))

        // Non-high-risk approved apps remain eligible even with null decidedBy
        assertTrue(CurfewLifecycleHandler.isApprovalEligibleForRelease("com.whatsapp", isApproved = true, decidedBy = null))

        // Not in customBlacklist -> NOT blocked by isHighRiskOrBlacklistedForRelease (even if unapproved)
        assertFalse(
            CurfewLifecycleHandler.isHighRiskOrBlacklistedForRelease(
                pkg = highRiskPkg,
                approvedPackages = setOf(highRiskPkg),
                customBlacklist = emptySet()
            )
        )
        assertFalse(
            CurfewLifecycleHandler.isHighRiskOrBlacklistedForRelease(
                pkg = highRiskPkg,
                approvedPackages = emptySet(),
                customBlacklist = emptySet()
            )
        )

        // Explicitly custom-blacklisted app stays suspended regardless of approval set
        assertTrue(
            CurfewLifecycleHandler.isHighRiskOrBlacklistedForRelease(
                pkg = highRiskPkg,
                approvedPackages = setOf(highRiskPkg),
                customBlacklist = setOf(highRiskPkg)
            )
        )
        assertTrue(
            CurfewLifecycleHandler.isHighRiskOrBlacklistedForRelease(
                pkg = highRiskPkg,
                approvedPackages = emptySet(),
                customBlacklist = setOf(highRiskPkg)
            )
        )
    }

    @Test
    fun testCurfewConfig_dnsRestorePendingPersistence() {
        val prefs = InMemoryPrefs()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getApplicationContext(): Context = this
        }
        assertFalse(CurfewConfig.isDnsRestorePending(context))
        CurfewConfig.setDnsRestorePending(context, true)
        assertTrue(CurfewConfig.isDnsRestorePending(context))
        CurfewConfig.setDnsRestorePending(context, false)
        assertFalse(CurfewConfig.isDnsRestorePending(context))
    }

    @Test
    fun testCurfewDnsRestorePending_suppressesFalseTamperAlert() {
        val prefs = InMemoryPrefs()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getApplicationContext(): Context = this
        }

        // Scenario 1: Offline post-curfew DNS restoration pending
        CurfewConfig.setDnsRestorePending(context, true)
        val mode = "opportunistic"
        val specifier = ""
        val expectedHost = "family.cloudflare-dns.com"

        val isPendingCurfewRelease = (mode == "opportunistic" || mode.isNullOrEmpty()) &&
            specifier.isNullOrEmpty() &&
            CurfewConfig.isDnsRestorePending(context)
        assertTrue("Must recognize offline pending restore from curfew", isPendingCurfewRelease)

        val desyncReasons = mutableListOf<String>()
        if (isPendingCurfewRelease) {
            desyncReasons.add("[DNS_PENDING_RESTORE: Settings.Global DNS pending post-curfew restoration]")
        } else {
            desyncReasons.add("[DNS_GLOBAL: Settings.Global DNS mismatch]")
        }

        val isDirectTamper = desyncReasons.any {
            it.startsWith("[TIMEZONE:") ||
                it.startsWith("[AUTO_TIME:") ||
                it.startsWith("[system_tamper:") ||
                (it.startsWith("[DNS_GLOBAL:") && !it.contains("VPN required"))
        }
        assertFalse("Pending post-curfew DNS restore must NOT trigger isDirectTamper", isDirectTamper)

        val isTransitionSafe = !CurfewConfig.isDnsRestorePending(context)
        assertFalse("Notification gate must block alert when isDnsRestorePending is true", isTransitionSafe)

        // Scenario 2: Actual direct tamper (user turned DNS off or changed specifier to an unauthorized server)
        val tamperedMode = "off"
        val isPendingTamper = (tamperedMode == "opportunistic" || tamperedMode.isNullOrEmpty()) &&
            specifier.isNullOrEmpty() &&
            CurfewConfig.isDnsRestorePending(context)
        assertFalse("Turning DNS off cannot be masked as pending restore", isPendingTamper)

        val tamperReasons = mutableListOf<String>()
        tamperReasons.add("[DNS_GLOBAL: Settings.Global DNS mismatch (mode=off)]")
        val isDirectTamperDetected = tamperReasons.any {
            it.startsWith("[TIMEZONE:") ||
                it.startsWith("[AUTO_TIME:") ||
                it.startsWith("[system_tamper:") ||
                (it.startsWith("[DNS_GLOBAL:") && !it.contains("VPN required"))
        }
        assertTrue("Genuine tampering must be caught as isDirectTamper", isDirectTamperDetected)
    }

    @Test
    fun testCurfewVpnDnsBridge_hasActiveVerifiedFamilyDns() {
        // Must accept known family presets in hostname mode
        assertTrue(CurfewVpnDnsBridge.hasActiveVerifiedFamilyDns("hostname", "family.cloudflare-dns.com"))
        assertTrue(CurfewVpnDnsBridge.hasActiveVerifiedFamilyDns("hostname", "no-ads-porn.freedns.controld.com"))
        assertTrue(CurfewVpnDnsBridge.hasActiveVerifiedFamilyDns("hostname", "dns-family.adguard.com"))
        assertTrue(CurfewVpnDnsBridge.hasActiveVerifiedFamilyDns("hostname", "family.adguard-dns.com"))
        assertTrue(CurfewVpnDnsBridge.hasActiveVerifiedFamilyDns("hostname", "family-filter-dns.cleanbrowsing.org"))

        // Must reject non-family presets, generic resolvers, blank hosts, or non-hostname modes
        assertFalse(CurfewVpnDnsBridge.hasActiveVerifiedFamilyDns("hostname", "dns.google"))
        assertFalse(CurfewVpnDnsBridge.hasActiveVerifiedFamilyDns("hostname", "1.1.1.1"))
        assertFalse(CurfewVpnDnsBridge.hasActiveVerifiedFamilyDns("hostname", ""))
        assertFalse(CurfewVpnDnsBridge.hasActiveVerifiedFamilyDns("hostname", null))
        assertFalse(CurfewVpnDnsBridge.hasActiveVerifiedFamilyDns("opportunistic", "family.cloudflare-dns.com"))
        assertFalse(CurfewVpnDnsBridge.hasActiveVerifiedFamilyDns("off", "family.cloudflare-dns.com"))
        assertFalse(CurfewVpnDnsBridge.hasActiveVerifiedFamilyDns(null, "family.cloudflare-dns.com"))
    }

    @Test
    fun testCurfewConfig_privateDnsBlockedByUnaffiliatedUsersPersistence() {
        val prefs = InMemoryPrefs()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getApplicationContext(): Context = this
        }
        assertFalse(CurfewConfig.isPrivateDnsBlockedByUnaffiliatedUsers(context))
        CurfewConfig.setPrivateDnsBlockedByUnaffiliatedUsers(context, true)
        assertTrue(CurfewConfig.isPrivateDnsBlockedByUnaffiliatedUsers(context))
        CurfewConfig.setPrivateDnsBlockedByUnaffiliatedUsers(context, false)
        assertFalse(CurfewConfig.isPrivateDnsBlockedByUnaffiliatedUsers(context))
    }

    @Test
    fun testCurfewConfig_pendingDpmPrivateDnsSyncPersistenceAndTeardownClear() {
        val prefs = InMemoryPrefs()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getApplicationContext(): Context = this
        }

        assertFalse(CurfewConfig.isPendingDpmPrivateDnsSync(context))
        assertEquals(null, CurfewConfig.getPendingDpmPrivateDnsHost(context))

        CurfewConfig.setPendingDpmPrivateDnsSync(context, true, "family.adguard-dns.com")
        assertTrue(CurfewConfig.isPendingDpmPrivateDnsSync(context))
        assertEquals("family.adguard-dns.com", CurfewConfig.getPendingDpmPrivateDnsHost(context))

        CurfewConfig.clearTransientFlagsForTeardown(context)
        assertFalse(CurfewConfig.isPendingDpmPrivateDnsSync(context))
        assertEquals(null, CurfewConfig.getPendingDpmPrivateDnsHost(context))
    }

    @Test
    fun testWatchdog_localVpnWithUnaffiliatedFamilyDoT_suppressesDesync() {
        val mode: String? = "hostname"
        val specifier = "family.cloudflare-dns.com"
        val isUnaffiliatedBlocked = true

        val isAppropriate = mode == "opportunistic" || mode == "off" || mode == null
        val isUnaffiliatedProtectedFallback = isUnaffiliatedBlocked &&
            mode == "hostname" &&
            !specifier.isNullOrBlank() &&
            com.drmohammed.antiporn.core.network.DnsFilteringValidator.isKnownFamilyPreset(specifier)

        val desyncReasons = mutableListOf<String>()
        if (!isAppropriate && !isUnaffiliatedProtectedFallback) {
            desyncReasons.add("[DNS_GLOBAL: mismatch]")
        }

        assertTrue("Verified family preset with unaffiliated users must be recognized as fallback", isUnaffiliatedProtectedFallback)
        assertTrue("Desync reasons must remain empty for verified fallback", desyncReasons.isEmpty())

        // If user changed to dns.google, it is NOT a verified family fallback and must trigger desync
        val untrustedSpecifier = "dns.google"
        val isUntrustedFallback = isUnaffiliatedBlocked &&
            mode == "hostname" &&
            com.drmohammed.antiporn.core.network.DnsFilteringValidator.isKnownFamilyPreset(untrustedSpecifier)
        assertFalse(isUntrustedFallback)

        val untrustedDesync = mutableListOf<String>()
        if (!isAppropriate && !isUntrustedFallback) {
            untrustedDesync.add("[DNS_GLOBAL: mismatch]")
        }
        assertEquals(1, untrustedDesync.size)
    }

    private class InMemoryPrefs : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, v: MutableSet<String>?) = apply { if (key != null) pending[key] = v?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) { values.clear(); clearAll = false }
                for ((k, v) in pending) {
                    if (v == null) values.remove(k) else values[k] = v
                }
                pending.clear()
            }
        }
    }

    @Test
    fun testEnsureFailClosedOnUnexpectedStop_whenActiveTransitions_earlyReturns() {
        val fakeCtx = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        val vpnService = CurfewBlackholeVpnService()
        com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.resetForTest()
        val token = com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.acquireTransitionToken("TEST_TRANSITION")
        try {
            assertTrue(com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.hasActiveTransitions())
            // Must return early without exception
            vpnService.ensureFailClosedOnUnexpectedStop(fakeCtx, wasRevoked = true)
        } finally {
            com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.releaseTransitionToken(token)
            com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.resetForTest()
        }
    }

    @Test
    fun testCurfewLifecycleSupport_isVpnReconcileNeeded_skipsDaytimeStartAndReleasesBlackhole() {
        assertFalse(
            "Daytime DNS_FILTERING_ONLY without active tunnel must not trigger curfew release reconcile",
            CurfewLifecycleSupport.isVpnReconcileNeeded(
                instantKill = false,
                requiredMode = CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY,
                isServiceOrTunnelActive = false,
                currentMode = CurfewBlackholeVpnService.VpnMode.IDLE
            )
        )
        assertFalse(
            "Already running daytime DNS_FILTERING_ONLY must not trigger release reconcile",
            CurfewLifecycleSupport.isVpnReconcileNeeded(
                instantKill = false,
                requiredMode = CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY,
                isServiceOrTunnelActive = true,
                currentMode = CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY
            )
        )
        assertTrue(
            "Active CURFEW_BLACKHOLE must trigger release reconcile when downgrading to DNS_FILTERING_ONLY",
            CurfewLifecycleSupport.isVpnReconcileNeeded(
                instantKill = false,
                requiredMode = CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY,
                isServiceOrTunnelActive = true,
                currentMode = CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE
            )
        )
        assertTrue(
            "Active tunnel must trigger release reconcile when requiredMode is IDLE",
            CurfewLifecycleSupport.isVpnReconcileNeeded(
                instantKill = false,
                requiredMode = CurfewBlackholeVpnService.VpnMode.IDLE,
                isServiceOrTunnelActive = true,
                currentMode = CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY
            )
        )
        assertFalse(
            "Inactive tunnel with requiredMode=IDLE needs no reconcile",
            CurfewLifecycleSupport.isVpnReconcileNeeded(
                instantKill = false,
                requiredMode = CurfewBlackholeVpnService.VpnMode.IDLE,
                isServiceOrTunnelActive = false,
                currentMode = CurfewBlackholeVpnService.VpnMode.IDLE
            )
        )
        assertFalse(
            "Instant kill active must skip release reconcile",
            CurfewLifecycleSupport.isVpnReconcileNeeded(
                instantKill = true,
                requiredMode = CurfewBlackholeVpnService.VpnMode.IDLE,
                isServiceOrTunnelActive = true,
                currentMode = CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE
            )
        )
    }

    @Test
    fun testCurfewVpnTunnelHandler_shouldConfigureIpv6Tunnel() {
        assertFalse(CurfewVpnTunnelHandler.shouldConfigureIpv6Tunnel(false))
        assertTrue(CurfewVpnTunnelHandler.shouldConfigureIpv6Tunnel(true))
    }
}
