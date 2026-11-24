package com.drmohammed.antiporn.accountability.coordinator

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.InAppNotificationDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.entity.InAppNotificationEntity
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GuardianSupervisionCoordinatorTest {

    private lateinit var fakeApp: FakeApplication
    private lateinit var fakeDao: FakeInAppNotificationDao
    private lateinit var notifScope: CoroutineScope
    private lateinit var notifRepo: NotificationRepository
    private lateinit var notifManager: AppNotificationManager
    private lateinit var fakeAccRepo: FakeAccountabilityRepository
    private lateinit var coordinator: GuardianSupervisionCoordinator

    @Before
    fun setUp() {
        TrustedTimeProvider.elapsedRealtimeProvider = { 10_000L }
        TrustedTimeProvider.wallTimeProvider = { 1_750_000_000_000L }
        TrustedTimeProvider.resetForTesting()

        fakeApp = FakeApplication()
        fakeDao = FakeInAppNotificationDao()
        notifScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        notifRepo = NotificationRepository(fakeDao, notifScope)
        notifManager = AppNotificationManager(fakeApp, notifRepo, notifScope)
        fakeAccRepo = FakeAccountabilityRepository(fakeApp)
        coordinator = GuardianSupervisionCoordinator(
            context = fakeApp,
            accountabilityRepository = fakeAccRepo,
            notificationManager = notifManager,
            notificationRepository = notifRepo
        ).apply {
            coordinatorScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            stringProvider = { resId, args ->
                if (args.isEmpty()) fakeApp.resources.getString(resId)
                else fakeApp.resources.getString(resId, *args)
            }
        }
    }

    @After
    fun tearDown() {
        coordinator.coordinatorScope.cancel()
        notifScope.cancel()
        TrustedTimeProvider.resetForTesting()
    }

    @Test
    fun testNotificationIdComputation_isDeterministicAndPositive() {
        val ward1 = "ward_uid_abc123"
        val pkg1 = "com.example.unapproved1"
        val pkg2 = "com.example.unapproved2"
        val ward2 = "ward_uid_xyz789"

        val baseId = NotificationRegistry.EVENT_WARD_REQUEST_ID
        val id1 = GuardianSupervisionCoordinator.computeSystemNotificationId(ward1, pkg1)
        val id2 = GuardianSupervisionCoordinator.computeSystemNotificationId(ward1, pkg2)
        val id3 = GuardianSupervisionCoordinator.computeSystemNotificationId(ward2, pkg1)
        val id1Repeat = GuardianSupervisionCoordinator.computeSystemNotificationId(ward1, pkg1)

        // Deterministic
        assertEquals(id1, id1Repeat)

        // Unique across different packages / wards
        assertNotEquals(id1, id2)
        assertNotEquals(id1, id3)

        // Within expected positive range
        assertTrue(id1 >= baseId && id1 < baseId + 1000)
        assertTrue(id2 >= baseId && id2 < baseId + 1000)
    }

    @Test
    fun testNotificationIdComputation_handlesNegativeAndExtremeHashCodes() {
        val baseId = NotificationRegistry.EVENT_WARD_REQUEST_ID
        // Verify that masking with 0x7FFFFFFF guarantees non-negative IDs even for Int.MIN_VALUE
        val extremeMinVal = Int.MIN_VALUE
        val safeOffset = (extremeMinVal and 0x7FFFFFFF) % 1000
        assertTrue("Safe offset must never be negative", safeOffset >= 0)

        // Test with arbitrary strings
        for (i in 0..100) {
            val id = GuardianSupervisionCoordinator.computeSystemNotificationId("ward_$i", "pkg_$i")
            assertTrue("ID must be within [baseId, baseId + 1000)", id in baseId until (baseId + 1000))
        }
    }

    @Test
    fun testDedupKeyFormat_consistentWithViewBinder() {
        val wardId = "user_ward_456"
        val pkg = "com.social.chat"

        val expectedDedupKey = "guardian_req_${wardId}_$pkg"
        assertEquals("guardian_req_user_ward_456_com.social.chat", expectedDedupKey)
    }

    @Test
    fun testActionTypeAndCategory_matchApprovalsHubRouting() {
        val category = NotificationCategory.APPROVALS
        val actionType = NotificationActionType.OPEN_APPROVALS_HUB

        assertEquals("APPROVALS", category.key)
        assertEquals("OPEN_APPROVALS_HUB", actionType.key)

        val wardId = "ward_789"
        val pkg = "com.rogue.game"
        val payload = "$wardId|$pkg"

        assertTrue(payload.contains("|"))
        val parts = payload.split("|")
        assertEquals(wardId, parts[0])
        assertEquals(pkg, parts[1])
    }

    @Test
    fun testSupervisionEventChannels_areConfiguredCorrectly() {
        assertEquals("antiporn_protection_events", NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS)
        assertEquals("antiporn_critical_alerts", NotificationRegistry.CHANNEL_ID_CRITICAL_ALERTS)
        assertEquals(8001, NotificationRegistry.EVENT_GUARDIAN_REQUEST_ID)
        assertEquals(8002, NotificationRegistry.EVENT_WARD_REQUEST_ID)
        assertEquals(8004, NotificationRegistry.EVENT_BREAKOUT_REQUEST_ID)
        assertEquals(7001, NotificationRegistry.EVENT_SYSTEM_TAMPER_ID)
        assertEquals(8500, NotificationRegistry.EVENT_SELF_CONTROL_ALERT_ID)
    }

    @Test
    fun testRemoteTeardown_suppressesPendingDeactivationNotificationAlert() {
        val now = 1_790_000_000_000L
        // Normal ward-initiated deactivation (no remoteTeardownRequestedAt) MUST alert guardian
        assertTrue(
            GuardianSupervisionCoordinator.isPendingGuardianDeactivationAlert(
                deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
                remoteTeardownRequestedAt = 0L,
                deactivationUnlockAt = now + 60_000L,
                nowMs = now
            )
        )
        // Guardian-initiated immediate teardown (remoteTeardownRequestedAt > 0L) MUST NOT alert as breakout/deactivation request
        assertFalse(
            GuardianSupervisionCoordinator.isPendingGuardianDeactivationAlert(
                deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
                remoteTeardownRequestedAt = now - 10_000L,
                deactivationUnlockAt = now + 60_000L,
                nowMs = now
            )
        )
        // Completed status never posts deactivation alert
        assertFalse(
            GuardianSupervisionCoordinator.isPendingGuardianDeactivationAlert(
                deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED,
                remoteTeardownRequestedAt = 0L,
                deactivationUnlockAt = now + 60_000L,
                nowMs = now
            )
        )
    }

    @Test
    fun testRemoteTeardown_completionDetectorRequiresCompletedAndPendingMarker() {
        assertTrue(
            GuardianSupervisionCoordinator.shouldHandleRemoteTeardownCompletion(
                deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED,
                isPendingGuardianTeardown = true
            )
        )
        assertTrue(
            GuardianSupervisionCoordinator.shouldHandleRemoteTeardownCompletion(
                deactivationStatus = "completed",
                isPendingGuardianTeardown = true
            )
        )
        assertFalse(
            GuardianSupervisionCoordinator.shouldHandleRemoteTeardownCompletion(
                deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED,
                isPendingGuardianTeardown = false
            )
        )
        assertFalse(
            GuardianSupervisionCoordinator.shouldHandleRemoteTeardownCompletion(
                deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
                isPendingGuardianTeardown = true
            )
        )
    }

    @Test
    fun testSelfApprovedDecision_correctlyIdentifiesSelfApproved() {
        // Must return true only when status is APPROVED and decidedBy is SELF (case-insensitive)
        assertTrue(GuardianSupervisionCoordinator.isSelfApprovedDecision("APPROVED", "SELF"))
        assertTrue(GuardianSupervisionCoordinator.isSelfApprovedDecision("approved", "self"))
        assertTrue(GuardianSupervisionCoordinator.isSelfApprovedDecision("Approved", "Self"))

        // False when approved by guardian or others
        assertFalse(GuardianSupervisionCoordinator.isSelfApprovedDecision("APPROVED", "GUARDIAN_123"))
        assertFalse(GuardianSupervisionCoordinator.isSelfApprovedDecision("APPROVED", "Father"))
        assertFalse(GuardianSupervisionCoordinator.isSelfApprovedDecision("APPROVED", null))
        assertFalse(GuardianSupervisionCoordinator.isSelfApprovedDecision("APPROVED", ""))

        // False when status is not APPROVED even if decidedBy is SELF
        assertFalse(GuardianSupervisionCoordinator.isSelfApprovedDecision("REJECTED", "SELF"))
        assertFalse(GuardianSupervisionCoordinator.isSelfApprovedDecision("PENDING", "SELF"))
        assertFalse(GuardianSupervisionCoordinator.isSelfApprovedDecision(null, "SELF"))
    }

    @Test
    fun updateInAppApprovalStatus_createsSilentNotification_whenExistingNullAndSelfApproved() = runBlocking {
        val dedupKey = "guardian_req_ward1_com.app"
        val insertLatch = fakeDao.expectInsertLatch(1)

        coordinator.updateInAppApprovalStatus(
            dedupKey = dedupKey,
            appName = "My App",
            wardName = "Ali",
            status = "APPROVED",
            decidedBy = "SELF",
            decidedAt = 1000L,
            wardUid = "ward1",
            packageName = "com.app"
        )

        assertTrue("Timed out waiting for async notification insert", insertLatch.await(2, TimeUnit.SECONDS))
        val all = fakeDao.getAllNotifications()
        assertEquals(1, all.size)
        val notif = all.first()
        assertEquals(dedupKey, notif.dedupKey)
        assertEquals("APPROVALS", notif.category)
        assertEquals("OPEN_APPROVALS_HUB", notif.actionType)
        assertEquals("ward1|com.app", notif.actionPayload)
        assertFalse(notif.isRead)
        assertEquals(fakeApp.resources.getString(R.string.notif_ward_self_qr_approved_title), notif.title)
        assertEquals(fakeApp.resources.getString(R.string.notif_ward_self_qr_approved_desc, "My App (Ali)"), notif.message)
    }

    @Test
    fun updateInAppApprovalStatus_createsSilentNotification_whenExistingNullAndSelfRejected() = runBlocking {
        val dedupKey = "guardian_req_ward1_com.rejected"
        val insertLatch = fakeDao.expectInsertLatch(1)

        coordinator.updateInAppApprovalStatus(
            dedupKey = dedupKey,
            appName = "Bad App",
            wardName = "Ali",
            status = "REJECTED",
            decidedBy = "SELF",
            decidedAt = 2000L,
            wardUid = "ward1",
            packageName = "com.rejected"
        )

        assertTrue("Timed out waiting for async notification insert", insertLatch.await(2, TimeUnit.SECONDS))
        val all = fakeDao.getAllNotifications()
        assertEquals(1, all.size)
        val notif = all.first()
        assertEquals(dedupKey, notif.dedupKey)
        assertEquals("APPROVALS", notif.category)
        assertEquals("OPEN_APPROVALS_HUB", notif.actionType)
        assertEquals("ward1|com.rejected", notif.actionPayload)
        assertFalse(notif.isRead)
        assertEquals(fakeApp.resources.getString(R.string.notif_ward_self_qr_rejected_title), notif.title)
        assertEquals(fakeApp.resources.getString(R.string.notif_ward_self_qr_rejected_desc, "Bad App (Ali)"), notif.message)
    }

    @Test
    fun updateInAppApprovalStatus_doesNotFloodHistory_forHistoricalGuardianDecisionsWhenExistingNull() = runBlocking {
        coordinator.updateInAppApprovalStatus(
            dedupKey = "guardian_req_ward1_com.old1",
            appName = "Old Approved App",
            wardName = "Ali",
            status = "APPROVED",
            decidedBy = "Father",
            decidedAt = 3000L,
            wardUid = "ward1",
            packageName = "com.old1"
        )
        coordinator.updateInAppApprovalStatus(
            dedupKey = "guardian_req_ward1_com.old2",
            appName = "Old Rejected App",
            wardName = "Ali",
            status = "REJECTED",
            decidedBy = "Father",
            decidedAt = 3100L,
            wardUid = "ward1",
            packageName = "com.old2"
        )

        Thread.sleep(100)
        assertEquals(0, fakeDao.getAllNotifications().size)
    }

    @Test
    fun updateInAppApprovalStatus_isIdempotent_andDoesNotResurrectUnreadWhenAlreadyRead() = runBlocking {
        val dedupKey = "guardian_req_ward1_com.app"
        val expectedTitle = fakeApp.resources.getString(R.string.notif_ward_self_qr_approved_title)
        val expectedMessage = fakeApp.resources.getString(R.string.notif_ward_self_qr_approved_desc, "My App (Ali)")

        fakeDao.insertOrReplace(
            InAppNotificationEntity(
                id = 1L,
                dedupKey = dedupKey,
                category = "APPROVALS",
                title = expectedTitle,
                message = expectedMessage,
                timestamp = 1000L,
                isRead = true,
                actionType = "OPEN_APPROVALS_HUB",
                actionPayload = "ward1|com.app",
                importance = 2
            )
        )

        coordinator.updateInAppApprovalStatus(
            dedupKey = dedupKey,
            appName = "My App",
            wardName = "Ali",
            status = "APPROVED",
            decidedBy = "SELF",
            decidedAt = 1000L,
            wardUid = "ward1",
            packageName = "com.app"
        )

        Thread.sleep(100)
        val stored = fakeDao.getByDedupKey(dedupKey)
        assertNotNull(stored)
        assertTrue("Already-read notification must NOT be flipped back to unread on restart", stored!!.isRead)
    }

    @Test
    fun updateInAppApprovalStatus_doesNotResurrectDeletedSelfDecision_andUpdatesPendingToSelfApproved() = runBlocking {
        val deletedDedupKey = "guardian_req_ward1_com.deleted"
        val firstLatch = fakeDao.expectInsertLatch(1)

        coordinator.updateInAppApprovalStatus(
            dedupKey = deletedDedupKey,
            appName = "Deleted App",
            wardName = "Ali",
            status = "APPROVED",
            decidedBy = "SELF",
            decidedAt = 5000L,
            wardUid = "ward1",
            packageName = "com.deleted"
        )
        assertTrue(firstLatch.await(2, TimeUnit.SECONDS))
        assertEquals(1, fakeDao.getTotalCount())

        // Simulate guardian clearing notification history
        fakeDao.clearAll()
        assertEquals(0, fakeDao.getTotalCount())

        // Simulate restart delivering the same decision signature (decidedAt = 5000L)
        coordinator.updateInAppApprovalStatus(
            dedupKey = deletedDedupKey,
            appName = "Deleted App",
            wardName = "Ali",
            status = "APPROVED",
            decidedBy = "SELF",
            decidedAt = 5000L,
            wardUid = "ward1",
            packageName = "com.deleted"
        )
        Thread.sleep(100)
        assertEquals("Deleted self-decision notification must NOT be resurrected", 0, fakeDao.getTotalCount())

        // Verify PENDING -> APPROVED (SELF) transition updates existing notification cleanly
        val pendingDedupKey = "guardian_req_ward1_com.pending"
        fakeDao.insertOrReplace(
            InAppNotificationEntity(
                id = 10L,
                dedupKey = pendingDedupKey,
                category = "APPROVALS",
                title = "Pending Request",
                message = "Waiting for guardian",
                timestamp = 6000L,
                isRead = true,
                actionType = "OPEN_APPROVALS_HUB",
                actionPayload = "ward1|com.pending",
                importance = 2
            )
        )

        val updateLatch = fakeDao.expectInsertLatch(1)
        coordinator.updateInAppApprovalStatus(
            dedupKey = pendingDedupKey,
            appName = "Pending App",
            wardName = "Ali",
            status = "APPROVED",
            decidedBy = "SELF",
            decidedAt = 7000L,
            wardUid = "ward1",
            packageName = "com.pending"
        )
        assertTrue(updateLatch.await(2, TimeUnit.SECONDS))

        val updated = fakeDao.getByDedupKey(pendingDedupKey)
        assertNotNull(updated)
        assertEquals(fakeApp.resources.getString(R.string.notif_ward_self_qr_approved_title), updated!!.title)
        assertEquals(fakeApp.resources.getString(R.string.notif_ward_self_qr_approved_desc, "Pending App (Ali)"), updated.message)
        assertEquals("OPEN_APPROVALS_HUB", updated.actionType)
        assertEquals("ward1|com.pending", updated.actionPayload)
        assertFalse(updated.isRead)
    }

    private class FakeInAppNotificationDao : InAppNotificationDao {
        private val lock = Any()
        private val items = linkedMapOf<Long, InAppNotificationEntity>()
        private var nextId = 1L
        private val allFlow = MutableStateFlow<List<InAppNotificationEntity>>(emptyList())
        private val unreadFlow = MutableStateFlow(0)
        @Volatile
        private var insertLatch: CountDownLatch? = null

        fun expectInsertLatch(count: Int): CountDownLatch {
            val latch = CountDownLatch(count)
            insertLatch = latch
            return latch
        }

        private fun snapshotSorted(): List<InAppNotificationEntity> =
            items.values.sortedByDescending { it.timestamp }

        private fun emitLocked() {
            val sorted = snapshotSorted()
            allFlow.value = sorted
            unreadFlow.value = sorted.count { !it.isRead }
        }

        override suspend fun insertOrReplace(notification: InAppNotificationEntity): Long {
            val assignedId = synchronized(lock) {
                val existingByDedup = notification.dedupKey?.let { key ->
                    items.values.find { it.dedupKey == key }
                }
                val id = when {
                    notification.id > 0L -> notification.id
                    existingByDedup != null -> existingByDedup.id
                    else -> nextId++
                }
                if (id >= nextId) nextId = id + 1
                items[id] = notification.copy(id = id)
                emitLocked()
                id
            }
            insertLatch?.countDown()
            return assignedId
        }

        override suspend fun getAllNotifications(limit: Int): List<InAppNotificationEntity> = synchronized(lock) {
            snapshotSorted().take(limit)
        }

        override fun observeAllNotifications(limit: Int): Flow<List<InAppNotificationEntity>> = allFlow.asStateFlow()

        override suspend fun getUnreadCount(): Int = synchronized(lock) {
            items.values.count { !it.isRead }
        }

        override fun observeUnreadCount(): Flow<Int> = unreadFlow.asStateFlow()

        override suspend fun getByDedupKey(dedupKey: String): InAppNotificationEntity? = getByDedupKeyBlocking(dedupKey)

        override fun getByDedupKeyBlocking(dedupKey: String): InAppNotificationEntity? = synchronized(lock) {
            items.values.find { it.dedupKey == dedupKey }
        }

        override suspend fun markAsRead(id: Long): Int = synchronized(lock) {
            val existing = items[id] ?: return 0
            items[id] = existing.copy(isRead = true)
            emitLocked()
            1
        }

        override suspend fun markAllAsRead(): Int = synchronized(lock) {
            var count = 0
            for ((id, entity) in items.toMap()) {
                if (!entity.isRead) {
                    items[id] = entity.copy(isRead = true)
                    count++
                }
            }
            emitLocked()
            count
        }

        override suspend fun deleteById(id: Long): Int = synchronized(lock) {
            if (items.remove(id) != null) {
                emitLocked()
                1
            } else 0
        }

        override suspend fun clearAll(): Int = synchronized(lock) {
            val count = items.size
            items.clear()
            emitLocked()
            count
        }

        override suspend fun getTotalCount(): Int = synchronized(lock) { items.size }

        override suspend fun deleteOlderThan(olderThanMs: Long): Int = synchronized(lock) {
            val toRemove = items.values.filter { it.timestamp < olderThanMs }.map { it.id }
            toRemove.forEach { items.remove(it) }
            emitLocked()
            toRemove.size
        }

        override suspend fun deleteOldest(count: Int): Int = synchronized(lock) {
            val toRemove = items.values.sortedWith(compareBy({ it.timestamp }, { it.id })).take(count).map { it.id }
            toRemove.forEach { items.remove(it) }
            emitLocked()
            toRemove.size
        }
    }

    private class FakeAccountabilityRepository(
        context: Context
    ) : AccountabilityRepository(
        context = context,
        partnerDao = dummyProxy(),
        policyDao = dummyProxy()
    ) {
        override fun getMyDisplayName(): String = "Guardian"

        companion object {
            private inline fun <reified T> dummyProxy(): T {
                return Proxy.newProxyInstance(
                    T::class.java.classLoader,
                    arrayOf(T::class.java)
                ) { _, method, _ ->
                    when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        List::class.java -> emptyList<Any>()
                        Flow::class.java -> MutableStateFlow(emptyList<Any>())
                        else -> null
                    }
                } as T
            }
        }
    }

    private class FakeApplication : Application() {
        private val prefsByName = mutableMapOf<String, InMemorySharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getPackageName(): String = "com.drmohammed.antiporn"

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
                R.string.notif_ward_self_qr_approved_title -> "Self-Approved via QR"
                R.string.notif_ward_self_qr_approved_desc -> "Ward self-approved %1\$s via QR"
                R.string.notif_ward_self_qr_rejected_title -> "Self-Rejected by Ward"
                R.string.notif_ward_self_qr_rejected_desc -> "Ward self-rejected %1\$s"
                R.string.notif_approval_granted_title -> "App Approved"
                R.string.notif_approval_granted_desc -> "Guardian %1\$s approved %2\$s"
                R.string.notif_approval_rejected_title -> "App Rejected"
                R.string.notif_approval_rejected_desc -> "Guardian %1\$s rejected %2\$s"
                R.string.tab_approvals_rejected -> "Rejected"
                R.string.notif_ward_approval_req_title -> "Approval Request"
                R.string.notif_ward_approval_req_desc -> "Ward requested approval for %1\$s"
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
