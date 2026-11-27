package com.drmohammed.antiporn.data.repository

import android.content.Context
import android.os.SystemClock
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.fortress.engine.CurfewWindowOverrideStore
import com.drmohammed.antiporn.fortress.engine.FortressSessionController
import com.drmohammed.antiporn.fortress.model.FortressProfile
import com.drmohammed.antiporn.testing.FakeTestApplicationContext
import com.drmohammed.antiporn.testing.FakeTestDevicePolicyDao
import com.drmohammed.antiporn.testing.FakeTestPartnerDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RemoteModeUnlockHandlerTest {

    private lateinit var context: FakeTestApplicationContext
    private var fakeElapsedMs = 50_000L
    private var fakeWallMs = 0L

    @Before
    fun setUp() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(CurfewConfig.DEFAULT_TIMEZONE_ID)).apply {
            set(2026, Calendar.MAY, 13, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        fakeWallMs = cal.timeInMillis

        PolicyRepository.setInstance(null)
        FortressSessionController.resetForTesting()
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { fakeElapsedMs }
        TrustedTimeProvider.wallTimeProvider = { fakeWallMs }
        TrustedTimeProvider.setAnchorForTesting(fakeWallMs, fakeElapsedMs)

        FortressSessionController.elapsedProvider = { fakeElapsedMs }
        FortressSessionController.wallProvider = { fakeWallMs }
        FortressSessionController.bootCountProvider = { 1 }
        CurfewAppResolver.elapsedRealtimeProvider = { fakeElapsedMs }
        CurfewAppResolver.invalidateCache()

        context = FakeTestApplicationContext()
        PolicyRepository.setInstance(
            PolicyRepository(context, FakeTestDevicePolicyDao(), FakeTestPartnerDao())
        )
    }

    @After
    fun tearDown() {
        PolicyRepository.setInstance(null)
        FortressSessionController.resetForTesting()
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { SystemClock.elapsedRealtime() }
        TrustedTimeProvider.wallTimeProvider = { System.currentTimeMillis() }
        CurfewAppResolver.elapsedRealtimeProvider = { SystemClock.elapsedRealtime() }
        CurfewAppResolver.invalidateCache()
    }

    private fun supervisedCurfewPolicy(
        target: String?,
        requestedAt: Long?,
        isSupervised: Boolean = true,
        guardianUid: String? = "guardian-1"
    ): DevicePolicyBundle = DevicePolicyBundle.defaultPolicy().copy(
        isSupervised = isSupervised,
        guardianUid = guardianUid,
        isCurfewEnabled = true,
        curfewStartHour = 21,
        curfewStartMinute = 0,
        curfewEndHour = 7,
        curfewEndMinute = 0,
        timeZoneId = CurfewConfig.DEFAULT_TIMEZONE_ID,
        remoteModeUnlockTarget = target,
        remoteModeUnlockRequestedAt = requestedAt
    )

    @Test
    fun checkAndApplyRemoteModeUnlock_ignoresUnsupervisedAndUnknownTarget() {
        FortressSessionController.startFortress(context, FortressProfile.defaultEmergency())
        val prefs = context.getSharedPreferences(PolicyRepository.PREFS_NAME, Context.MODE_PRIVATE)

        RemoteModeUnlockHandler.checkAndApplyRemoteModeUnlock(
            context,
            supervisedCurfewPolicy(
                target = DevicePolicyBundle.REMOTE_MODE_UNLOCK_ALL,
                requestedAt = 100L,
                isSupervised = false
            )
        )
        assertTrue(FortressSessionController.isFortressActive(context))
        assertEquals(0L, prefs.getLong(PolicyRepository.KEY_LAST_PROCESSED_REMOTE_MODE_UNLOCK_AT, 0L))

        RemoteModeUnlockHandler.checkAndApplyRemoteModeUnlock(
            context,
            supervisedCurfewPolicy(
                target = "UNKNOWN_TARGET",
                requestedAt = 200L
            )
        )
        assertTrue(FortressSessionController.isFortressActive(context))
        assertEquals(0L, prefs.getLong(PolicyRepository.KEY_LAST_PROCESSED_REMOTE_MODE_UNLOCK_AT, 0L))
    }

    @Test
    fun checkAndApplyRemoteModeUnlock_fortressOnlyAndIdempotentReplay() {
        FortressSessionController.startFortress(context, FortressProfile.defaultEmergency())
        assertTrue(FortressSessionController.isFortressActive(context))

        val unlockCmd = supervisedCurfewPolicy(
            target = DevicePolicyBundle.REMOTE_MODE_UNLOCK_FORTRESS,
            requestedAt = 1_000L
        )
        RemoteModeUnlockHandler.checkAndApplyRemoteModeUnlock(context, unlockCmd)

        assertFalse(FortressSessionController.isFortressActive(context))
        assertFalse(CurfewWindowOverrideStore.isWindowOverridden(context, fakeWallMs))
        val prefs = context.getSharedPreferences(PolicyRepository.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(1_000L, prefs.getLong(PolicyRepository.KEY_LAST_PROCESSED_REMOTE_MODE_UNLOCK_AT, 0L))

        // Starting a new fortress later and replaying the same requestedAt must NOT unlock it
        FortressSessionController.startFortress(context, FortressProfile.defaultEmergency())
        RemoteModeUnlockHandler.checkAndApplyRemoteModeUnlock(context, unlockCmd)
        assertTrue(FortressSessionController.isFortressActive(context))
    }

    @Test
    fun checkAndApplyRemoteModeUnlock_curfewAndAllTargets() {
        FortressSessionController.startFortress(context, FortressProfile.defaultEmergency())
        context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(CurfewConfig.KEY_MANUAL_TEST_ACTIVE, true).commit()

        RemoteModeUnlockHandler.checkAndApplyRemoteModeUnlock(
            context,
            supervisedCurfewPolicy(
                target = DevicePolicyBundle.REMOTE_MODE_UNLOCK_CURFEW,
                requestedAt = 2_000L
            )
        )
        assertTrue(CurfewWindowOverrideStore.isWindowOverridden(context, fakeWallMs))
        assertFalse(CurfewEnforcer.isManualTestActive(context))
        assertTrue(FortressSessionController.isFortressActive(context))

        CurfewWindowOverrideStore.clearOverride(context)
        RemoteModeUnlockHandler.checkAndApplyRemoteModeUnlock(
            context,
            supervisedCurfewPolicy(
                target = DevicePolicyBundle.REMOTE_MODE_UNLOCK_ALL,
                requestedAt = 3_000L
            )
        )
        assertFalse(FortressSessionController.isFortressActive(context))
        assertTrue(CurfewWindowOverrideStore.isWindowOverridden(context, fakeWallMs))
    }
}
