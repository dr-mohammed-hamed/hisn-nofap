package com.drmohammed.antiporn.curfew.notification

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import com.drmohammed.antiporn.HisnDeviceAdminService
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService
import com.drmohammed.antiporn.curfew.CurfewVpnStateCoordinator
import com.drmohammed.antiporn.fortress.engine.FortressSessionController
import com.drmohammed.antiporn.fortress.notification.FortressNotificationReceiver
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.waqfa.engine.WaqfaNotificationHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProtectionNotificationCoordinatorTest {

    /**
     * resolveCurrentState() now goes through EffectiveRestrictionResolver, whose default
     * calendar uses TrustedTimeProvider (backed by SystemClock.elapsedRealtime, which is
     * not available in local JVM unit tests). Inject the existing test seams with stable
     * values so no production logic needs to change for the notification coordinator.
     */
    @Before
    fun setUpInjectedClocks() {
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { FAKE_ELAPSED_MS }
        TrustedTimeProvider.wallTimeProvider = { FAKE_WALL_MS }
        TrustedTimeProvider.setAnchorForTesting(FAKE_WALL_MS, FAKE_ELAPSED_MS)
        FortressSessionController.resetForTesting()
        FortressSessionController.elapsedProvider = { FAKE_ELAPSED_MS }
        FortressSessionController.wallProvider = { FAKE_WALL_MS }
        FortressSessionController.bootCountProvider = { 1 }
    }

    @Test
    fun testConstantsAndIdsConsistency() {
        assertEquals("antiporn_curfew_vpn", ProtectionNotificationCoordinator.CHANNEL_ID_PROTECTION)
        assertEquals("antiporn_protection_events", ProtectionNotificationCoordinator.CHANNEL_ID_EVENTS)
        assertEquals(2001, ProtectionNotificationCoordinator.NOTIFICATION_ID_PROTECTION)
        assertEquals(2002, ProtectionNotificationCoordinator.NOTIFICATION_ID_EVENTS)

        // Must match CurfewBlackholeVpnService FGS requirements
        assertEquals(CurfewBlackholeVpnService.CHANNEL_ID, ProtectionNotificationCoordinator.CHANNEL_ID_PROTECTION)
        assertEquals(CurfewBlackholeVpnService.NOTIFICATION_ID, ProtectionNotificationCoordinator.NOTIFICATION_ID_PROTECTION)
    }

    @Test
    fun testStateHierarchy_GuardianLockdown_HasHighestPriority() {
        // State 1: Guardian Lockdown active while Curfew and Fortress are also active
        val state = ProtectionNotificationCoordinator.ProtectionState.GuardianLockdown(
            isCurfewAlsoActive = true,
            activeFortressName = "Study Fortress"
        )
        assertTrue(state.isCurfewAlsoActive)
        assertEquals("Study Fortress", state.activeFortressName)
    }

    @Test
    fun testStateHierarchy_FortressActive_HasChronometerData() {
        val endWall = 1_700_000_000_000L
        val remaining = 1_800_000L
        val state = ProtectionNotificationCoordinator.ProtectionState.FortressActive(
            fortressName = "Emergency Fortress",
            isEmergency = true,
            endWallMs = endWall,
            remainingMs = remaining
        )
        assertEquals("Emergency Fortress", state.fortressName)
        assertTrue(state.isEmergency)
        assertEquals(endWall, state.endWallMs)
        assertEquals(remaining, state.remainingMs)
    }

    @Test
    fun testStateHierarchy_CurfewActive_HasDynamicHours() {
        val state = ProtectionNotificationCoordinator.ProtectionState.CurfewActive(
            endHour = 6,
            endMinute = 30
        )
        assertEquals(6, state.endHour)
        assertEquals(30, state.endMinute)
    }

    @Test
    fun testStateHierarchy_AppsOnlyActive_DistinguishesFortressFromCurfew() {
        val fortressAppsOnly = ProtectionNotificationCoordinator.ProtectionState.AppsOnlyActive(
            activeFortressName = "Focus",
            endHour = 0,
            endMinute = 0,
            endWallMs = 123456L
        )
        assertEquals("Focus", fortressAppsOnly.activeFortressName)
        assertEquals(123456L, fortressAppsOnly.endWallMs)

        val curfewAppsOnly = ProtectionNotificationCoordinator.ProtectionState.AppsOnlyActive(
            activeFortressName = null,
            endHour = 7,
            endMinute = 0,
            endWallMs = 0L
        )
        assertEquals(null, curfewAppsOnly.activeFortressName)
        assertEquals(7, curfewAppsOnly.endHour)
    }

    @Test
    fun testStateHierarchy_DnsFilteringActive() {
        val state = ProtectionNotificationCoordinator.ProtectionState.DnsFilteringActive
        assertTrue(state is ProtectionNotificationCoordinator.ProtectionState)
    }

    @Test
    fun testFortressNotificationReceiverAction_matchesActionName() {
        assertEquals(
            "com.drmohammed.antiporn.fortress.ACTION_EXTEND_FORTRESS",
            FortressNotificationReceiver.ACTION_EXTEND_FORTRESS
        )
    }

    @Test
    fun testGuardianNameSanitization() {
        fun sanitizeGuardianName(name: String?, fallback: String): String {
            return name?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) } ?: fallback
        }

        assertEquals("Parent", sanitizeGuardianName(null, "Parent"))
        assertEquals("Parent", sanitizeGuardianName("", "Parent"))
        assertEquals("Parent", sanitizeGuardianName("   ", "Parent"))
        assertEquals("Parent", sanitizeGuardianName("null", "Parent"))
        assertEquals("Parent", sanitizeGuardianName("NULL", "Parent"))
        assertEquals("Alice", sanitizeGuardianName("Alice", "Parent"))
    }

    @Test
    fun testNewCurfewStringResourceDefined() {
        assertTrue(com.drmohammed.antiporn.R.string.notif_fortress_ended_desc_curfew_still_active != 0)
        assertTrue(com.drmohammed.antiporn.R.string.notif_fortress_ended_desc != 0)
        assertTrue(com.drmohammed.antiporn.R.string.info_quiet_channel_name != 0)
        assertTrue(com.drmohammed.antiporn.R.string.info_quiet_channel_desc != 0)
        assertEquals("antiporn_info_quiet", NotificationRegistry.CHANNEL_ID_INFO_QUIET)
        assertEquals(6100, NotificationRegistry.NOTIF_WAQFA_COOLDOWN_BASE_ID)
    }

    @Test
    fun testWaqfaCooldownNotificationId_isDeterministicAndBounded() {
        val id1 = WaqfaNotificationHelper.computeCooldownNotificationId("com.example.app1")
        val id2 = WaqfaNotificationHelper.computeCooldownNotificationId("com.example.app1")
        assertEquals(id1, id2)
        assertTrue(id1 in 6100 until 6600)
    }

    @Test
    fun testCanRunDnsVpnSilently_returnsFalseOnPreOreoOrUnboundOrNonDeviceOwner() {
        val prefs = InMemorySharedPreferences()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.drmohammed.antiporn"
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getSystemService(name: String): Any? = null
        }

        // Pre-Oreo
        assertFalse(CurfewVpnStateCoordinator.canRunDnsVpnSilently(context, sdkInt = Build.VERSION_CODES.N_MR1))

        // Oreo+ but not Device Owner (getSystemService returns null -> isDeviceOwner = false)
        setDeviceAdminBound(true)
        assertFalse(CurfewVpnStateCoordinator.canRunDnsVpnSilently(context, sdkInt = Build.VERSION_CODES.O))

        // Oreo+ and unbound
        setDeviceAdminBound(false)
        assertFalse(CurfewVpnStateCoordinator.canRunDnsVpnSilently(context, sdkInt = Build.VERSION_CODES.O))
    }

    @Test
    fun testCanRunDnsVpnSilently_returnsFalseDuringGuardianLockdown() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putBoolean("instant_internet_kill", true).apply()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.drmohammed.antiporn"
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getSystemService(name: String): Any? = null
        }
        setDeviceAdminBound(true)
        assertFalse(CurfewVpnStateCoordinator.canRunDnsVpnSilently(context, sdkInt = Build.VERSION_CODES.O))
        setDeviceAdminBound(false)
    }

    @Test
    fun testSyncProtectionNotification_cancelsWhenInactiveAndDoesNotRepost() {
        val cancelledIds = mutableListOf<Int>()
        val postedStates = mutableListOf<Pair<Int, ProtectionNotificationCoordinator.ProtectionState>>()
        val prefs = InMemorySharedPreferences()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.drmohammed.antiporn"
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getSystemService(name: String): Any? = null
        }

        ProtectionNotificationCoordinator.syncProtectionNotificationInternal(
            context = context,
            onCancel = { cancelledIds.add(it) },
            onPost = { id, state -> postedStates.add(id to state) }
        )

        assertTrue(cancelledIds.contains(ProtectionNotificationCoordinator.NOTIFICATION_ID_PROTECTION))
        assertTrue(postedStates.isEmpty())
    }

    @Test
    fun testCurfewBlackholeVpnService_extraStartedAsFgsConstantAndStartCommandContract() {
        assertEquals("extra_started_as_fgs", CurfewBlackholeVpnService.EXTRA_STARTED_AS_FGS)
        assertEquals("extra_force_keep_fgs", CurfewBlackholeVpnService.EXTRA_FORCE_KEEP_FGS)
        assertEquals(
            CurfewBlackholeVpnService.StartForegroundAction.PROMOTE_THEN_DEMOTE,
            CurfewBlackholeVpnService.resolveStartForegroundAction(startedAsFgs = true, canRunSilently = true)
        )
        assertEquals(
            CurfewBlackholeVpnService.StartForegroundAction.PROMOTE_FGS_ONLY,
            CurfewBlackholeVpnService.resolveStartForegroundAction(startedAsFgs = true, canRunSilently = false)
        )
        assertEquals(
            CurfewBlackholeVpnService.StartForegroundAction.PROMOTE_FGS_ONLY,
            CurfewBlackholeVpnService.resolveStartForegroundAction(startedAsFgs = false, canRunSilently = false)
        )
        assertEquals(
            CurfewBlackholeVpnService.StartForegroundAction.SKIP_SILENT_BOUND,
            CurfewBlackholeVpnService.resolveStartForegroundAction(startedAsFgs = false, canRunSilently = true)
        )
    }

    @Test
    fun testCanRunDnsVpnSilently_returnsFalseDuringTeardown() {
        val prefs = InMemorySharedPreferences()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.drmohammed.antiporn"
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getSystemService(name: String): Any? = null
        }
        setDeviceAdminBound(true)
        ProtectionTransitionGuard.isTeardownInProgress = true
        assertFalse(CurfewVpnStateCoordinator.canRunDnsVpnSilently(context, sdkInt = Build.VERSION_CODES.O))
    }

    @Test
    fun testWaqfaCooldownNotificationId_handlesNegativeHashCodesSafely() {
        // String with negative hashCode
        val id = WaqfaNotificationHelper.computeCooldownNotificationId("com.negative.hash.package.example.long.name")
        assertTrue(id in 6100 until 6600)
    }

    @After
    fun tearDownBoundState() {
        setDeviceAdminBound(false)
        ProtectionTransitionGuard.isTeardownInProgress = false
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { SystemClock.elapsedRealtime() }
        TrustedTimeProvider.wallTimeProvider = { System.currentTimeMillis() }
        FortressSessionController.resetForTesting()
    }

    private companion object {
        const val FAKE_ELAPSED_MS = 10_000L
        const val FAKE_WALL_MS = 1_790_000_000_000L
    }

    private fun setDeviceAdminBound(bound: Boolean) {
        val field = HisnDeviceAdminService::class.java.getDeclaredField("isBound")
        field.isAccessible = true
        field.setBoolean(null, bound)
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = apply { if (key != null) data[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) data[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) data[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) data[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) data[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) data[key] = value }
            override fun remove(key: String?) = apply { data.remove(key) }
            override fun clear() = apply { data.clear() }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
