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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankingShortcutHelperTest {

    @Test
    fun testShortcutIdConstant() {
        assertEquals("shortcut_instapay_safe", BankingShortcutHelper.SHORTCUT_ID_INSTAPAY)
    }

    @Test
    fun testIsInstaPayInstalled_returnsTrueWhenPackagePresent() {
        val context = DummyTestContext(installedPackages = setOf(BankingCloakManager.INSTAPAY_PACKAGE))
        assertTrue(BankingShortcutHelper.isInstaPayInstalled(context))
    }

    @Test
    fun testIsInstaPayInstalled_returnsFalseWhenPackageMissing() {
        val context = DummyTestContext(installedPackages = emptySet())
        assertFalse(BankingShortcutHelper.isInstaPayInstalled(context))
    }

    @Test
    fun testIsInstaPayInstalled_handlesExceptionGracefully() {
        val throwingContext = ThrowingPackageManagerContext()
        assertFalse(BankingShortcutHelper.isInstaPayInstalled(throwingContext))
    }

    @Test
    fun testPinInstaPayShortcut_unsupportedLauncher_returnsFalseGracefully() {
        val context = DummyTestContext(installedPackages = setOf(BankingCloakManager.INSTAPAY_PACKAGE))
        val result = BankingShortcutHelper.pinInstaPayShortcut(context)
        // In JVM test environment without supported ShortcutManager system service,
        // it gracefully handles absence and returns false without crashing.
        assertFalse(result)
    }

    @Test
    fun testPinInstaPayShortcut_whenInstaPayNotInstalled_returnsFalseImmediately() {
        val context = DummyTestContext(installedPackages = emptySet())
        val result = BankingShortcutHelper.pinInstaPayShortcut(context)
        assertFalse("Should return false immediately if InstaPay is not installed", result)
    }

    // =========================================================================
    // Test Doubles
    // =========================================================================

    private class DummyTestContext(
        installedPackages: Set<String>
    ) : ContextWrapper(null) {
        private val fakePm = FakePackageManager(installedPackages)

        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.drmohammed.antiporn"
        override fun getPackageManager(): PackageManager = fakePm
        override fun getSystemService(name: String): Any? = null
    }

    private class ThrowingPackageManagerContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.drmohammed.antiporn"
        override fun getPackageManager(): PackageManager {
            throw SecurityException("Mock package manager error")
        }
    }

    private class FakeIntent(action: String? = null) : Intent() {
        private var actionStr: String? = action
        private var pkg: String? = null

        override fun getAction(): String? = actionStr
        override fun setPackage(packageName: String?): Intent {
            pkg = packageName
            return this
        }
        override fun getPackage(): String? = pkg
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private class FakePackageManager(
        private val installedPackages: Set<String>
    ) : PackageManager() {

        override fun getLaunchIntentForPackage(packageName: String): Intent? {
            return if (installedPackages.contains(packageName)) {
                FakeIntent(Intent.ACTION_MAIN).apply {
                    setPackage(packageName)
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
