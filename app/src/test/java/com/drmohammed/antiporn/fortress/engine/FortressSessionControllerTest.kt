package com.drmohammed.antiporn.fortress.engine

import android.content.Context
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.fortress.model.FortressProfile
import com.drmohammed.antiporn.testing.FakeTestApplicationContext
import com.drmohammed.antiporn.testing.FakeTestDevicePolicyDao
import com.drmohammed.antiporn.testing.FakeTestPartnerDao
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.qr.SelfControlQrManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FortressSessionControllerTest {

    private lateinit var context: FakeTestApplicationContext
    private var fakeElapsedMs = 10_000L
    private var fakeWallMs = 1_790_000_000_000L
    private var fakeBootCount = 3

    @Before
    fun setUp() {
        FortressSessionController.resetForTesting()
        context = FakeTestApplicationContext()
        FortressSessionController.elapsedProvider = { fakeElapsedMs }
        FortressSessionController.wallProvider = { fakeWallMs }
        FortressSessionController.bootCountProvider = { fakeBootCount }
    }

    @After
    fun tearDown() {
        PolicyRepository.setInstance(null)
        SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = true
        val qrInstanceField = SelfControlQrManager::class.java.getDeclaredField("instance")
        qrInstanceField.isAccessible = true
        qrInstanceField.set(null, null)
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { android.os.SystemClock.elapsedRealtime() }
        TrustedTimeProvider.wallTimeProvider = { System.currentTimeMillis() }
        FortressSessionController.resetForTesting()
    }

    @Test
    fun startFortress_enforcesSingleActiveSessionAndIsIdempotentForSameId() {
        val emergency = FortressProfile.defaultEmergency()
        val study = FortressProfile(id = "study", name = "Study", durationMinutes = 45)

        val firstEnd = FortressSessionController.startFortress(context, emergency)
        assertTrue(firstEnd > 0L)
        assertEquals(firstEnd, FortressSessionController.startFortress(context, emergency))

        val secondStartResult = FortressSessionController.startFortress(context, study)
        assertEquals(0L, secondStartResult)
        assertEquals(FortressProfile.EMERGENCY_FORTRESS_ID, FortressSessionController.getActiveSession(context)?.fortressId)
    }

    @Test
    fun extendActiveFortress_extendsByActiveProfileDurationAndReturnsZeroWhenIdle() {
        assertEquals(0L, FortressSessionController.extendActiveFortress(context))

        val custom = FortressProfile(id = "reading", name = "Reading", durationMinutes = 45)
        val initialEnd = FortressSessionController.startFortress(context, custom)
        assertEquals(fakeElapsedMs + 45 * 60_000L, initialEnd)

        fakeElapsedMs += 5 * 60_000L
        fakeWallMs += 5 * 60_000L
        val extendedEnd = FortressSessionController.extendActiveFortress(context)
        assertEquals(initialEnd + 45 * 60_000L, extendedEnd)
        assertEquals(85 * 60_000L, FortressSessionController.remainingMs(context))
    }

    @Test
    fun migrateLegacyNapSessionIfNeeded_migratesOnceAndMarksMigrated() {
        val legacyPrefs = context.getSharedPreferences("antiporn_nap_prefs", Context.MODE_PRIVATE)
        val napEndElapsed = fakeElapsedMs + 3_600_000L
        val napEndWall = fakeWallMs + 3_600_000L
        legacyPrefs.edit()
            .putBoolean("key_nap_active", true)
            .putLong("key_nap_start_elapsed", fakeElapsedMs)
            .putLong("key_nap_end_elapsed", napEndElapsed)
            .putLong("key_nap_end_wall_ms", napEndWall)
            .putInt("key_nap_boot_count", fakeBootCount)
            .commit()

        val migrated = FortressSessionController.getActiveSession(context)
        assertNotNull(migrated)
        assertEquals(FortressProfile.EMERGENCY_FORTRESS_ID, migrated?.fortressId)
        assertTrue(migrated?.isEmergency ?: false)
        assertEquals(napEndWall, migrated?.endWallMs)

        assertFalse(legacyPrefs.getBoolean("key_nap_active", true))
        assertTrue(legacyPrefs.getBoolean("key_nap_migrated_to_fortress", false))

        // Clearing the migrated session must NOT re-trigger migration from legacy prefs
        FortressSessionController.clearForTeardown(context)
        FortressSessionController.resetForTesting()
        FortressSessionController.elapsedProvider = { fakeElapsedMs }
        FortressSessionController.wallProvider = { fakeWallMs }
        FortressSessionController.bootCountProvider = { fakeBootCount }
        assertNull(FortressSessionController.getActiveSession(context))
    }

    @Test
    fun bootCountMismatchAndClockRollback_preservesUnexpiredAndExpiresElapsed() {
        val emergency = FortressProfile.defaultEmergency()
        FortressSessionController.startFortress(context, emergency)

        // Wall-clock rollback while monotonic clock is still inside window -> stays active
        fakeWallMs -= 30 * 60_000L
        assertTrue(FortressSessionController.isFortressActive(context))

        // Reboot (bootCount increments) while wall clock is still inside window -> re-anchors elapsed and stays active
        fakeWallMs += 40 * 60_000L // 10 min after start
        fakeBootCount = 4
        fakeElapsedMs = 5_000L
        val sessionAfterReboot = FortressSessionController.getActiveSession(context)
        assertNotNull(sessionAfterReboot)
        assertEquals(50 * 60_000L, sessionAfterReboot?.remainingMs)

        // Reboot after wall clock passed endWall -> expires and clears synchronously
        fakeBootCount = 5
        fakeElapsedMs = 2_000L
        fakeWallMs += 60 * 60_000L
        assertFalse(FortressSessionController.isFortressActive(context))
        assertNull(FortressSessionController.activeSessionFlow.value)
    }

    @Test
    fun clearForTeardown_clearsSessionSynchronously() {
        val custom = FortressProfile(id = "focus", name = "Focus", durationMinutes = 30)
        FortressSessionController.startFortress(context, custom)
        assertTrue(FortressSessionController.isFortressActive(context))
        assertNotNull(FortressSessionController.activeSessionFlow.value)

        FortressSessionController.clearForTeardown(context)

        assertFalse(FortressSessionController.isFortressActive(context))
        assertNull(FortressSessionController.activeSessionFlow.value)
        assertEquals(0L, FortressSessionController.remainingMs(context))
        val prefs = context.getSharedPreferences(FortressSessionPrefsStore.PREFS_NAME, Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean(FortressSessionPrefsStore.KEY_ACTIVE, true))
        assertFalse(prefs.contains(FortressSessionPrefsStore.KEY_FORTRESS_ID))
    }

    @Test
    fun bootCountUnavailable_rebootPastEndWallWhenElapsedExceedsStartElapsed_expiresSession() {
        fakeBootCount = -1
        fakeElapsedMs = 15_000L
        val custom = FortressProfile(id = "study", name = "Study", durationMinutes = 60)
        FortressSessionController.startFortress(context, custom)

        // Simulate device powered off for 2 hours and booted again; elapsedNow (20_000L) > startElapsed (15_000L)
        fakeElapsedMs = 20_000L
        fakeWallMs += 2 * 60 * 60_000L

        assertFalse(FortressSessionController.isFortressActive(context))
        assertNull(FortressSessionController.activeSessionFlow.value)
    }

    @Test
    fun unlockPathways_enforceEmergencyQrAndGuardianPermissions() {
        TrustedTimeProvider.elapsedRealtimeProvider = { fakeElapsedMs }
        TrustedTimeProvider.wallTimeProvider = { fakeWallMs }
        TrustedTimeProvider.setAnchorForTesting(fakeWallMs, fakeElapsedMs)
        val repo = PolicyRepository(context, FakeTestDevicePolicyDao(), FakeTestPartnerDao())
        PolicyRepository.setInstance(repo)

        // Emergency fortress cannot be unlocked directly even without QR or supervision
        FortressSessionController.startFortress(context, FortressProfile.defaultEmergency())
        assertFalse(FortressSessionController.unlockActiveFortressDirectWhenAllowed(context))
        assertTrue(FortressSessionController.unlockActiveFortressByGuardian(context))
        assertFalse(FortressSessionController.unlockActiveFortressByGuardian(context))

        // Custom fortress direct unlock fails when Guardian denies FORTRESS_UNLOCK
        repo.updateLocalPolicyWithoutEnforcing(
            DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = true,
                guardianUid = "g1",
                isSelfControlAllowed = true,
                selfControlFortressUnlockAllowed = false
            )
        )
        val custom = FortressProfile(id = "focus", name = "Focus", durationMinutes = 30)
        FortressSessionController.startFortress(context, custom)
        assertFalse(FortressSessionController.unlockActiveFortressDirectWhenAllowed(context))

        // Allowed when Guardian grants FORTRESS_UNLOCK and QR lock is off
        repo.updateLocalPolicyWithoutEnforcing(
            DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = true,
                guardianUid = "g1",
                isSelfControlAllowed = true,
                selfControlFortressUnlockAllowed = true
            )
        )
        assertTrue(FortressSessionController.unlockActiveFortressDirectWhenAllowed(context))
        assertFalse(FortressSessionController.isFortressActive(context))

        // With QR lock enabled, direct and emergency bypass fail; valid token succeeds
        val qrManager = SelfControlQrManager.getInstance(context)
        val qrPrefs = context.getSharedPreferences(SelfControlQrManager.PREFS_NAME, Context.MODE_PRIVATE)
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest("VALID_QR_TOKEN".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        qrPrefs.edit()
            .putBoolean(SelfControlQrManager.KEY_QR_LOCK_ENABLED, true)
            .putString(SelfControlQrManager.KEY_SAVED_TOKEN_HASH, sha256)
            .commit()

        FortressSessionController.startFortress(context, custom)
        assertFalse(FortressSessionController.unlockActiveFortressDirectWhenAllowed(context))
        assertFalse(FortressSessionController.unlockActiveFortressViaEmergencyBypass(context))
        assertFalse(FortressSessionController.unlockActiveFortressWithQrToken(context, "WRONG_TOKEN"))
        assertTrue(FortressSessionController.unlockActiveFortressWithQrToken(context, "VALID_QR_TOKEN"))
        assertFalse(FortressSessionController.isFortressActive(context))

        // Emergency bypass succeeds once QR lock is disabled
        qrManager.setQrLockEnabled(false)
        FortressSessionController.startFortress(context, FortressProfile.defaultEmergency())
        assertTrue(FortressSessionController.unlockActiveFortressViaEmergencyBypass(context))
        assertFalse(FortressSessionController.isFortressActive(context))
    }

    @Test
    fun getActiveSession_doesNotEmitFlowOnReadsDuringActiveSession() {
        val study = FortressProfile(id = "study", name = "Study", durationMinutes = 60)
        FortressSessionController.startFortress(context, study)
        val initialEmitted = FortressSessionController.activeSessionFlow.value
        assertNotNull(initialEmitted)

        // Advance clock while still active and call getActiveSession repeatedly (like RecyclerView bind)
        fakeElapsedMs += 5_000L
        fakeWallMs += 5_000L
        val read1 = FortressSessionController.getActiveSession(context)
        assertNotNull(read1)
        assertEquals(60 * 60_000L - 5_000L, read1?.remainingMs)
        // activeSessionFlow must keep the exact same reference so collectors don't re-enter layout
        assertTrue(FortressSessionController.activeSessionFlow.value === initialEmitted)

        // State transitions (extend / unlock) DO update activeSessionFlow
        FortressSessionController.extendActiveFortress(context)
        val afterExtend = FortressSessionController.activeSessionFlow.value
        assertNotNull(afterExtend)
        assertFalse(afterExtend === initialEmitted)

        assertTrue(FortressSessionController.unlockActiveFortressDirectWhenAllowed(context))
        assertNull(FortressSessionController.activeSessionFlow.value)
    }
}
