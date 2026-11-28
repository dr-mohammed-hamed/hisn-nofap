package com.drmohammed.antiporn.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.dao.InAppNotificationDao
import com.drmohammed.antiporn.data.local.db.entity.InAppNotificationEntity
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.repository.NotificationRepository
import com.drmohammed.antiporn.testing.FakeTestPendingApprovalDao
import com.drmohammed.antiporn.testing.InMemoryTestSharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavioral tests exercising the real production [ApprovalRepository] with an in-memory
 * [PendingApprovalDao], [FakeContext], and `@VisibleForTesting` seams.
 */
class ApprovalSelfControlTest {

    private lateinit var fakeContext: FakeContext
    private lateinit var fakeDao: FakeTestPendingApprovalDao
    private lateinit var repo: ApprovalRepository
    private lateinit var notifScope: CoroutineScope

    private var fakeNowMs: Long = 1_750_000_000_000L
    private var dpmUnsuspendCalls = mutableListOf<List<String>>()
    private var dpmSuspendCalls = mutableListOf<Pair<String, String>>()
    private var cloudSyncCalls = mutableListOf<String>()
    private var notificationCalls = mutableListOf<Triple<String, Boolean, String?>>()

    @Before
    fun setUp() {
        fakeNowMs = 1_750_000_000_000L
        dpmUnsuspendCalls.clear()
        dpmSuspendCalls.clear()
        cloudSyncCalls.clear()
        notificationCalls.clear()

        TrustedTimeProvider.elapsedRealtimeProvider = { 10_000L }
        TrustedTimeProvider.wallTimeProvider = { fakeNowMs }
        TrustedTimeProvider.resetForTesting()

        fakeContext = FakeContext()
        fakeDao = FakeTestPendingApprovalDao()

        notifScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val fakeNotifRepo = NotificationRepository(NoOpInAppNotificationDao(), notifScope)
        AppNotificationManager.setInstance(AppNotificationManager(fakeContext, fakeNotifRepo, notifScope))

        repo = ApprovalRepository(fakeContext, fakeDao).apply {
            policyProvider = { DevicePolicyBundle.defaultPolicy() }
            currentTimeMillisProvider = { fakeNowMs }
            isDeviceOwnerProvider = { true }
            isBlockActiveProvider = { false }
            immunePackagesProvider = { emptySet() }
            appLabelProvider = { pkg -> pkg }
            isPackageInstalledProvider = { true }
            isAppHiddenProvider = { false }
            setAppHiddenProvider = { _, _ -> true }
            setPackagesSuspendedProvider = { packages, suspended ->
                if (!suspended) {
                    dpmUnsuspendCalls.add(packages.toList())
                }
                true
            }
            suspendWithMessageProvider = { pkg, msg ->
                dpmSuspendCalls.add(pkg to msg)
                true
            }
            watcherSyncer = {}
            foregroundEvaluator = {}
            notificationDispatcher = { pkg, approved, _, decidedBy ->
                notificationCalls.add(Triple(pkg, approved, decidedBy))
            }
            cloudDecisionSyncer = { pkg ->
                cloudSyncCalls.add(pkg)
            }
            firestoreUploader = null
            stringProvider = { resId -> fakeContext.resources.getString(resId) }
            systemCriticalChecker = { false }
        }
    }

    @After
    fun tearDown() {
        ApprovalRepository.setInstance(null)
        AppNotificationManager.setInstance(null)
        TrustedTimeProvider.resetForTesting()
        notifScope.cancel()
    }

    @Test
    fun guardianRejected_blocksSelfApproval_withoutCallingDpmOrMutatingDao() {
        fakeDao.savePendingApprovalBlocking(
            PendingApprovalEntity(
                packageName = "com.blocked.app",
                appName = "Blocked App",
                requestedAt = 1000L,
                status = PendingApprovalEntity.STATUS_REJECTED,
                decidedBy = "GuardianAlice",
                decidedAt = 1500L,
                isCloudSynced = true
            )
        )

        val result = repo.unsuspendAndApprove(
            packageName = "com.blocked.app",
            decidedBy = "SELF",
            syncToCloud = true
        )

        assertEquals(ApprovalDecisionResult.BLOCKED_REJECTED, result)
        assertTrue("DPM unsuspend must NEVER be called for guardian-rejected app", dpmUnsuspendCalls.isEmpty())
        val stored = fakeDao.getApprovalByPackageNameBlocking("com.blocked.app")
        assertNotNull(stored)
        assertEquals(PendingApprovalEntity.STATUS_REJECTED, stored?.status)
        assertEquals("GuardianAlice", stored?.decidedBy)
    }

    @Test
    fun guardianCanReApprove_previouslyRejectedApp() {
        fakeDao.savePendingApprovalBlocking(
            PendingApprovalEntity(
                packageName = "com.blocked.app",
                appName = "Blocked App",
                requestedAt = 1000L,
                status = PendingApprovalEntity.STATUS_REJECTED,
                decidedBy = "GuardianAlice",
                decidedAt = 1500L,
                isCloudSynced = true
            )
        )

        val result = repo.unsuspendAndApprove(
            packageName = "com.blocked.app",
            decidedBy = "GuardianAlice",
            syncToCloud = false
        )

        assertEquals(ApprovalDecisionResult.APPROVED, result)
        assertEquals(listOf(listOf("com.blocked.app")), dpmUnsuspendCalls)
        val stored = fakeDao.getApprovalByPackageNameBlocking("com.blocked.app")
        assertNotNull(stored)
        assertEquals(PendingApprovalEntity.STATUS_APPROVED, stored?.status)
        assertEquals("GuardianAlice", stored?.decidedBy)
        assertTrue(stored?.isCloudSynced == true)
    }

    @Test
    fun customBlacklist_blocksSelfApproval_withoutCallingDpmUnsuspend() {
        repo.policyProvider = {
            DevicePolicyBundle.defaultPolicy().copy(
                customBlacklistPackages = setOf("com.blacklisted.app")
            )
        }

        val result = repo.unsuspendAndApprove(
            packageName = "com.blacklisted.app",
            decidedBy = "SELF"
        )

        assertEquals(ApprovalDecisionResult.BLOCKED_BLACKLISTED, result)
        assertTrue("DPM unsuspend must NEVER be called for blacklisted app", dpmUnsuspendCalls.isEmpty())
    }

    @Test
    fun supervisedWard_withoutSelfControlAppPermissions_isBlockedLocally() {
        repo.policyProvider = {
            DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = true,
                guardianUid = "guardian_1",
                isSelfControlAllowed = true,
                selfControlNewAppsAllowed = false,
                selfControlAppsAllowed = false
            )
        }

        val result = repo.unsuspendAndApprove(
            packageName = "com.new.app",
            decidedBy = "SELF",
            syncToCloud = true
        )

        assertEquals(ApprovalDecisionResult.BLOCKED_SELF_CONTROL_FORBIDDEN, result)
        assertTrue("DPM unsuspend must NEVER be called when self-control app approval is forbidden", dpmUnsuspendCalls.isEmpty())
    }

    @Test
    fun supervisedWard_withSelfControlAppsAllowed_approvesAndSyncsAfterUpload() = runBlocking {
        repo.policyProvider = {
            DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = true,
                guardianUid = "guardian_1",
                isSelfControlAllowed = true,
                selfControlNewAppsAllowed = false,
                selfControlAppsAllowed = true
            )
        }
        repo.appLabelProvider = { "Cool App" }

        val result = repo.unsuspendAndApprove(
            packageName = "com.cool.app",
            decidedBy = "SELF",
            syncToCloud = true
        )

        assertEquals(ApprovalDecisionResult.APPROVED, result)
        assertEquals(listOf("com.cool.app"), cloudSyncCalls)

        val storedBeforeUpload = fakeDao.getApprovalByPackageNameBlocking("com.cool.app")
        assertNotNull(storedBeforeUpload)
        assertEquals("Cool App", storedBeforeUpload?.appName)
        assertEquals(PendingApprovalEntity.STATUS_APPROVED, storedBeforeUpload?.status)
        assertEquals("SELF", storedBeforeUpload?.decidedBy)
        assertFalse("Must remain unsynced until cloud upload succeeds", storedBeforeUpload?.isCloudSynced == true)

        repo.firestoreUploader = { true }
        repo.syncPendingApprovalsToCloud()

        val storedAfterUpload = fakeDao.getApprovalByPackageNameBlocking("com.cool.app")
        assertTrue("Must become cloud-synced once upload succeeds", storedAfterUpload?.isCloudSynced == true)
    }

    @Test
    fun uploadSingleApprovalSync_stalePendingUploadDoesNotOverwriteNewerLocalSelfDecision() = runBlocking {
        val pendingSnapshot = PendingApprovalEntity(
            packageName = "com.race.app",
            appName = "Race App",
            requestedAt = 1000L,
            status = PendingApprovalEntity.STATUS_PENDING,
            decidedBy = null,
            decidedAt = null,
            isCloudSynced = false
        )
        fakeDao.savePendingApprovalBlocking(pendingSnapshot)

        fakeNowMs = 2000L
        repo.firestoreUploader = {
            // Simulate user self-approving while the earlier PENDING upload is still in flight
            val approveResult = repo.unsuspendAndApprove(
                packageName = "com.race.app",
                decidedBy = "SELF",
                syncToCloud = true
            )
            assertEquals(ApprovalDecisionResult.APPROVED, approveResult)
            true
        }

        val uploaded = repo.uploadSingleApprovalSync(pendingSnapshot)
        assertTrue(uploaded)

        val currentRow = fakeDao.getApprovalByPackageNameBlocking("com.race.app")
        assertNotNull(currentRow)
        assertEquals(PendingApprovalEntity.STATUS_APPROVED, currentRow?.status)
        assertEquals(2000L, currentRow?.decidedAt)
        assertFalse(
            "Stale PENDING upload completion must NOT mark newer APPROVED decision as synced",
            currentRow?.isCloudSynced == true
        )
    }

    @Test
    fun uninstallAndReinstall_preservesRejectedStatus() {
        fakeDao.savePendingApprovalBlocking(
            PendingApprovalEntity(
                packageName = "com.rejected.app",
                appName = "Rejected App",
                requestedAt = 1000L,
                status = PendingApprovalEntity.STATUS_REJECTED,
                decidedBy = "GuardianAlice",
                decidedAt = 1500L,
                isCloudSynced = true
            )
        )

        repo.removePendingApproval("com.rejected.app")

        val afterUninstall = fakeDao.getApprovalByPackageNameBlocking("com.rejected.app")
        assertNotNull("REJECTED record must survive uninstall", afterUninstall)
        assertEquals(PendingApprovalEntity.STATUS_REJECTED, afterUninstall?.status)
        assertEquals("GuardianAlice", afterUninstall?.decidedBy)

        val frozen = repo.freezeAndSubmitApprovalLocal("com.rejected.app", showToast = false)
        assertTrue(frozen)
        assertTrue(
            "DPM freeze must be enforced on reinstall of rejected app",
            dpmSuspendCalls.any { it.first == "com.rejected.app" }
        )

        val afterReinstall = fakeDao.getApprovalByPackageNameBlocking("com.rejected.app")
        assertNotNull(afterReinstall)
        assertEquals(PendingApprovalEntity.STATUS_REJECTED, afterReinstall?.status)
        assertEquals("GuardianAlice", afterReinstall?.decidedBy)
    }

    @Test
    fun recordRejection_freezesImmediatelyAndMarksUnsyncedWhenSyncToCloudTrue() {
        repo.recordRejection(
            packageName = "com.self.rejected",
            decidedBy = "SELF",
            syncToCloud = true
        )

        assertTrue(
            "recordRejection must immediately freeze via DPM",
            dpmSuspendCalls.any { it.first == "com.self.rejected" }
        )
        assertEquals(listOf("com.self.rejected"), cloudSyncCalls)

        val stored = fakeDao.getApprovalByPackageNameBlocking("com.self.rejected")
        assertNotNull(stored)
        assertEquals(PendingApprovalEntity.STATUS_REJECTED, stored?.status)
        assertEquals("SELF", stored?.decidedBy)
        assertFalse(stored?.isCloudSynced == true)
    }

    @Test
    fun autoApprovedUnlocked_skipsCloudUploadAndMarksCloudSynced() = runBlocking {
        repo.recordApproval(
            packageName = "com.unlocked.app",
            appName = "Unlocked App",
            decidedBy = "AUTO_APPROVED_UNLOCKED"
        )

        val saved = fakeDao.getApprovalByPackageNameBlocking("com.unlocked.app")
        assertNotNull(saved)
        assertTrue(saved?.isCloudSynced == true)
        assertEquals("AUTO_APPROVED_UNLOCKED", saved?.decidedBy)

        val unsyncedCopy = saved!!.copy(isCloudSynced = false)
        fakeDao.savePendingApprovalBlocking(unsyncedCopy)

        var uploaderCalled = false
        repo.firestoreUploader = {
            uploaderCalled = true
            true
        }

        val result = repo.uploadSingleApprovalSync(unsyncedCopy)
        assertTrue(result)
        assertFalse("AUTO_APPROVED_UNLOCKED must never call Firestore uploader", uploaderCalled)
        val afterSync = fakeDao.getApprovalByPackageNameBlocking("com.unlocked.app")
        assertTrue(afterSync?.isCloudSynced == true)
    }

    @Test
    fun selfRejected_allowsSelfApprovalWithoutOverrideRejected() {
        fakeDao.savePendingApprovalBlocking(
            PendingApprovalEntity(
                packageName = "com.self.rejected.app",
                appName = "Self Rejected App",
                requestedAt = 1000L,
                status = PendingApprovalEntity.STATUS_REJECTED,
                decidedBy = "SELF",
                decidedAt = 1500L,
                isCloudSynced = true
            )
        )

        val result = repo.unsuspendAndApprove(
            packageName = "com.self.rejected.app",
            decidedBy = "SELF",
            syncToCloud = true,
            overrideRejected = false
        )

        assertEquals(ApprovalDecisionResult.APPROVED, result)
        assertEquals(listOf(listOf("com.self.rejected.app")), dpmUnsuspendCalls)
        val stored = fakeDao.getApprovalByPackageNameBlocking("com.self.rejected.app")
        assertNotNull(stored)
        assertEquals(PendingApprovalEntity.STATUS_APPROVED, stored?.status)
        assertEquals("SELF", stored?.decidedBy)
    }

    @Test
    fun guardianRejected_allowsSelfApprovalFromAppManagerWhenOverrideRejectedTrueAndSelfControlAppsAllowed() {
        repo.policyProvider = {
            DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = true,
                guardianUid = "guardian_1",
                isSelfControlAllowed = true,
                selfControlNewAppsAllowed = false,
                selfControlAppsAllowed = true
            )
        }
        fakeDao.savePendingApprovalBlocking(
            PendingApprovalEntity(
                packageName = "com.guardian.rejected.app",
                appName = "Guardian Rejected App",
                requestedAt = 1000L,
                status = PendingApprovalEntity.STATUS_REJECTED,
                decidedBy = "GuardianAlice",
                decidedAt = 1500L,
                isCloudSynced = true
            )
        )

        val result = repo.unsuspendAndApprove(
            packageName = "com.guardian.rejected.app",
            decidedBy = "SELF",
            syncToCloud = true,
            overrideRejected = true
        )

        assertEquals(ApprovalDecisionResult.APPROVED, result)
        assertEquals(listOf(listOf("com.guardian.rejected.app")), dpmUnsuspendCalls)
        val stored = fakeDao.getApprovalByPackageNameBlocking("com.guardian.rejected.app")
        assertNotNull(stored)
        assertEquals(PendingApprovalEntity.STATUS_APPROVED, stored?.status)
        assertEquals("SELF", stored?.decidedBy)
    }

    @Test
    fun migrateLegacyHighRiskFreezesIfNeeded_unsuspendsAndApprovesUnlocked_skipsBlacklistAndRejected_runsOnce() {
        val telegramPkg = "org.telegram.messenger"
        val blacklistedPkg = "com.twitter.android"
        val rejectedPkg = "com.zhiliaoapp.musically"
        val secondRunPkg = "com.reddit.frontpage"

        fun makePkgInfo(pkg: String) = PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                flags = 0
            }
            firstInstallTime = 1_700_000_000_000L
        }

        fakeDao.savePendingApprovalBlocking(
            PendingApprovalEntity(
                packageName = rejectedPkg,
                appName = "TikTok",
                requestedAt = 1000L,
                status = PendingApprovalEntity.STATUS_REJECTED,
                decidedBy = "GuardianAlice",
                decidedAt = 1500L,
                isCloudSynced = true
            )
        )

        repo.policyProvider = {
            DevicePolicyBundle.defaultPolicy().copy(
                lockNewInstallsUntilApproved = false,
                customBlacklistPackages = setOf(blacklistedPkg)
            )
        }
        var currentInstalled = listOf(
            makePkgInfo(telegramPkg),
            makePkgInfo(blacklistedPkg),
            makePkgInfo(rejectedPkg)
        )
        repo.installedPackagesProvider = { currentInstalled }
        repo.isPackageSuspendedProvider = { true }

        repo.migrateLegacyHighRiskFreezesIfNeeded()

        assertEquals(listOf(listOf(telegramPkg)), dpmUnsuspendCalls)
        val telegramRow = fakeDao.getApprovalByPackageNameBlocking(telegramPkg)
        assertNotNull(telegramRow)
        assertEquals(PendingApprovalEntity.STATUS_APPROVED, telegramRow?.status)
        assertEquals("AUTO_APPROVED_UNLOCKED", telegramRow?.decidedBy)
        assertTrue(telegramRow?.isCloudSynced == true)

        assertEquals(null, fakeDao.getApprovalByPackageNameBlocking(blacklistedPkg))
        val rejectedRow = fakeDao.getApprovalByPackageNameBlocking(rejectedPkg)
        assertEquals(PendingApprovalEntity.STATUS_REJECTED, rejectedRow?.status)
        assertEquals("GuardianAlice", rejectedRow?.decidedBy)

        // Second invocation must be a no-op because KEY_HIGH_RISK_NORMALIZATION_V1 is persisted
        dpmUnsuspendCalls.clear()
        currentInstalled = listOf(makePkgInfo(secondRunPkg))
        repo.migrateLegacyHighRiskFreezesIfNeeded()

        assertTrue("Migration must run only once", dpmUnsuspendCalls.isEmpty())
        assertEquals(null, fakeDao.getApprovalByPackageNameBlocking(secondRunPkg))
    }

    @Test
    fun guardianRejected_blocksSelfApproval_evenWithOverrideRejectedTrueWhenSelfControlAppsDisallowed() {
        repo.policyProvider = {
            DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = true,
                guardianUid = "guardian_1",
                isSelfControlAllowed = true,
                selfControlNewAppsAllowed = true,
                selfControlAppsAllowed = false
            )
        }
        fakeDao.savePendingApprovalBlocking(
            PendingApprovalEntity(
                packageName = "com.guardian.rejected.app",
                appName = "Guardian Rejected App",
                requestedAt = 1000L,
                status = PendingApprovalEntity.STATUS_REJECTED,
                decidedBy = "GuardianAlice",
                decidedAt = 1500L,
                isCloudSynced = true
            )
        )

        val result = repo.unsuspendAndApprove(
            packageName = "com.guardian.rejected.app",
            decidedBy = "SELF",
            syncToCloud = true,
            overrideRejected = true
        )

        assertEquals(ApprovalDecisionResult.BLOCKED_REJECTED, result)
        assertTrue("DPM unsuspend must NOT be called when selfControlAppsAllowed=false", dpmUnsuspendCalls.isEmpty())
    }

    @Test
    fun recordApproval_neverOverwritesExistingRejectedRecord() {
        fakeDao.savePendingApprovalBlocking(
            PendingApprovalEntity(
                packageName = "com.guardian.rejected",
                appName = "Rejected",
                requestedAt = 1000L,
                status = PendingApprovalEntity.STATUS_REJECTED,
                decidedBy = "GuardianAlice",
                decidedAt = 1500L,
                isCloudSynced = true
            )
        )

        repo.recordApproval("com.guardian.rejected", "Rejected", "AUTO_APPROVED_UNLOCKED")

        val stored = fakeDao.getApprovalByPackageNameBlocking("com.guardian.rejected")
        assertEquals(PendingApprovalEntity.STATUS_REJECTED, stored?.status)
        assertEquals("GuardianAlice", stored?.decidedBy)
    }

    @Test
    fun migrateLegacyHighRiskFreezesIfNeeded_retriesAfterDpmFailure_andUnhidesDuringCurfew() {
        val hiddenPkg = "org.telegram.messenger"
        val pkgInfo = PackageInfo().apply {
            packageName = hiddenPkg
            applicationInfo = ApplicationInfo().apply {
                packageName = hiddenPkg
                flags = 0
            }
            firstInstallTime = 1_700_000_000_000L
        }
        val unhideCalls = mutableListOf<String>()
        var failSuspend = true

        repo.policyProvider = { DevicePolicyBundle.defaultPolicy().copy(lockNewInstallsUntilApproved = false) }
        repo.installedPackagesProvider = { listOf(pkgInfo) }
        repo.isBlockActiveProvider = { true }
        repo.isAppHiddenProvider = { true }
        repo.isPackageSuspendedProvider = { true }
        repo.setAppHiddenProvider = { pkg, hidden ->
            if (!hidden) unhideCalls.add(pkg)
            true
        }
        repo.setPackagesSuspendedProvider = { pkgs, suspended ->
            if (!suspended) dpmUnsuspendCalls.add(pkgs.toList())
            !failSuspend
        }

        // During active curfew: unhides hidden app without lifting curfew suspension
        repo.migrateLegacyHighRiskFreezesIfNeeded()
        assertEquals(listOf(hiddenPkg), unhideCalls)
        assertTrue("Curfew active must keep package suspended", dpmUnsuspendCalls.isEmpty())

        // Reset completion via a fresh scenario where DPM unsuspend fails then retries cleanly
        val retryContext = FakeContext()
        val retryDao = FakeTestPendingApprovalDao()
        val retryRepo = ApprovalRepository(retryContext, retryDao).apply {
            policyProvider = { DevicePolicyBundle.defaultPolicy().copy(lockNewInstallsUntilApproved = false) }
            currentTimeMillisProvider = { fakeNowMs }
            isDeviceOwnerProvider = { true }
            isBlockActiveProvider = { false }
            immunePackagesProvider = { emptySet() }
            appLabelProvider = { it }
            isAppHiddenProvider = { false }
            isPackageSuspendedProvider = { true }
            setPackagesSuspendedProvider = { pkgs, suspended ->
                if (!suspended) dpmUnsuspendCalls.add(pkgs.toList())
                !failSuspend
            }
            watcherSyncer = {}
            systemCriticalChecker = { false }
            installedPackagesProvider = { listOf(pkgInfo) }
        }

        retryRepo.migrateLegacyHighRiskFreezesIfNeeded()
        assertEquals(1, dpmUnsuspendCalls.size)

        failSuspend = false
        retryRepo.migrateLegacyHighRiskFreezesIfNeeded()
        assertEquals("Migration must retry when prior DPM unsuspend failed", 2, dpmUnsuspendCalls.size)
    }

    @Test
    fun enforcePendingSuspensionsLocked_skipsAppsNoLongerPending() {
        fakeDao.savePendingApprovalBlocking(
            PendingApprovalEntity(
                packageName = "com.already.approved",
                appName = "Approved",
                requestedAt = 1000L,
                status = PendingApprovalEntity.STATUS_APPROVED,
                decidedBy = "SELF",
                decidedAt = 1500L,
                isCloudSynced = true
            )
        )
        fakeDao.savePendingApprovalBlocking(
            PendingApprovalEntity(
                packageName = "com.still.pending",
                appName = "Pending",
                requestedAt = 1000L,
                status = PendingApprovalEntity.STATUS_PENDING,
                isCloudSynced = false
            )
        )
        val suspendedCalls = mutableListOf<List<String>>()
        repo.isPackageSuspendedProvider = { false }
        repo.setPackagesSuspendedProvider = { pkgs, suspended ->
            if (suspended) suspendedCalls.add(pkgs.toList())
            true
        }

        repo.enforcePendingSuspensionsLocked(listOf("com.already.approved", "com.still.pending"))

        assertEquals(listOf(listOf("com.still.pending")), suspendedCalls)
    }

    private class NoOpInAppNotificationDao : InAppNotificationDao {
        private val flow = MutableStateFlow<List<InAppNotificationEntity>>(emptyList())
        private val countFlow = MutableStateFlow(0)
        override suspend fun insertOrReplace(notification: InAppNotificationEntity): Long = 1L
        override suspend fun getAllNotifications(limit: Int): List<InAppNotificationEntity> = emptyList()
        override fun observeAllNotifications(limit: Int): Flow<List<InAppNotificationEntity>> = flow.asStateFlow()
        override suspend fun getUnreadCount(): Int = 0
        override fun observeUnreadCount(): Flow<Int> = countFlow.asStateFlow()
        override suspend fun getByDedupKey(dedupKey: String): InAppNotificationEntity? = null
        override fun getByDedupKeyBlocking(dedupKey: String): InAppNotificationEntity? = null
        override suspend fun markAsRead(id: Long): Int = 0
        override suspend fun markAllAsRead(): Int = 0
        override suspend fun deleteById(id: Long): Int = 0
        override suspend fun clearAll(): Int = 0
        override suspend fun getTotalCount(): Int = 0
        override suspend fun deleteOlderThan(olderThanMs: Long): Int = 0
        override suspend fun deleteOldest(count: Int): Int = 0
    }

    private class FakeContext : Application() {
        private val prefsByName = mutableMapOf<String, InMemoryTestSharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getPackageName(): String = "com.drmohammed.antiporn"

        override fun getPackageManager(): PackageManager? = null

        override fun getSystemService(name: String): Any? = null

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefsByName.getOrPut(name ?: "default") { InMemoryTestSharedPreferences() }
        }

        private val fakeResources: Resources by lazy {
            @Suppress("DEPRECATION")
            object : Resources(null, null, null) {
                override fun getString(id: Int): String = resolveString(id)
                override fun getString(id: Int, vararg formatArgs: Any): String = resolveString(id, *formatArgs)
            }
        }

        override fun getResources(): Resources = fakeResources

        private fun resolveString(resId: Int, vararg formatArgs: Any): String {
            val template = when (resId) {
                R.string.toast_app_suspended_by_guardian -> "App suspended pending guardian approval"
                R.string.toast_high_risk_app_suspended -> "High-risk app suspended pending guardian approval"
                R.string.notification_high_risk_app_frozen_title -> "High-risk app suspended"
                R.string.notification_high_risk_app_frozen_msg -> "High-risk app %1\$s suspended"
                R.string.guardian_default_name -> "Guardian"
                R.string.notif_approval_granted_title -> "App Approved"
                R.string.notif_approval_rejected_title -> "App Rejected"
                R.string.notif_self_qr_approval_granted_desc -> "Self-approved %1\$s via QR"
                R.string.notif_self_qr_approval_rejected_desc -> "Self-rejected %1\$s"
                R.string.notif_self_qr_approval_deferred_curfew_desc -> "Self-approved %1\$s (deferred until curfew ends)"
                R.string.notif_approval_granted_desc -> "Guardian %1\$s approved %2\$s"
                R.string.notif_approval_rejected_desc -> "Guardian %1\$s rejected %2\$s"
                R.string.notif_approval_deferred_curfew_desc -> "%1\$s approved by %2\$s (deferred until curfew ends)"
                else -> "res_$resId"
            }
            return if (formatArgs.isEmpty()) template else String.format(template, *formatArgs)
        }
    }
}
