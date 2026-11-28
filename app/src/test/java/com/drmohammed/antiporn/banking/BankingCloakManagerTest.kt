package com.drmohammed.antiporn.banking

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ChangedPackages
import android.content.pm.FeatureInfo
import android.content.pm.InstrumentationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.SharedLibraryInfo
import android.content.pm.VersionedPackage
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.TestUri
import android.net.Uri
import android.os.SystemClock
import android.os.UserHandle
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BankingCloakManagerTest {

    private lateinit var dummyContext: DummyBankingContext
    private var fakeElapsedMs = 10_000L
    private var fakeWallMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        fakeElapsedMs = 10_000L
        fakeWallMs = 1_700_000_000_000L
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { fakeElapsedMs }
        TrustedTimeProvider.wallTimeProvider = { fakeWallMs }
        dummyContext = DummyBankingContext()
        BankingCloakManager.resetForTesting()
        AccessibilitySelfActivator.enableSilently(dummyContext, forceUncloak = true)
    }

    @After
    fun tearDown() {
        com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.isTeardownInProgress = false
        BankingCloakManager.resetForTesting()
        AccessibilitySelfActivator.enableSilently(dummyContext, forceUncloak = true)
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { SystemClock.elapsedRealtime() }
        TrustedTimeProvider.wallTimeProvider = { System.currentTimeMillis() }
    }

    @Test
    fun testConstants() {
        assertEquals("com.egyptianbanks.instapay", BankingCloakManager.INSTAPAY_PACKAGE)
        assertEquals(180_000L, BankingCloakManager.CLOAK_TIMEOUT_MS)
        assertEquals(250L, BankingCloakManager.DEFAULT_SETTLING_DELAY_MS)
        assertTrue(BankingCloakManager.MIN_CLOAK_SETTLING_DELAY_MS >= 350L)
        assertEquals("com.drmohammed.antiporn.action.RESTORE_BANKING_CLOAK", BankingCloakManager.ACTION_RESTORE_BANKING_CLOAK)
    }

    @Test
    fun testCloakStateAndActiveTargetPackage_surviveSimulatedProcessDeath() {
        val cibPkg = "com.cib.cibmobilebanking"
        val prefs = InMemorySharedPreferences()
        val persistentContext = DummyBankingContext(sharedPrefs = prefs).apply {
            installedPackages.add(cibPkg)
        }

        // Launch CIB (non-InstaPay bank) -> persists cloak state and target package
        val launched = BankingCloakManager.launchSafely(persistentContext, cibPkg)
        assertTrue(launched)
        assertTrue(BankingCloakManager.isCloaked(persistentContext))
        assertEquals(cibPkg, BankingCloakManager.activeTargetPackage)

        // Simulate process death by resetting in-memory volatile fields without clearing SharedPreferences
        val cloakField = AccessibilitySelfActivator::class.java.getDeclaredField("isTemporarilyCloaked")
        cloakField.isAccessible = true
        cloakField.setBoolean(AccessibilitySelfActivator, false)
        val expiryField = AccessibilitySelfActivator::class.java.getDeclaredField("cloakExpiresAtMs")
        expiryField.isAccessible = true
        expiryField.setLong(AccessibilitySelfActivator, 0L)
        val targetField = BankingCloakManager::class.java.getDeclaredField("activeTargetPackage")
        targetField.isAccessible = true
        targetField.set(BankingCloakManager, BankingCloakManager.INSTAPAY_PACKAGE)

        // After process restart, isCloaked(context) must recover from SharedPreferences
        assertTrue(BankingCloakManager.isCloaked(persistentContext))

        // Staying inside CIB after process restart must NOT uncloak
        BankingCloakManager.onForegroundPackageChanged(persistentContext, cibPkg)
        assertTrue(BankingCloakManager.isCloaked(persistentContext))
        assertEquals(cibPkg, BankingCloakManager.activeTargetPackage)

        // Leaving CIB to Chrome must uncloak and clear persisted state
        BankingCloakManager.onForegroundPackageChanged(persistentContext, "com.android.chrome")
        assertFalse(BankingCloakManager.isCloaked(persistentContext))
        assertEquals(BankingCloakManager.INSTAPAY_PACKAGE, BankingCloakManager.activeTargetPackage)
    }

    @Test
    fun testIsIgnoredPackage() {
        assertTrue(BankingCloakManager.isIgnoredPackage("com.android.systemui"))
        assertTrue(BankingCloakManager.isIgnoredPackage("android"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.android.permissioncontroller"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.google.android.permissioncontroller"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.google.android.inputmethod.latin"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.sec.android.inputmethod"))

        // OEM Secure Keyboards (Realme/OPPO, Xiaomi, Samsung, Huawei)
        assertTrue(BankingCloakManager.isIgnoredPackage("com.coloros.keyboard"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.oppo.keyboard"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.oplus.keyboard"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.coloros.safecenter"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.coloros.securitypermission"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.miui.securityadd"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.samsung.android.honeyboard"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.huawei.secime"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.touchtype.swiftkey"))

        // Cross-OEM Settings & Biometrics Ignored Packages
        assertTrue(BankingCloakManager.isIgnoredPackage("com.android.settings"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.samsung.android.biometrics.app.setting"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.vivo.fingerprint"))
        assertTrue(BankingCloakManager.isIgnoredPackage("com.vivo.fingerprintui"))

        assertFalse(BankingCloakManager.isIgnoredPackage("com.android.chrome"))
        assertFalse(BankingCloakManager.isIgnoredPackage("com.egyptianbanks.instapay"))
        assertFalse(BankingCloakManager.isIgnoredPackage("com.google.android.youtube"))
        assertFalse(BankingCloakManager.isIgnoredPackage("com.sec.android.app.launcher"))
        assertFalse(BankingCloakManager.isIgnoredPackage(""))
    }

    @Test
    fun testDeveloperOptionsDetection_handlesExceptionsGracefully() {
        assertFalse(BankingCloakManager.isDeveloperOptionsEnabled(dummyContext))
    }

    @Test
    fun testForegroundPackageChanged_whenNotCloaked_doesNothing() {
        assertFalse(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertFalse(BankingCloakManager.isCloaked)

        BankingCloakManager.onForegroundPackageChanged(dummyContext, "com.android.chrome")

        assertFalse(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertFalse(BankingCloakManager.isCloaked)
    }

    @Test
    fun testForegroundPackageChanged_whenCloaked_switchedToOtherApp_restoresProtection() {
        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertTrue(BankingCloakManager.isCloaked)

        BankingCloakManager.onForegroundPackageChanged(dummyContext, "com.android.chrome")

        assertFalse(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertFalse(BankingCloakManager.isCloaked)
        assertEquals(BankingCloakManager.INSTAPAY_PACKAGE, BankingCloakManager.activeTargetPackage)
    }

    @Test
    fun testForegroundPackageChanged_whenCloaked_staysCloakedIfTargetBankingApp() {
        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        BankingCloakManager.onForegroundPackageChanged(dummyContext, BankingCloakManager.INSTAPAY_PACKAGE)

        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertTrue(BankingCloakManager.isCloaked)
    }

    @Test
    fun testForegroundPackageChanged_whenCloaked_staysCloakedIfOwnPackage() {
        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        BankingCloakManager.onForegroundPackageChanged(dummyContext, dummyContext.packageName)

        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertTrue(BankingCloakManager.isCloaked)
    }

    @Test
    fun testForegroundPackageChanged_whenCloaked_staysCloakedIfIgnoredSystemPackage() {
        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        BankingCloakManager.onForegroundPackageChanged(dummyContext, "com.android.systemui")
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        BankingCloakManager.onForegroundPackageChanged(dummyContext, "android")
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        BankingCloakManager.onForegroundPackageChanged(dummyContext, "com.google.android.inputmethod.latin")
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)
    }

    @Test
    fun testForegroundPackageChanged_whenCloaked_staysCloakedIfBlankPackage() {
        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        BankingCloakManager.onForegroundPackageChanged(dummyContext, "")
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        BankingCloakManager.onForegroundPackageChanged(dummyContext, "   ")
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)
    }

    @Test
    fun testForegroundPackageChanged_withCustomTargetPackage() {
        val customBank = "com.cibeg.banking"
        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        // Staying inside custom bank
        BankingCloakManager.onForegroundPackageChanged(dummyContext, customBank, targetPackage = customBank)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        // Switching away from custom bank
        BankingCloakManager.onForegroundPackageChanged(dummyContext, "com.android.chrome", targetPackage = customBank)
        assertFalse(AccessibilitySelfActivator.isTemporarilyCloaked)
    }

    @Test
    fun testRestoreProtection_cancelsAlarmAndForcesUncloak() {
        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        BankingCloakManager.restoreProtection(dummyContext)

        assertFalse(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertEquals(BankingCloakManager.INSTAPAY_PACKAGE, BankingCloakManager.activeTargetPackage)
    }

    @Test
    fun testLaunchSafely_whenPackageNotWhitelisted_returnsFalseAndDoesNotCloak() {
        dummyContext.installedPackages.add("com.android.chrome")

        val result = BankingCloakManager.launchSafely(dummyContext, "com.android.chrome")

        assertFalse(result)
        assertFalse(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertFalse(BankingCloakManager.isCloaked)
    }

    @Test
    fun testLaunchSafely_whenPackageNotInstalled_returnsFalseAndDoesNotCloak() {
        dummyContext.installedPackages.clear()

        val result = BankingCloakManager.launchSafely(dummyContext, BankingCloakManager.INSTAPAY_PACKAGE)

        assertFalse(result)
        assertFalse(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertFalse(BankingCloakManager.isCloaked)
    }

    @Test
    fun testLaunchSafely_whenPackageInstalled_returnsTrueAndCloaks() {
        dummyContext.installedPackages.add(BankingCloakManager.INSTAPAY_PACKAGE)

        val result = BankingCloakManager.launchSafely(dummyContext, BankingCloakManager.INSTAPAY_PACKAGE)

        assertTrue(result)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertTrue(BankingCloakManager.isCloaked)
        assertEquals(BankingCloakManager.INSTAPAY_PACKAGE, BankingCloakManager.activeTargetPackage)
        assertEquals(1, dummyContext.startedActivities.size)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, dummyContext.startedActivities[0].flags and Intent.FLAG_ACTIVITY_NEW_TASK)

        BankingCloakManager.restoreProtection(dummyContext)
    }

    @Test
    fun testLaunchSafely_whenCloakFails_abortsLaunchAndClearsState() {
        val cibPkg = "com.cib.cibmobilebanking"
        val prefs = InMemorySharedPreferences()
        val persistentContext = DummyBankingContext(sharedPrefs = prefs).apply {
            installedPackages.add(cibPkg)
        }
        BankingCloakManager.disableCleanlyAction = { _, _ -> false }

        val launched = BankingCloakManager.launchSafely(persistentContext, cibPkg)

        assertFalse(launched)
        assertTrue(persistentContext.startedActivities.isEmpty())
        assertFalse(BankingCloakManager.isCloaked(persistentContext))
        assertEquals(BankingCloakManager.INSTAPAY_PACKAGE, BankingCloakManager.activeTargetPackage)
        assertNull(prefs.getString("active_target_package", null))
    }

    @Test
    fun testLaunchSafely_launchGracePreventsPrematureRestore_thenRestoresAfterGraceExpires() {
        dummyContext.installedPackages.add(BankingCloakManager.INSTAPAY_PACKAGE)

        val launched = BankingCloakManager.launchSafely(dummyContext, BankingCloakManager.INSTAPAY_PACKAGE)
        assertTrue(launched)
        assertTrue(BankingCloakManager.isCloaked(dummyContext))

        // Within 2500ms launch grace window: transient foreground package must NOT prematurely restore
        fakeElapsedMs += 500L
        BankingCloakManager.onForegroundPackageChanged(dummyContext, "com.android.chrome")
        assertTrue(BankingCloakManager.isCloaked(dummyContext))

        // Once launch grace window elapses: non-banking foreground app restores protection
        fakeElapsedMs += 2_500L
        BankingCloakManager.onForegroundPackageChanged(dummyContext, "com.android.chrome")
        assertFalse(BankingCloakManager.isCloaked(dummyContext))
    }

    @Test
    fun testLaunchSafely_launchGraceExitsImmediatelyWhenBankEntersForeground() {
        dummyContext.installedPackages.add(BankingCloakManager.INSTAPAY_PACKAGE)

        assertTrue(BankingCloakManager.launchSafely(dummyContext, BankingCloakManager.INSTAPAY_PACKAGE))
        assertTrue(BankingCloakManager.isCloaked(dummyContext))

        // Target bank reaches foreground -> clears launch grace immediately
        fakeElapsedMs += 200L
        BankingCloakManager.onForegroundPackageChanged(dummyContext, BankingCloakManager.INSTAPAY_PACKAGE)
        assertTrue(BankingCloakManager.isCloaked(dummyContext))

        // Leaving bank right away restores protection without waiting for the original 2500ms grace
        fakeElapsedMs += 100L
        BankingCloakManager.onForegroundPackageChanged(dummyContext, "com.android.chrome")
        assertFalse(BankingCloakManager.isCloaked(dummyContext))
    }

    @Test
    fun testLaunchSafely_whenAccessibilityInactive_launchesDirectlyWithoutCloaking() {
        dummyContext.installedPackages.add(BankingCloakManager.INSTAPAY_PACKAGE)
        BankingCloakManager.accessibilityActiveCheckOverride = { false }

        val launched = BankingCloakManager.launchSafely(dummyContext, BankingCloakManager.INSTAPAY_PACKAGE)

        assertTrue(launched)
        assertEquals(1, dummyContext.startedActivities.size)
        assertFalse(BankingCloakManager.isCloaked(dummyContext))
        assertFalse(AccessibilitySelfActivator.isTemporarilyCloaked)
    }

    @Test
    fun testCloakExpiry_zeroExpiryRejectedAndMaxDurationClamped() {
        val prefs = InMemorySharedPreferences()
        val persistentContext = DummyBankingContext(sharedPrefs = prefs)

        // Persisted cloak with zero/invalid expiry must be treated as expired and cleared
        prefs.edit()
            .putBoolean("is_cloaked", true)
            .putLong("cloak_expires_at_ms", 0L)
            .apply()
        assertFalse(AccessibilitySelfActivator.isCloaked(persistentContext))
        assertFalse(prefs.getBoolean("is_cloaked", false))

        // Oversized cloak duration must be clamped to MAX_CLOAK_DURATION_MS (10 minutes)
        val clamped = AccessibilitySelfActivator.disableCleanly(
            persistentContext,
            isTemporaryCloak = true,
            cloakDurationMs = 60L * 60L * 1000L
        )
        assertTrue(clamped)
        assertTrue(AccessibilitySelfActivator.isCloaked(persistentContext))

        fakeElapsedMs += AccessibilitySelfActivator.MAX_CLOAK_DURATION_MS - 1_000L
        assertTrue(AccessibilitySelfActivator.isCloaked(persistentContext))

        fakeElapsedMs += 1_000L
        assertFalse(AccessibilitySelfActivator.isCloaked(persistentContext))
    }

    @Test
    fun testBankingRestoreReceiver_onReceiveRestoresProtection() {
        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        val receiver = BankingRestoreReceiver()
        val intent = FakeIntent(BankingCloakManager.ACTION_RESTORE_BANKING_CLOAK)
        receiver.onReceive(dummyContext, intent)

        assertFalse(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertFalse(BankingCloakManager.isCloaked)
    }

    @Test
    fun testBankingRestoreReceiver_unexpectedAction_doesNotRestoreProtection() {
        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        val receiver = BankingRestoreReceiver()
        val intent = FakeIntent("some.malicious.or.unexpected.ACTION")
        receiver.onReceive(dummyContext, intent)

        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertTrue(BankingCloakManager.isCloaked)

        BankingCloakManager.restoreProtection(dummyContext)
    }

    @Test
    fun testRestoreProtection_duringTeardownClearsCloakAndActiveTargetPackage() {
        val prefs = InMemorySharedPreferences()
        val cibPkg = "com.cib.cibmobilebanking"
        val persistentContext = DummyBankingContext(sharedPrefs = prefs).apply {
            installedPackages.add(cibPkg)
        }

        assertTrue(BankingCloakManager.launchSafely(persistentContext, cibPkg))
        assertTrue(BankingCloakManager.isCloaked(persistentContext))
        assertEquals(cibPkg, BankingCloakManager.activeTargetPackage)

        com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.isTeardownInProgress = true
        BankingCloakManager.restoreProtection(persistentContext)

        assertFalse(BankingCloakManager.isCloaked(persistentContext))
        assertFalse(AccessibilitySelfActivator.hasPendingCloakFlag(persistentContext))
        assertEquals(BankingCloakManager.INSTAPAY_PACKAGE, BankingCloakManager.activeTargetPackage)
    }

    @Test
    fun testLaunchUriWithCloakIfNeeded_rejectsNonHttpsUri() {
        dummyContext.installedPackages.add(BankingCloakManager.INSTAPAY_PACKAGE)
        BankingCloakManager.viewUriIntentFactory = { u -> FakeIntent(Intent.ACTION_VIEW).apply { data = u } }

        val httpUri = TestUri(schemeValue = "http", hostValue = "ipn.eg")
        val jsUri = TestUri(schemeValue = "javascript", hostValue = null)

        assertFalse(BankingCloakManager.launchUriWithCloakIfNeeded(dummyContext, httpUri))
        assertFalse(BankingCloakManager.launchUriWithCloakIfNeeded(dummyContext, jsUri))
        assertTrue(dummyContext.startedActivities.isEmpty())
        assertFalse(BankingCloakManager.isCloaked(dummyContext))
    }

    @Test
    fun testLaunchUriWithCloakIfNeeded_whenInstalledAndA11yActive_cloaksAndLaunchesUri() {
        dummyContext.installedPackages.add(BankingCloakManager.INSTAPAY_PACKAGE)
        BankingCloakManager.viewUriIntentFactory = { u -> FakeIntent(Intent.ACTION_VIEW).apply { data = u } }
        val httpsUri = TestUri(schemeValue = "https", hostValue = "ipn.eg")

        val result = BankingCloakManager.launchUriWithCloakIfNeeded(dummyContext, httpsUri)

        assertTrue(result)
        assertTrue(BankingCloakManager.isCloaked(dummyContext))
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertEquals(BankingCloakManager.INSTAPAY_PACKAGE, BankingCloakManager.activeTargetPackage)
        assertEquals(1, dummyContext.startedActivities.size)
        assertEquals(Intent.ACTION_VIEW, dummyContext.startedActivities[0].action)
        assertEquals(httpsUri, dummyContext.startedActivities[0].data)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, dummyContext.startedActivities[0].flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun testLaunchUriWithCloakIfNeeded_whenNotInstalled_launchesDirectlyWithoutCloaking() {
        dummyContext.installedPackages.clear()
        BankingCloakManager.viewUriIntentFactory = { u -> FakeIntent(Intent.ACTION_VIEW).apply { data = u } }
        val httpsUri = TestUri(schemeValue = "https", hostValue = "ipn.eg")

        val result = BankingCloakManager.launchUriWithCloakIfNeeded(dummyContext, httpsUri)

        assertTrue(result)
        assertFalse(BankingCloakManager.isCloaked(dummyContext))
        assertFalse(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertEquals(1, dummyContext.startedActivities.size)
        assertEquals(Intent.ACTION_VIEW, dummyContext.startedActivities[0].action)
        assertEquals(httpsUri, dummyContext.startedActivities[0].data)
    }

    @Test
    fun testLaunchUriWithCloakIfNeeded_whenA11yInactive_launchesDirectlyWithoutCloaking() {
        dummyContext.installedPackages.add(BankingCloakManager.INSTAPAY_PACKAGE)
        BankingCloakManager.accessibilityActiveCheckOverride = { false }
        BankingCloakManager.viewUriIntentFactory = { u -> FakeIntent(Intent.ACTION_VIEW).apply { data = u } }
        val httpsUri = TestUri(schemeValue = "https", hostValue = "ipn.eg")

        val result = BankingCloakManager.launchUriWithCloakIfNeeded(dummyContext, httpsUri)

        assertTrue(result)
        assertFalse(BankingCloakManager.isCloaked(dummyContext))
        assertFalse(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertEquals(1, dummyContext.startedActivities.size)
        assertEquals(Intent.ACTION_VIEW, dummyContext.startedActivities[0].action)
    }

    @Test
    fun testLaunchUriWithCloakIfNeeded_whenPackageNotWhitelisted_launchesDirectlyWithoutCloaking() {
        dummyContext.installedPackages.add("com.android.chrome")
        BankingCloakManager.viewUriIntentFactory = { u -> FakeIntent(Intent.ACTION_VIEW).apply { data = u } }
        val httpsUri = TestUri(schemeValue = "https", hostValue = "ipn.eg")

        val result = BankingCloakManager.launchUriWithCloakIfNeeded(
            context = dummyContext,
            uri = httpsUri,
            targetPackage = "com.android.chrome"
        )

        assertTrue(result)
        assertFalse(BankingCloakManager.isCloaked(dummyContext))
        assertFalse(AccessibilitySelfActivator.isTemporarilyCloaked)
        assertEquals(1, dummyContext.startedActivities.size)
    }

    // =========================================================================
    // Test Doubles & Mocks
    // =========================================================================

    private class FakeIntent(action: String? = null) : Intent() {
        private var actionStr: String? = action
        private var pkg: String? = null
        private var flagsInt: Int = 0
        private var dataUri: Uri? = null

        override fun getAction(): String? = actionStr
        override fun setAction(action: String?): Intent {
            actionStr = action
            return this
        }
        override fun setData(data: Uri?): Intent {
            dataUri = data
            return this
        }
        override fun getData(): Uri? = dataUri
        override fun setPackage(packageName: String?): Intent {
            pkg = packageName
            return this
        }
        override fun getPackage(): String? = pkg
        override fun addFlags(flags: Int): Intent {
            flagsInt = flagsInt or flags
            return this
        }
        override fun getFlags(): Int = flagsInt
    }

    private class DummyBankingContext(
        private val sharedPrefs: android.content.SharedPreferences? = null
    ) : ContextWrapper(null) {
        val installedPackages = mutableSetOf<String>()
        val startedActivities = mutableListOf<Intent>()
        private val fakePm = FakeBankingPackageManager(installedPackages)

        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.drmohammed.antiporn"
        override fun getPackageManager(): PackageManager = fakePm

        override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
            return sharedPrefs ?: super.getSharedPreferences(name, mode)
        }

        override fun startActivity(intent: Intent?) {
            if (intent != null) {
                startedActivities.add(intent)
            }
        }

        override fun getSystemService(name: String): Any? {
            // Return null for AlarmManager or other system services in test double
            return null
        }
    }

    private class InMemorySharedPreferences : android.content.SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) removals.add(key) }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                data.putAll(pending)
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private class FakeBankingPackageManager(
        private val installedPackages: Set<String>
    ) : PackageManager() {

        override fun getLaunchIntentForPackage(packageName: String): Intent? {
            return if (installedPackages.contains(packageName)) {
                FakeIntent(Intent.ACTION_MAIN).apply {
                    `package` = packageName
                }
            } else {
                null
            }
        }

        override fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
            if (installedPackages.contains(packageName)) {
                return PackageInfo().apply { this.packageName = packageName }
            }
            throw NameNotFoundException(packageName)
        }

        override fun getPackageInfo(packageName: String, flags: PackageInfoFlags): PackageInfo =
            getPackageInfo(packageName, flags.value.toInt())

        override fun getPackageInfo(versionedPackage: VersionedPackage, flags: Int): PackageInfo =
            throw NameNotFoundException(versionedPackage.packageName)

        override fun getPackageGids(packageName: String): IntArray = throw NameNotFoundException(packageName)
        override fun getPackageGids(packageName: String, flags: Int): IntArray = throw NameNotFoundException(packageName)
        override fun getPackageUid(packageName: String, flags: Int): Int = throw NameNotFoundException(packageName)
        override fun currentToCanonicalPackageNames(packageNames: Array<out String>): Array<String> = emptyArray()
        override fun canonicalToCurrentPackageNames(packageNames: Array<out String>): Array<String> = emptyArray()
        override fun getLeanbackLaunchIntentForPackage(packageName: String): Intent? = null
        override fun getPermissionInfo(permName: String, flags: Int): PermissionInfo = throw NameNotFoundException(permName)
        override fun queryPermissionsByGroup(permissionGroup: String?, flags: Int): List<PermissionInfo> = emptyList()
        override fun getPermissionGroupInfo(groupName: String, flags: Int): PermissionGroupInfo = throw NameNotFoundException(groupName)
        override fun getAllPermissionGroups(flags: Int): List<PermissionGroupInfo> = emptyList()
        override fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo = throw NameNotFoundException(packageName)
        override fun getActivityInfo(component: ComponentName, flags: Int): ActivityInfo = throw NameNotFoundException()
        override fun getReceiverInfo(component: ComponentName, flags: Int): ActivityInfo = throw NameNotFoundException()
        override fun getServiceInfo(component: ComponentName, flags: Int): ServiceInfo = throw NameNotFoundException()
        override fun getProviderInfo(component: ComponentName, flags: Int): ProviderInfo = throw NameNotFoundException()
        override fun getInstalledPackages(flags: Int): List<PackageInfo> = emptyList()
        override fun getPackagesHoldingPermissions(permissions: Array<out String>, flags: Int): List<PackageInfo> = emptyList()
        override fun checkPermission(permName: String, packageName: String): Int = PERMISSION_DENIED
        override fun isPermissionRevokedByPolicy(permName: String, packageName: String): Boolean = false
        override fun addPermission(info: PermissionInfo): Boolean = false
        override fun addPermissionAsync(info: PermissionInfo): Boolean = false
        override fun removePermission(permName: String) {}
        override fun checkSignatures(packageName1: String, packageName2: String): Int = SIGNATURE_NO_MATCH
        override fun checkSignatures(uid1: Int, uid2: Int): Int = SIGNATURE_NO_MATCH
        override fun getPackagesForUid(uid: Int): Array<String>? = null
        override fun getNameForUid(uid: Int): String? = null
        override fun getInstalledApplications(flags: Int): List<ApplicationInfo> = emptyList()
        override fun isInstantApp(): Boolean = false
        override fun isInstantApp(packageName: String): Boolean = false
        override fun getInstantAppCookieMaxBytes(): Int = 0
        override fun getInstantAppCookie(): ByteArray = byteArrayOf()
        override fun clearInstantAppCookie() {}
        override fun updateInstantAppCookie(cookie: ByteArray?) {}
        override fun getSystemSharedLibraryNames(): Array<String>? = null
        override fun getSharedLibraries(flags: Int): List<SharedLibraryInfo> = emptyList()
        override fun getChangedPackages(sequenceNumber: Int): ChangedPackages? = null
        override fun getSystemAvailableFeatures(): Array<FeatureInfo> = emptyArray()
        override fun hasSystemFeature(featureName: String): Boolean = false
        override fun hasSystemFeature(featureName: String, version: Int): Boolean = false
        override fun resolveActivity(intent: Intent, flags: Int): ResolveInfo? = null
        override fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfo> = emptyList()
        override fun queryIntentActivityOptions(caller: ComponentName?, specifics: Array<out Intent>?, intent: Intent, flags: Int): List<ResolveInfo> = emptyList()
        override fun queryBroadcastReceivers(intent: Intent, flags: Int): List<ResolveInfo> = emptyList()
        override fun resolveService(intent: Intent, flags: Int): ResolveInfo? = null
        override fun queryIntentServices(intent: Intent, flags: Int): List<ResolveInfo> = emptyList()
        override fun queryIntentContentProviders(intent: Intent, flags: Int): List<ResolveInfo> = emptyList()
        override fun resolveContentProvider(authority: String, flags: Int): ProviderInfo? = null
        override fun queryContentProviders(processName: String?, uid: Int, flags: Int): List<ProviderInfo> = emptyList()
        override fun getInstrumentationInfo(className: ComponentName, flags: Int): InstrumentationInfo = throw NameNotFoundException()
        override fun queryInstrumentation(targetPackage: String, flags: Int): List<InstrumentationInfo> = emptyList()
        override fun getDrawable(packageName: String, resid: Int, appInfo: ApplicationInfo?): Drawable? = null
        override fun getActivityIcon(activityName: ComponentName): Drawable = throw NameNotFoundException()
        override fun getActivityIcon(intent: Intent): Drawable = throw NameNotFoundException()
        override fun getActivityBanner(activityName: ComponentName): Drawable? = null
        override fun getActivityBanner(intent: Intent): Drawable? = null
        override fun getDefaultActivityIcon(): Drawable = throw UnsupportedOperationException()
        override fun getApplicationIcon(info: ApplicationInfo): Drawable = throw UnsupportedOperationException()
        override fun getApplicationIcon(packageName: String): Drawable = throw NameNotFoundException(packageName)
        override fun getApplicationBanner(info: ApplicationInfo): Drawable? = null
        override fun getApplicationBanner(packageName: String): Drawable? = null
        override fun getActivityLogo(activityName: ComponentName): Drawable? = null
        override fun getActivityLogo(intent: Intent): Drawable? = null
        override fun getApplicationLogo(info: ApplicationInfo): Drawable? = null
        override fun getApplicationLogo(packageName: String): Drawable? = null
        override fun getUserBadgedIcon(drawable: Drawable, user: UserHandle): Drawable = drawable
        override fun getUserBadgedDrawableForDensity(drawable: Drawable, user: UserHandle, badgeLocation: Rect?, badgeDensity: Int): Drawable = drawable
        override fun getUserBadgedLabel(label: CharSequence, user: UserHandle): CharSequence = label
        override fun getText(packageName: String, resid: Int, appInfo: ApplicationInfo?): CharSequence? = null
        override fun getXml(packageName: String, resid: Int, appInfo: ApplicationInfo?): XmlResourceParser? = null
        override fun getApplicationLabel(info: ApplicationInfo): CharSequence = ""
        override fun getResourcesForActivity(activityName: ComponentName): Resources = throw NameNotFoundException()
        override fun getResourcesForApplication(app: ApplicationInfo): Resources = throw NameNotFoundException()
        override fun getResourcesForApplication(packageName: String): Resources = throw NameNotFoundException(packageName)
        override fun verifyPendingInstall(id: Int, verificationCode: Int) {}
        override fun extendVerificationTimeout(id: Int, verificationCodeAtTimeout: Int, millisecondsToDelay: Long) {}
        override fun setInstallerPackageName(targetPackage: String, installerPackageName: String?) {}
        override fun getInstallerPackageName(packageName: String): String? = null
        override fun addPackageToPreferred(packageName: String) {}
        override fun removePackageFromPreferred(packageName: String) {}
        override fun getPreferredPackages(flags: Int): List<PackageInfo> = emptyList()
        override fun addPreferredActivity(filter: IntentFilter, match: Int, set: Array<out ComponentName>?, activity: ComponentName) {}
        override fun clearPackagePreferredActivities(packageName: String) {}
        override fun getPreferredActivities(outFilters: MutableList<IntentFilter>, outActivities: MutableList<ComponentName>, packageName: String?): Int = 0
        override fun setComponentEnabledSetting(componentName: ComponentName, newState: Int, flags: Int) {}
        override fun getComponentEnabledSetting(componentName: ComponentName): Int = COMPONENT_ENABLED_STATE_DEFAULT
        override fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int) {}
        override fun getApplicationEnabledSetting(packageName: String): Int = COMPONENT_ENABLED_STATE_DEFAULT
        override fun isSafeMode(): Boolean = false
        override fun setApplicationCategoryHint(packageName: String, categoryHint: Int) {}
        override fun getPackageInstaller(): PackageInstaller = throw UnsupportedOperationException()
        override fun canRequestPackageInstalls(): Boolean = false
    }
}
