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
import android.os.UserHandle
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BankingCloakManagerTest {

    private lateinit var dummyContext: DummyBankingContext

    @Before
    fun setUp() {
        dummyContext = DummyBankingContext()
        AccessibilitySelfActivator.enableSilently(dummyContext, forceUncloak = true)
    }

    @After
    fun tearDown() {
        AccessibilitySelfActivator.enableSilently(dummyContext, forceUncloak = true)
    }

    @Test
    fun testConstants() {
        assertEquals("com.egyptianbanks.instapay", BankingCloakManager.INSTAPAY_PACKAGE)
        assertEquals(180_000L, BankingCloakManager.CLOAK_TIMEOUT_MS)
        assertEquals(250L, BankingCloakManager.DEFAULT_SETTLING_DELAY_MS)
        assertEquals("com.drmohammed.antiporn.action.RESTORE_BANKING_CLOAK", BankingCloakManager.ACTION_RESTORE_BANKING_CLOAK)
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

    // =========================================================================
    // Test Doubles & Mocks
    // =========================================================================

    private class FakeIntent(action: String? = null) : Intent() {
        private var actionStr: String? = action
        private var pkg: String? = null
        private var flagsInt: Int = 0

        override fun getAction(): String? = actionStr
        override fun setAction(action: String?): Intent {
            actionStr = action
            return this
        }
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

    private class DummyBankingContext : ContextWrapper(null) {
        val installedPackages = mutableSetOf<String>()
        val startedActivities = mutableListOf<Intent>()
        private val fakePm = FakeBankingPackageManager(installedPackages)

        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.drmohammed.antiporn"
        override fun getPackageManager(): PackageManager = fakePm

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
