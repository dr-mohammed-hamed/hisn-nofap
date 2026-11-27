package com.drmohammed.antiporn.fortress.engine

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.fortress.model.FortressProfile
import com.drmohammed.antiporn.fortress.model.FortressesConfig
import com.drmohammed.antiporn.testing.FakeTestApplicationContext
import com.drmohammed.antiporn.testing.FakeTestDevicePolicyDao
import com.drmohammed.antiporn.testing.FakeTestPartnerDao
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.gate.SelfControlSection
import com.drmohammed.antiporn.ui.control.qr.SelfControlQrManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class EffectiveRestrictionResolverTest {

    private lateinit var context: FakeTestApplicationContext
    private lateinit var repository: PolicyRepository
    private var fakeElapsedMs = 100_000L
    private var fakeWallMs = 1_790_000_000_000L

    @Before
    fun setUp() {
        resetSingletons()
        context = FakeTestApplicationContext()
        repository = PolicyRepository(context, FakeTestDevicePolicyDao(), FakeTestPartnerDao())
        PolicyRepository.setInstance(repository)

        TrustedTimeProvider.elapsedRealtimeProvider = { fakeElapsedMs }
        TrustedTimeProvider.wallTimeProvider = { fakeWallMs }
        TrustedTimeProvider.setAnchorForTesting(fakeWallMs, fakeElapsedMs)

        FortressSessionController.elapsedProvider = { fakeElapsedMs }
        FortressSessionController.wallProvider = { fakeWallMs }
        FortressSessionController.bootCountProvider = { 1 }
        CurfewAppResolver.elapsedRealtimeProvider = { fakeElapsedMs }
    }

    @After
    fun tearDown() {
        resetSingletons()
    }

    private fun resetSingletons() {
        PolicyRepository.setInstance(null)
        FortressSessionController.resetForTesting()
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { android.os.SystemClock.elapsedRealtime() }
        TrustedTimeProvider.wallTimeProvider = { System.currentTimeMillis() }
        CurfewAppResolver.elapsedRealtimeProvider = { android.os.SystemClock.elapsedRealtime() }
        CurfewAppResolver.invalidateCache()
        val qrInstanceField = SelfControlQrManager::class.java.getDeclaredField("instance")
        qrInstanceField.isAccessible = true
        qrInstanceField.set(null, null)
    }

    private fun calendarAt(hour: Int, minute: Int, dayOffset: Int = 0): Calendar {
        return Calendar.getInstance(TimeZone.getTimeZone(CurfewConfig.DEFAULT_TIMEZONE_ID)).apply {
            set(2026, Calendar.MAY, 13 + dayOffset, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun resolve_nightCurfewVsEmergencyVsCustomFortressInternetCut() {
        val studyCutOff = FortressProfile(
            id = "study_no_cut",
            name = "Study",
            durationMinutes = 45,
            isEmergency = false,
            isInternetCutEnabled = false,
            allowedPackages = setOf("com.xodo.pdf.reader", "com.whatsapp")
        )
        val studyCutOn = FortressProfile(
            id = "study_cut",
            name = "Deep Focus",
            durationMinutes = 45,
            isEmergency = false,
            isInternetCutEnabled = true,
            allowedPackages = setOf("com.xodo.pdf.reader", "com.whatsapp"),
            vpnBypassPackages = setOf("com.whatsapp")
        )
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            isCurfewEnabled = true,
            curfewStartHour = 20,
            curfewStartMinute = 0,
            curfewEndHour = 7,
            curfewEndMinute = 0,
            isVpnBlackholeEnabled = true,
            customCurfewWhitelistPackages = setOf("com.whatsapp", "com.islamicapp.moshafy"),
            curfewVpnBypassPackages = setOf("com.whatsapp"),
            fortressesConfig = FortressesConfig.default().upsert(studyCutOff).upsert(studyCutOn)
        )
        repository.updateLocalPolicyWithoutEnforcing(policy)

        val nightCal = calendarAt(22, 0)
        val dayCal = calendarAt(14, 0)
        fakeWallMs = nightCal.timeInMillis
        TrustedTimeProvider.setAnchorForTesting(fakeWallMs, fakeElapsedMs)

        val nightOnly = EffectiveRestrictionResolver.resolve(context, nightCal, policy)
        assertTrue(nightOnly.isBlockActive)
        assertTrue(nightOnly.shouldCutInternet)
        assertTrue(nightOnly.isNightCurfewActive)
        assertNull(nightOnly.activeFortress)
        assertEquals(setOf("com.whatsapp", "com.islamicapp.moshafy"), nightOnly.effectiveAllowedUserApps)
        assertEquals(setOf("com.whatsapp", context.packageName), nightOnly.effectiveVpnBypassPackages)

        fakeWallMs = dayCal.timeInMillis
        TrustedTimeProvider.setAnchorForTesting(fakeWallMs, fakeElapsedMs)
        FortressSessionController.startFortress(context, studyCutOff)
        val customNoCut = EffectiveRestrictionResolver.resolve(context, dayCal, policy)
        assertTrue(customNoCut.isBlockActive)
        assertFalse(customNoCut.shouldCutInternet)
        assertEquals(setOf("com.xodo.pdf.reader", "com.whatsapp"), customNoCut.effectiveAllowedUserApps)
        assertEquals(setOf(context.packageName), customNoCut.effectiveVpnBypassPackages)

        FortressSessionController.clearForTeardown(context)
        FortressSessionController.startFortress(context, studyCutOn)
        val customWithCut = EffectiveRestrictionResolver.resolve(context, dayCal, policy)
        assertTrue(customWithCut.isBlockActive)
        assertTrue(customWithCut.shouldCutInternet)
        assertEquals(setOf("com.whatsapp", context.packageName), customWithCut.effectiveVpnBypassPackages)

        FortressSessionController.clearForTeardown(context)
        FortressSessionController.startFortress(context, FortressProfile.defaultEmergency())
        val emergencyOnly = EffectiveRestrictionResolver.resolve(context, dayCal, policy)
        assertTrue(emergencyOnly.isBlockActive)
        assertTrue(emergencyOnly.shouldCutInternet)
        assertTrue(emergencyOnly.effectiveAllowedUserApps.isEmpty())
        assertEquals(setOf(context.packageName), emergencyOnly.effectiveVpnBypassPackages)
    }

    @Test
    fun resolve_nightCurfewPlusFortress_enforcesStrictestWinsIntersection() {
        val customFortress = FortressProfile(
            id = "focus",
            name = "Focus",
            durationMinutes = 60,
            isEmergency = false,
            isInternetCutEnabled = false,
            allowedPackages = setOf("com.whatsapp", "com.xodo.pdf.reader", "com.android.chrome")
        )
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            isCurfewEnabled = true,
            curfewStartHour = 20,
            curfewStartMinute = 0,
            curfewEndHour = 7,
            curfewEndMinute = 0,
            isVpnBlackholeEnabled = true,
            customCurfewWhitelistPackages = setOf("com.whatsapp", "com.islamicapp.moshafy", "com.android.chrome"),
            curfewVpnBypassPackages = setOf("com.whatsapp", "com.android.chrome"),
            customBlacklistPackages = setOf("com.blocked.app"),
            fortressesConfig = FortressesConfig.default().upsert(customFortress)
        )
        repository.updateLocalPolicyWithoutEnforcing(policy)

        val nightCal = calendarAt(23, 0)
        fakeWallMs = nightCal.timeInMillis
        TrustedTimeProvider.setAnchorForTesting(fakeWallMs, fakeElapsedMs)
        FortressSessionController.startFortress(context, customFortress)

        val mergedCustom = EffectiveRestrictionResolver.resolve(context, nightCal, policy)
        assertTrue(mergedCustom.isNightCurfewActive)
        assertNotNull(mergedCustom.activeFortress)
        assertTrue(mergedCustom.shouldCutInternet)
        assertEquals(setOf("com.whatsapp"), mergedCustom.effectiveAllowedUserApps)
        assertEquals(setOf("com.whatsapp", context.packageName), mergedCustom.effectiveVpnBypassPackages)

        // Adding Emergency Fortress kills all allowed apps and restricts VPN bypass to self only
        FortressSessionController.clearForTeardown(context)
        FortressSessionController.startFortress(context, FortressProfile.defaultEmergency())
        val mergedEmergency = EffectiveRestrictionResolver.resolve(context, nightCal, policy)
        assertTrue(mergedEmergency.shouldCutInternet)
        assertTrue(mergedEmergency.effectiveAllowedUserApps.isEmpty())
        assertEquals(setOf(context.packageName), mergedEmergency.effectiveVpnBypassPackages)
    }

    @Test
    fun curfewWindowOverride_unlocksCurrentWindowOnlyWithoutAffectingNextNightOrFortress() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            isCurfewEnabled = true,
            curfewStartHour = 20,
            curfewStartMinute = 0,
            curfewEndHour = 7,
            curfewEndMinute = 0,
            isSupervised = false,
            customCurfewWhitelistPackages = setOf("com.whatsapp")
        )
        repository.updateLocalPolicyWithoutEnforcing(policy)

        val night1 = calendarAt(22, 0, dayOffset = 0)
        fakeWallMs = night1.timeInMillis
        TrustedTimeProvider.setAnchorForTesting(fakeWallMs, fakeElapsedMs)

        FortressSessionController.startFortress(context, FortressProfile.defaultEmergency())
        assertTrue(CurfewEnforcer.unlockCurfewForCurrentWindowDirect(context))

        // Curfew window overridden, but active Fortress keeps block ON
        val duringOverrideWithFortress = EffectiveRestrictionResolver.resolve(context, night1, policy)
        assertFalse(duringOverrideWithFortress.isNightCurfewActive)
        assertTrue(duringOverrideWithFortress.isBlockActive)
        assertNotNull(duringOverrideWithFortress.activeFortress)

        // Once Fortress ends, Night 1 remains unlocked until 07:00
        FortressSessionController.clearForTeardown(context)
        val night1Later = calendarAt(2, 0, dayOffset = 1)
        fakeWallMs = night1Later.timeInMillis
        TrustedTimeProvider.setAnchorForTesting(fakeWallMs, fakeElapsedMs)
        assertFalse(EffectiveRestrictionResolver.resolve(context, night1Later, policy).isNightCurfewActive)

        // Subsequent night window reactivates automatically once windowEndWallMs passes
        val night2 = calendarAt(21, 0, dayOffset = 1)
        fakeWallMs = night2.timeInMillis
        TrustedTimeProvider.setAnchorForTesting(fakeWallMs, fakeElapsedMs)
        assertTrue(EffectiveRestrictionResolver.resolve(context, night2, policy).isNightCurfewActive)
    }

    @Test
    fun fortressUnlockGates_enforceEmergencyQrCustomDirectAndGuardianPermissions() {
        val custom = FortressProfile(id = "custom", name = "Custom", durationMinutes = 30)
        val qrManager = SelfControlQrManager.getInstance(context)
        val qrToken = qrManager.generateAndSaveNewToken()

        // 1. Standalone with QR OFF: Emergency rejects direct, Custom allows direct
        repository.updateLocalPolicyWithoutEnforcing(DevicePolicyBundle.defaultPolicy())
        qrManager.setQrLockEnabled(false)

        FortressSessionController.startFortress(context, FortressProfile.defaultEmergency())
        assertFalse(FortressSessionController.unlockActiveFortressDirectWhenAllowed(context))
        assertTrue(FortressSessionController.isFortressActive(context))

        FortressSessionController.clearForTeardown(context)
        FortressSessionController.startFortress(context, custom)
        assertTrue(FortressSessionController.unlockActiveFortressDirectWhenAllowed(context))
        assertFalse(FortressSessionController.isFortressActive(context))

        // 2. QR ON: Custom rejects direct, allows valid QR; Emergency allows valid QR
        qrManager.setQrLockEnabled(true)
        FortressSessionController.startFortress(context, custom)
        assertFalse(FortressSessionController.unlockActiveFortressDirectWhenAllowed(context))
        assertFalse(FortressSessionController.unlockActiveFortressWithQrToken(context, "bad_token"))
        assertTrue(FortressSessionController.unlockActiveFortressWithQrToken(context, qrToken))

        FortressSessionController.startFortress(context, FortressProfile.defaultEmergency())
        assertTrue(FortressSessionController.unlockActiveFortressWithQrToken(context, qrToken))

        // 3. Supervised with selfControlFortressUnlockAllowed == false: rejects even valid QR and direct
        repository.updateLocalPolicyWithoutEnforcing(
            DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = true,
                guardianUid = "guardian-1",
                isSelfControlAllowed = true,
                selfControlFortressUnlockAllowed = false
            )
        )
        FortressSessionController.startFortress(context, custom)
        assertFalse(FortressSessionController.unlockActiveFortressWithQrToken(context, qrToken))
        qrManager.setQrLockEnabled(false)
        assertFalse(FortressSessionController.unlockActiveFortressDirectWhenAllowed(context))
        assertTrue(FortressSessionController.isFortressActive(context))
    }

    @Test
    fun curfewAppResolver_grantsEssentialOnlyImmunityWithoutAutoExemptingBrowsersMediaOrReadingApps() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            customCurfewWhitelistPackages = emptySet(),
            customBlacklistPackages = setOf("com.custom.blacklisted")
        )
        repository.updateLocalPolicyWithoutEnforcing(policy)

        val essentialOnly = CurfewAppResolver.resolveEssentialImmunePackages(context, forceRefresh = true)
        assertTrue(essentialOnly.contains(context.packageName))
        assertTrue(essentialOnly.contains("com.google.android.deskclock"))
        assertTrue(essentialOnly.contains("com.android.settings"))
        assertFalse(essentialOnly.contains("com.google.android.apps.photos"))
        assertFalse(essentialOnly.contains("com.android.vending"))
        assertFalse(essentialOnly.contains("com.google.android.apps.searchlite"))
        assertFalse(essentialOnly.contains("com.custom.blacklisted"))

        val nonEssentialApps = listOf(
            "com.android.chrome",
            "com.google.android.youtube",
            "org.schabi.newpipe",
            "org.schabi.newpipe.debug",
            "com.mxtech.videoplayer.ad",
            "in.krosbits.musicolet",
            "com.xodo.pdf.reader",
            "com.islamicapp.moshafy"
        )
        val nightNoWhitelist = EffectiveRestrictionResolver.resolve(context, calendarAt(22, 0), policy)
        val nightImmune = CurfewAppResolver.resolveImmunePackages(context, nightNoWhitelist, forceRefresh = true)
        for (pkg in nonEssentialApps) {
            assertFalse("Essential immunity must not include $pkg", essentialOnly.contains(pkg))
            assertFalse("Night curfew must not auto-exempt $pkg unless whitelisted", nightImmune.contains(pkg))
        }

        val customFortress = FortressProfile(
            id = "study",
            name = "Study",
            durationMinutes = 60,
            isInternetCutEnabled = false,
            allowedPackages = setOf("com.xodo.pdf.reader")
        )
        val fortressPolicy = policy.copy(fortressesConfig = FortressesConfig.default().upsert(customFortress))
        repository.updateLocalPolicyWithoutEnforcing(fortressPolicy)
        FortressSessionController.startFortress(context, customFortress)

        val fortressProfile = EffectiveRestrictionResolver.resolve(context, calendarAt(14, 0), fortressPolicy)
        val fortressImmune = CurfewAppResolver.resolveImmunePackages(context, fortressProfile, forceRefresh = true)
        assertTrue(fortressImmune.contains("com.xodo.pdf.reader"))
        assertFalse(fortressImmune.contains("org.schabi.newpipe"))
        assertFalse(fortressImmune.contains("com.android.chrome"))
    }

    @Test
    fun curfewWindowUnlockWithQrAndSectionGates_respectGuardianPermissions() {
        val nightWallMs = calendarAt(23, 0).timeInMillis
        TrustedTimeProvider.wallTimeProvider = { nightWallMs }
        TrustedTimeProvider.setAnchorForTesting(nightWallMs, fakeElapsedMs)

        val policy = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            isSelfControlAllowed = true,
            selfControlFortressesAllowed = false,
            selfControlFortressUnlockAllowed = false,
            isCurfewEnabled = true,
            curfewStartHour = 21,
            curfewStartMinute = 0,
            curfewEndHour = 7,
            curfewEndMinute = 0,
            timeZoneId = CurfewConfig.DEFAULT_TIMEZONE_ID
        )
        repository.updateLocalPolicyWithoutEnforcing(policy)

        assertFalse(SelfControlAccessGateRegistry.isSectionPermitted(context, SelfControlSection.FORTRESSES))
        assertFalse(SelfControlAccessGateRegistry.isSectionPermitted(context, SelfControlSection.FORTRESS_UNLOCK))
        assertFalse(CurfewEnforcer.unlockCurfewForCurrentWindowWithQr(context, "ANY_TOKEN"))

        val allowedPolicy = policy.copy(
            selfControlFortressesAllowed = true,
            selfControlFortressUnlockAllowed = true
        )
        repository.updateLocalPolicyWithoutEnforcing(allowedPolicy)
        assertTrue(SelfControlAccessGateRegistry.isSectionPermitted(context, SelfControlSection.FORTRESSES))
        assertTrue(SelfControlAccessGateRegistry.isSectionPermitted(context, SelfControlSection.FORTRESS_UNLOCK))

        val qrPrefs = context.getSharedPreferences(SelfControlQrManager.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest("CURFEW_QR_OK".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        qrPrefs.edit()
            .putBoolean(SelfControlQrManager.KEY_QR_LOCK_ENABLED, true)
            .putString(SelfControlQrManager.KEY_SAVED_TOKEN_HASH, sha256)
            .commit()

        assertFalse(CurfewEnforcer.unlockCurfewForCurrentWindowWithQr(context, "BAD"))
        assertTrue(CurfewEnforcer.unlockCurfewForCurrentWindowWithQr(context, "CURFEW_QR_OK"))
        assertTrue(CurfewWindowOverrideStore.isWindowOverridden(context, nightWallMs))
    }
}
