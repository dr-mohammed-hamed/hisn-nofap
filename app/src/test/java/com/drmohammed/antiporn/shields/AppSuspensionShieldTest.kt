package com.drmohammed.antiporn.shields

import com.drmohammed.antiporn.PackageChangeHandler
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.testing.FakeTestApplicationContext
import com.drmohammed.antiporn.testing.FakeTestDevicePolicyDao
import com.drmohammed.antiporn.testing.FakeTestPartnerDao
import com.drmohammed.antiporn.testing.FakeTestPendingApprovalDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSuspensionShieldTest {

    @After
    fun tearDown() {
        ApprovalRepository.setInstance(null)
        PolicyRepository.setInstance(null)
        AppSuspensionHandler.isPackageInstalledProvider = { pm, pkg ->
            pm != null && AppSuspensionHandler.isPackageInstalled(pm, pkg)
        }
    }

    @Test
    fun testTelegramPackagesAreBlacklisted() {
        val telegramPackages = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.telegram.plus",
            "nekox.messenger",
            "org.thunderdog.challegram",
            "com.iMe.android",
            "org.aka.messenger",
            "com.turrit.telegram",
            "org.vidogram.messenger",
            "org.telegram.BNavigation"
        )

        for (pkg in telegramPackages) {
            assertFalse("Package $pkg must not be blacklisted under Unified Zero-Trust", AppSuspensionShield.isBlacklisted(pkg))
            assertTrue("Package $pkg must be high risk", AppPolicyRegistry.isHighRisk(pkg))
            assertEquals("Telegram", AppSuspensionShield.getCategoryForPackage(pkg))
        }
    }

    @Test
    fun testOpenSocialNetworksAreBlacklisted() {
        val socialPackages = listOf(
            // Reddit & top clients
            "com.reddit.frontpage",
            "com.rubenmayayo.reddit",
            "com.laurencedawson.reddit_sync",
            "ml.docilealligator.infinityforreddit",
            "free.reddit.news",
            "com.andrewshu.android.reddit",
            // X / Twitter
            "com.twitter.android",
            "com.twitter.android.lite",
            // Tumblr
            "com.tumblr",
            // VK
            "com.vkontakte.android",
            "com.vk.im"
        )

        for (pkg in socialPackages) {
            assertFalse("Package $pkg must not be blacklisted under Unified Zero-Trust", AppSuspensionShield.isBlacklisted(pkg))
            assertTrue("Package $pkg must be high risk", AppPolicyRegistry.isHighRisk(pkg))
            assertEquals("Open Social Network", AppSuspensionShield.getCategoryForPackage(pkg))
        }
    }

    @Test
    fun testProxyBrowsersAreBlacklisted() {
        val browsers = listOf(
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.torproject.torbrowser",
            "com.duckduckgo.mobile.android",
            "com.cloudmosa.puffinFree",
            "com.cloudmosa.puffin",
            "com.kiwibrowser.browser",
            "com.opera.touch",
            "com.opera.browser",
            "com.alohamobile.browser",
            "com.alohamobile.browser.lite",
            "com.UCMobile.intl",
            "com.uc.browser.en",
            "com.opera.mini.native",
            "org.mozilla.focus",
            "com.opera.gx",
            "com.opera.browser.beta",
            "com.yandex.browser"
        )

        for (pkg in browsers) {
            assertFalse("Package $pkg must not be blacklisted under Unified Zero-Trust", AppSuspensionShield.isBlacklisted(pkg))
            assertTrue("Package $pkg must be high risk", AppPolicyRegistry.isHighRisk(pkg))
            assertEquals("Proxy Browser", AppSuspensionShield.getCategoryForPackage(pkg))
        }
    }

    @Test
    fun testRandomChatAppsAreBlacklisted() {
        val chatApps = listOf(
            "chat.ometv",
            "com.monkey.cool",
            "com.chatous.chatous",
            "com.kik.android",
            "com.camfrog.android.phone",
            "com.connected2.me",
            "com.azar.live",
            "com.livu.live"
        )

        for (pkg in chatApps) {
            assertFalse("Package $pkg must not be blacklisted under Unified Zero-Trust", AppSuspensionShield.isBlacklisted(pkg))
            assertTrue("Package $pkg must be high risk", AppPolicyRegistry.isHighRisk(pkg))
            assertEquals("Random Chat App", AppSuspensionShield.getCategoryForPackage(pkg))
        }
    }

    @Test
    fun testTikTokPackagesAreBlacklisted() {
        val tikTokApps = listOf(
            "com.zhiliaoapp.musically",
            "com.zhiliaoapp.musically.go",
            "com.ss.android.ugc.trill",
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite",
            "com.ss.android.ugc.aweme.mobile",
            "com.zhiliao.musically"
        )

        for (pkg in tikTokApps) {
            assertFalse("Package $pkg must not be blacklisted under Unified Zero-Trust", AppSuspensionShield.isBlacklisted(pkg))
            assertTrue("Package $pkg must be high risk", AppPolicyRegistry.isHighRisk(pkg))
            assertEquals("TikTok", AppSuspensionShield.getCategoryForPackage(pkg))
        }
    }

    @Test
    fun testExplicitImmunitySafeguard() {
        val immuneApp = "com.drmohammed.antiporn"

        assertTrue("Package $immuneApp must be marked as immune", AppSuspensionShield.isImmune(immuneApp))
        assertFalse(
            "Immune package $immuneApp must NEVER be reported as blacklisted",
            AppSuspensionShield.isBlacklisted(immuneApp)
        )
        assertFalse(
            "Immune package $immuneApp must NOT exist in BLACKLISTED_PACKAGES set",
            AppSuspensionShield.BLACKLISTED_PACKAGES.contains(immuneApp)
        )
        assertNull(
            "Immune package $immuneApp must return null category",
            AppSuspensionShield.getCategoryForPackage(immuneApp)
        )

        // Puregram must NOT be immune (closed security loophole)
        assertFalse("Puregram must not be immune", AppSuspensionShield.isImmune("org.puregram.messenger"))

        val overlap = AppSuspensionShield.BLACKLISTED_PACKAGES.intersect(AppSuspensionShield.IMMUNE_PACKAGES)
        assertTrue("BLACKLISTED_PACKAGES and IMMUNE_PACKAGES must have zero overlap", overlap.isEmpty())
    }

    @Test
    fun testSafeAppsAndToolsAreNotBlacklisted() {
        val safeApps = listOf(
            "com.android.settings",
            "com.google.android.apps.messaging",
            "com.google.android.dialer",
            "com.whatsapp",
            "com.android.chrome",
            "com.google.android.youtube",
            "org.fdroid.fdroid",
            "com.google.android.googlequicksearchbox",
            "",
            "random.unknown.package"
        )

        for (pkg in safeApps) {
            assertFalse("Expected $pkg to NOT be blacklisted", AppSuspensionShield.isBlacklisted(pkg))
            assertNull("Expected category for $pkg to be null", AppSuspensionShield.getCategoryForPackage(pkg))
        }
    }

    @Test
    fun testLocalAdbToolsClassification() {
        for (pkg in AppSuspensionShield.LOCAL_ADB_TOOLS) {
            assertEquals("Local ADB Exploit", AppSuspensionShield.getCategoryForPackage(pkg))
            assertTrue("Local ADB tool $pkg must be high-risk in AppPolicyRegistry", AppPolicyRegistry.isHighRisk(pkg))
        }
    }

    @Test
    fun testPackageCountsAndCategoryIntegrity() {
        assertEquals(11, AppSuspensionShield.TELEGRAM_PACKAGES.size)
        assertEquals(11, AppSuspensionShield.OPEN_SOCIAL_NETWORKS.size)
        assertEquals(19, AppSuspensionShield.PROXY_BROWSERS.size)
        assertEquals(8, AppSuspensionShield.RANDOM_CHAT_APPS.size)
        assertEquals(7, AppSuspensionShield.TIKTOK_PACKAGES.size)
        assertEquals(7, AppSuspensionShield.LOCAL_ADB_TOOLS.size)

        val totalExpected = AppSuspensionShield.TELEGRAM_PACKAGES.size +
            AppSuspensionShield.OPEN_SOCIAL_NETWORKS.size +
            AppSuspensionShield.PROXY_BROWSERS.size +
            AppSuspensionShield.RANDOM_CHAT_APPS.size +
            AppSuspensionShield.TIKTOK_PACKAGES.size +
            AppSuspensionShield.LOCAL_ADB_TOOLS.size

        assertEquals(63, totalExpected)
        assertTrue("BLACKLISTED_PACKAGES must be empty under Unified Zero-Trust", AppSuspensionShield.BLACKLISTED_PACKAGES.isEmpty())
        assertEquals(0, AppSuspensionShield.BLACKLISTED_PACKAGES.size)
    }

    @Test
    fun testResolveTargetsToEnforce_excludesUnblacklistedHighRiskApp() {
        val telegramPkg = "org.telegram.messenger"
        val customBlacklistedPkg = "com.custom.blocked"
        val rejectedPkg = "com.rejected.app"

        val context = FakeTestApplicationContext()
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            lockNewInstallsUntilApproved = false,
            customBlacklistPackages = setOf(customBlacklistedPkg)
        )

        val policyDao = FakeTestDevicePolicyDao().apply {
            saveDevicePolicyBlocking(DevicePolicyEntity.fromBundle(policy))
        }
        val policyRepo = PolicyRepository(context, policyDao, FakeTestPartnerDao()).apply {
            updateLocalPolicyWithoutEnforcing(policy)
        }
        PolicyRepository.setInstance(policyRepo)

        val approvalDao = FakeTestPendingApprovalDao().apply {
            savePendingApprovalBlocking(
                PendingApprovalEntity(
                    packageName = telegramPkg,
                    appName = "Telegram",
                    requestedAt = 1000L,
                    status = PendingApprovalEntity.STATUS_APPROVED,
                    decidedBy = "AUTO_APPROVED_UNLOCKED",
                    decidedAt = 1000L,
                    isCloudSynced = true
                )
            )
            savePendingApprovalBlocking(
                PendingApprovalEntity(
                    packageName = rejectedPkg,
                    appName = "Rejected",
                    requestedAt = 1000L,
                    status = PendingApprovalEntity.STATUS_REJECTED,
                    decidedBy = "GuardianAlice",
                    decidedAt = 1500L,
                    isCloudSynced = true
                )
            )
        }
        ApprovalRepository.setInstance(ApprovalRepository(context, approvalDao))
        AppSuspensionHandler.isPackageInstalledProvider = { _, _ -> true }

        val targets = AppSuspensionHandler.resolveTargetsToEnforce(context)
        assertFalse(
            "org.telegram.messenger must NOT be returned when not in customBlacklist/pending/rejected",
            targets.contains(telegramPkg)
        )
        assertTrue("Custom blacklisted package must be enforced", targets.contains(customBlacklistedPkg))
        assertTrue("Rejected package must be enforced", targets.contains(rejectedPkg))
    }

    @Test
    fun testPackageChangeHandler_doesNotFreezeHighRiskOnUpdateOrWhenUnlocked() {
        val telegramPkg = "org.telegram.messenger"

        // 1. Update (isReplacing = true) of unseeded app even when Zero-Trust lock is enabled -> must NOT freeze
        val lockedPolicy = DevicePolicyBundle.defaultPolicy().copy(lockNewInstallsUntilApproved = true)
        val updateDecision = PackageChangeHandler.evaluatePackageAddedDecision(
            packageName = telegramPkg,
            isReplacing = true,
            policy = lockedPolicy,
            localApprovals = emptyList()
        )
        assertFalse("Update (isReplacing=true) must NOT freeze unseeded org.telegram.messenger", updateDecision.shouldFreeze)
        assertEquals("INITIAL_DEVICE_BASELINE", updateDecision.autoApproveDecidedBy)

        // 2. Fresh install when lockNewInstallsUntilApproved == false -> must NOT freeze
        val unlockedPolicy = DevicePolicyBundle.defaultPolicy().copy(lockNewInstallsUntilApproved = false)
        val unlockedDecision = PackageChangeHandler.evaluatePackageAddedDecision(
            packageName = telegramPkg,
            isReplacing = false,
            policy = unlockedPolicy,
            localApprovals = emptyList()
        )
        assertFalse("Unlocked policy must NOT freeze org.telegram.messenger", unlockedDecision.shouldFreeze)
        assertEquals("AUTO_APPROVED_UNLOCKED", unlockedDecision.autoApproveDecidedBy)

        // 3. Explicit custom blacklist still freezes even when unlocked or replacing
        val blacklistedPolicy = unlockedPolicy.copy(customBlacklistPackages = setOf(telegramPkg))
        val blacklistedDecision = PackageChangeHandler.evaluatePackageAddedDecision(
            packageName = telegramPkg,
            isReplacing = true,
            policy = blacklistedPolicy,
            localApprovals = emptyList()
        )
        assertTrue("Custom blacklisted package must freeze", blacklistedDecision.shouldFreeze)
    }

    @Test
    fun testPackageChangeHandler_preservesRejectedAndCustomBlacklistedOverApproved() {
        val rejectedPkg = "com.rejected.app"
        val customBlacklistedApprovedPkg = "com.blacklisted.approved"
        val unlockedPolicy = DevicePolicyBundle.defaultPolicy().copy(
            lockNewInstallsUntilApproved = false,
            customBlacklistPackages = setOf(customBlacklistedApprovedPkg)
        )
        val approvals = listOf(
            PendingApprovalEntity(
                packageName = rejectedPkg,
                appName = "Rejected",
                requestedAt = 1000L,
                status = PendingApprovalEntity.STATUS_REJECTED,
                decidedBy = "GuardianAlice",
                decidedAt = 1500L,
                isCloudSynced = true
            ),
            PendingApprovalEntity(
                packageName = customBlacklistedApprovedPkg,
                appName = "Approved Then Blacklisted",
                requestedAt = 1000L,
                status = PendingApprovalEntity.STATUS_APPROVED,
                decidedBy = "INITIAL_DEVICE_BASELINE",
                decidedAt = 1000L,
                isCloudSynced = true
            )
        )

        val reinstallRejected = PackageChangeHandler.evaluatePackageAddedDecision(
            packageName = rejectedPkg,
            isReplacing = false,
            policy = unlockedPolicy,
            localApprovals = approvals
        )
        assertTrue("Guardian-rejected app must freeze on reinstall even when unlocked", reinstallRejected.shouldFreeze)
        assertNull("Rejected app must NEVER auto-approve", reinstallRejected.autoApproveDecidedBy)

        val updateRejected = PackageChangeHandler.evaluatePackageAddedDecision(
            packageName = rejectedPkg,
            isReplacing = true,
            policy = unlockedPolicy,
            localApprovals = approvals
        )
        assertTrue("Guardian-rejected app must freeze on update even when unlocked", updateRejected.shouldFreeze)
        assertNull("Rejected app must NEVER auto-approve on update", updateRejected.autoApproveDecidedBy)

        val updateBlacklistedApproved = PackageChangeHandler.evaluatePackageAddedDecision(
            packageName = customBlacklistedApprovedPkg,
            isReplacing = true,
            policy = unlockedPolicy,
            localApprovals = approvals
        )
        assertTrue("Custom-blacklisted app must freeze even if prior approval row exists", updateBlacklistedApproved.shouldFreeze)
        assertNull(updateBlacklistedApproved.autoApproveDecidedBy)
    }

    @Test
    fun testResolveTargetsToEnforce_pendingRespectsLockNewInstallsFlag() {
        val pendingPkg = "com.pending.app"
        val context = FakeTestApplicationContext()
        val approvalDao = FakeTestPendingApprovalDao().apply {
            savePendingApprovalBlocking(
                PendingApprovalEntity(
                    packageName = pendingPkg,
                    appName = "Pending",
                    requestedAt = 1000L,
                    status = PendingApprovalEntity.STATUS_PENDING,
                    isCloudSynced = true
                )
            )
        }
        ApprovalRepository.setInstance(ApprovalRepository(context, approvalDao))
        AppSuspensionHandler.isPackageInstalledProvider = { _, _ -> true }

        val unlockedPolicy = DevicePolicyBundle.defaultPolicy().copy(lockNewInstallsUntilApproved = false)
        PolicyRepository.setInstance(
            PolicyRepository(context, FakeTestDevicePolicyDao(), FakeTestPartnerDao()).apply {
                updateLocalPolicyWithoutEnforcing(unlockedPolicy)
            }
        )
        assertFalse(
            "Pending package should not be enforced when lockNewInstallsUntilApproved=false",
            AppSuspensionHandler.resolveTargetsToEnforce(context).contains(pendingPkg)
        )

        val lockedPolicy = DevicePolicyBundle.defaultPolicy().copy(lockNewInstallsUntilApproved = true)
        PolicyRepository.setInstance(
            PolicyRepository(context, FakeTestDevicePolicyDao(), FakeTestPartnerDao()).apply {
                updateLocalPolicyWithoutEnforcing(lockedPolicy)
            }
        )
        assertTrue(
            "Pending package must be enforced when lockNewInstallsUntilApproved=true",
            AppSuspensionHandler.resolveTargetsToEnforce(context).contains(pendingPkg)
        )
    }
}
