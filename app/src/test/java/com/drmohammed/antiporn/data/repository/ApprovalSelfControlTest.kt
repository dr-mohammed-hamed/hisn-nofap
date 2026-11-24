package com.drmohammed.antiporn.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.dao.InAppNotificationDao
import com.drmohammed.antiporn.data.local.db.dao.PendingApprovalDao
import com.drmohammed.antiporn.data.local.db.entity.InAppNotificationEntity
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.repository.NotificationRepository
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
    private lateinit var fakeDao: FakePendingApprovalDao
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
        fakeDao = FakePendingApprovalDao()

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

    private class FakePendingApprovalDao : PendingApprovalDao {
        private val lock = Any()
        private val store = linkedMapOf<String, PendingApprovalEntity>()
        private val flow = MutableStateFlow<List<PendingApprovalEntity>>(emptyList())

        private fun snapshotSortedDesc(): List<PendingApprovalEntity> =
            store.values.sortedByDescending { it.requestedAt }

        private fun emitLocked() {
            flow.value = snapshotSortedDesc()
        }

        override fun getPendingApprovalsFlow(): Flow<List<PendingApprovalEntity>> = flow.asStateFlow()

        override suspend fun getPendingApprovals(): List<PendingApprovalEntity> = getPendingApprovalsBlocking()

        override fun getPendingApprovalsBlocking(): List<PendingApprovalEntity> = synchronized(lock) {
            snapshotSortedDesc()
        }

        override suspend fun getActualPendingApprovals(): List<PendingApprovalEntity> = getActualPendingApprovalsBlocking()

        override fun getActualPendingApprovalsBlocking(): List<PendingApprovalEntity> = synchronized(lock) {
            store.values
                .filter { it.status == PendingApprovalEntity.STATUS_PENDING || it.status == PendingApprovalEntity.STATUS_FALLBACK_HIDDEN }
                .sortedByDescending { it.requestedAt }
        }

        override suspend fun getUnsyncedApprovals(): List<PendingApprovalEntity> = getUnsyncedApprovalsBlocking()

        override fun getUnsyncedApprovalsBlocking(): List<PendingApprovalEntity> = synchronized(lock) {
            store.values.filter { !it.isCloudSynced }.sortedBy { it.requestedAt }
        }

        override suspend fun getApprovalByPackageName(packageName: String): PendingApprovalEntity? =
            getApprovalByPackageNameBlocking(packageName)

        override fun getApprovalByPackageNameBlocking(packageName: String): PendingApprovalEntity? = synchronized(lock) {
            store[packageName]
        }

        override suspend fun savePendingApproval(approval: PendingApprovalEntity) =
            savePendingApprovalBlocking(approval)

        override fun savePendingApprovalBlocking(approval: PendingApprovalEntity) = synchronized(lock) {
            store[approval.packageName] = approval
            emitLocked()
        }

        override suspend fun savePendingApprovals(approvals: List<PendingApprovalEntity>) =
            savePendingApprovalsBlocking(approvals)

        override fun savePendingApprovalsBlocking(approvals: List<PendingApprovalEntity>) = synchronized(lock) {
            for (approval in approvals) {
                store[approval.packageName] = approval
            }
            emitLocked()
        }

        override suspend fun updateApprovalStatus(
            packageName: String,
            status: String,
            decidedBy: String?,
            decidedAt: Long,
            isSynced: Int
        ): Int = updateApprovalStatusBlocking(packageName, status, decidedBy, decidedAt, isSynced)

        override fun updateApprovalStatusBlocking(
            packageName: String,
            status: String,
            decidedBy: String?,
            decidedAt: Long,
            isSynced: Int
        ): Int = synchronized(lock) {
            val existing = store[packageName] ?: return 0
            store[packageName] = existing.copy(
                status = status,
                decidedBy = decidedBy,
                decidedAt = decidedAt,
                isCloudSynced = isSynced == 1
            )
            emitLocked()
            1
        }

        override suspend fun markApprovalCloudSynced(packageName: String, isSynced: Int): Int =
            markApprovalCloudSyncedBlocking(packageName, isSynced)

        override fun markApprovalCloudSyncedBlocking(packageName: String, isSynced: Int): Int = synchronized(lock) {
            val existing = store[packageName] ?: return 0
            store[packageName] = existing.copy(isCloudSynced = isSynced == 1)
            emitLocked()
            1
        }

        override fun markApprovalCloudSyncedIfMatchingBlocking(
            packageName: String,
            expectedStatus: String,
            expectedDecidedAt: Long?,
            isSynced: Int
        ): Int = synchronized(lock) {
            val existing = store[packageName] ?: return 0
            val matchesStatus = existing.status == expectedStatus
            val matchesDecidedAt = (existing.decidedAt ?: 0L) == (expectedDecidedAt ?: 0L)
            if (!matchesStatus || !matchesDecidedAt) return 0
            store[packageName] = existing.copy(isCloudSynced = isSynced == 1)
            emitLocked()
            1
        }

        override suspend fun deletePendingApproval(packageName: String): Int =
            deletePendingApprovalBlocking(packageName)

        override fun deletePendingApprovalBlocking(packageName: String): Int = synchronized(lock) {
            val removed = store.remove(packageName)
            if (removed != null) {
                emitLocked()
                1
            } else {
                0
            }
        }

        override fun clearAllPendingApprovalsBlocking(): Int = synchronized(lock) {
            val count = store.size
            store.clear()
            emitLocked()
            count
        }
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
        private val prefsByName = mutableMapOf<String, InMemorySharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getPackageName(): String = "com.drmohammed.antiporn"

        override fun getPackageManager(): PackageManager? = null

        override fun getSystemService(name: String): Any? = null

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefsByName.getOrPut(name ?: "default") { InMemorySharedPreferences() }
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

    private class InMemorySharedPreferences : SharedPreferences {
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
                if (clearAll) {
                    values.clear()
                    clearAll = false
                }
                for ((k, v) in pending) {
                    if (v == null) values.remove(k) else values[k] = v
                }
                pending.clear()
            }
        }
    }
}
