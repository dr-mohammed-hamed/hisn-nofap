package com.drmohammed.antiporn.ui.accountability

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.InAppNotificationDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.dao.PendingApprovalDao
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.InAppNotificationEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.repository.NotificationRepository
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianApprovalsHubViewModelTest {

    private fun createViewModel(
        scope: CoroutineScope,
        wards: List<PartnerEntity> = emptyList(),
        resolveApprovalAction: suspend (WardApprovalModel, Boolean) -> Boolean = { _, _ -> true },
        onCancelNotif: (Int) -> Unit = {}
    ): GuardianApprovalsHubViewModel {
        val app = FakeApplication()
        val partnerDao = FakePartnerDao(wards)
        val policyDao = FakeDevicePolicyDao()
        val approvalDao = FakePendingApprovalDao()
        val notifManager = AppNotificationManager(app, NotificationRepository(FakeInAppNotificationDao()), scope)
        val accRepo = AccountabilityRepository(app, partnerDao, policyDao)
        val approvalRepo = ApprovalRepository(app, approvalDao)

        return GuardianApprovalsHubViewModel(
            application = app,
            accountabilityRepository = accRepo,
            approvalRepository = approvalRepo,
            partnerDao = partnerDao,
            appNotificationManager = notifManager
        ).apply {
            this.ioDispatcher = Dispatchers.Unconfined
            this.defaultDispatcher = Dispatchers.Unconfined
            this.coroutineScopeOverride = scope
            this.attachListenersOverride = { _ -> }
            this.resolveApprovalAction = resolveApprovalAction
            this.cancelSystemNotificationAction = onCancelNotif
            this.nowProvider = { 1_700_000_000_000L }
        }
    }

    @Test
    fun `initialize preserves tab selection on rotation and resets on forceReset`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(scope)
            vm.initialize(targetWardId = "ward-1", targetPackage = "com.example.app", forceReset = false)
            vm.selectTab(2)
            assertEquals(2, vm.uiState.value.selectedTabIndex)

            // Rotation (forceReset = false) must keep selectedTabIndex
            vm.initialize(targetWardId = "ward-1", targetPackage = "com.example.app", forceReset = false)
            assertEquals(2, vm.uiState.value.selectedTabIndex)

            // New Intent (forceReset = true) resets selectedTabIndex & target
            vm.initialize(targetWardId = "ward-2", targetPackage = "com.other.app", forceReset = true)
            assertEquals(0, vm.uiState.value.selectedTabIndex)
            assertEquals("ward-2", vm.uiState.value.targetWardId)
            assertEquals("com.other.app", vm.uiState.value.targetPackage)
            assertFalse(vm.uiState.value.hasAutoSelectedTargetTab)
            assertFalse(vm.uiState.value.hasScrolledToTarget)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `processWardApprovals auto-selects tab matching targetPackage only once`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(scope)
            vm.initialize(targetWardId = "ward-1", targetPackage = "com.target.app", forceReset = false)

            val approvedItem = WardApprovalModel(
                packageName = "com.target.app",
                appName = "Target App",
                wardId = "ward-1",
                wardName = "Ward",
                status = "APPROVED",
                requestedAt = 1000L
            )
            vm.processWardApprovals("ward-1", listOf(approvedItem))

            assertTrue(vm.uiState.value.hasAutoSelectedTargetTab)
            assertEquals(1, vm.uiState.value.selectedTabIndex)
            assertEquals(1, vm.uiState.value.filteredApprovalsForSelectedTab().size)

            // User switches to Pending tab; subsequent snapshot must not hijack tab back
            vm.selectTab(0)
            vm.processWardApprovals("ward-1", listOf(approvedItem))
            assertEquals(0, vm.uiState.value.selectedTabIndex)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resolveApproval tracks inFlightApprovalKeys and cancels notification on success`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = CompletableDeferred<Unit>()
            var cancelledNotifId: Int? = null
            val vm = createViewModel(
                scope = scope,
                resolveApprovalAction = { _, _ ->
                    gate.await()
                    true
                },
                onCancelNotif = { cancelledNotifId = it }
            )
            val item = WardApprovalModel(
                packageName = "com.pkg.test",
                appName = "Test",
                wardId = "ward-1",
                wardName = "Ward",
                status = "PENDING",
                requestedAt = 2000L
            )
            val expectedKey = GuardianApprovalsHubViewModel.approvalKey("ward-1", "com.pkg.test")

            vm.resolveApproval(item, approve = true)
            assertTrue(vm.uiState.value.inFlightApprovalKeys.contains(expectedKey))

            gate.complete(Unit)
            assertFalse(vm.uiState.value.inFlightApprovalKeys.contains(expectedKey))
            assertTrue(cancelledNotifId != null)
            assertEquals(ApprovalsHubEvent.ShowToastRes(R.string.toast_approval_granted), vm.events.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resolveApproval clears inFlightApprovalKeys and emits error toast on failure`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(
                scope = scope,
                resolveApprovalAction = { _, _ -> false }
            )
            val item = WardApprovalModel(
                packageName = "com.pkg.fail",
                appName = "Fail",
                wardId = "ward-1",
                wardName = "Ward",
                status = "PENDING",
                requestedAt = 3000L
            )

            vm.resolveApproval(item, approve = false)

            assertTrue(vm.uiState.value.inFlightApprovalKeys.isEmpty())
            assertEquals(ApprovalsHubEvent.ShowToastRes(R.string.toast_approval_failed), vm.events.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `onCleared removes all registered Firestore listeners`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(scope)
            var removedCount = 0
            vm.addListenerForTesting(ListenerRegistration { removedCount++ })
            vm.addListenerForTesting(ListenerRegistration { removedCount++ })

            vm.onCleared()

            assertEquals(2, removedCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `initialize with forceReset cancels prior load job and avoids stale ward attachment`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val firstCallEntered = CompletableDeferred<Unit>()
            val firstWardGate = CompletableDeferred<Unit>()
            val freshAttached = CompletableDeferred<Unit>()
            var callIndex = 0
            val attachedWardLists = mutableListOf<List<String>>()
            val app = FakeApplication()
            val partnerDao = object : PartnerDao by FakePartnerDao(
                listOf(
                    PartnerEntity("ward-1", "", "Ward 1", PartnerEntity.ROLE_WARD, PartnerEntity.STATUS_ACTIVE, 1L)
                )
            ) {
                override fun getPartnersByRoleBlocking(role: String): List<PartnerEntity> {
                    callIndex++
                    if (callIndex == 1) {
                        firstCallEntered.complete(Unit)
                        runBlocking { firstWardGate.await() }
                        return listOf(PartnerEntity("stale-ward", "", "Stale", PartnerEntity.ROLE_WARD, PartnerEntity.STATUS_ACTIVE, 1L))
                    }
                    return listOf(PartnerEntity("fresh-ward", "", "Fresh", PartnerEntity.ROLE_WARD, PartnerEntity.STATUS_ACTIVE, 1L))
                }
            }
            val policyDao = FakeDevicePolicyDao()
            val approvalDao = FakePendingApprovalDao()
            val notifManager = AppNotificationManager(app, NotificationRepository(FakeInAppNotificationDao()), scope)
            val accRepo = AccountabilityRepository(app, partnerDao, policyDao)
            val approvalRepo = ApprovalRepository(app, approvalDao)

            val vm = GuardianApprovalsHubViewModel(app, accRepo, approvalRepo, partnerDao, notifManager).apply {
                ioDispatcher = Dispatchers.IO
                defaultDispatcher = Dispatchers.Unconfined
                coroutineScopeOverride = scope
                resolvePartnerUidAction = { uid, _ -> uid }
                attachListenersOverride = { wards ->
                    attachedWardLists.add(wards.map { it.id })
                    if (wards.any { it.id == "fresh-ward" }) {
                        freshAttached.complete(Unit)
                    }
                }
            }

            kotlinx.coroutines.withTimeout(5_000L) {
                vm.initialize("stale-ward", null, forceReset = false)
                firstCallEntered.await()
                vm.initialize("fresh-ward", null, forceReset = true)
                firstWardGate.complete(Unit)
                freshAttached.await()
            }

            assertEquals(listOf(listOf("fresh-ward")), attachedWardLists)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `initialize clears isLoading when no wards exist and targetWardId is null`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(scope = scope, wards = emptyList())
            vm.initialize(targetWardId = null, targetPackage = null, forceReset = false)

            assertFalse(vm.uiState.value.isLoading)
            assertTrue(vm.uiState.value.allApprovals.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `initialize synthesizes fallback ward when targetWardId is missing from partnerDao`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val attachedWards = mutableListOf<List<String>>()
            val vm = createViewModel(scope = scope, wards = emptyList()).apply {
                resolvePartnerUidAction = { uid, _ -> uid }
                attachListenersOverride = { wards -> attachedWards.add(wards.map { it.id }) }
            }
            vm.initialize(targetWardId = "ward-missing", targetPackage = null, forceReset = false)

            assertEquals(listOf(listOf("ward-missing")), attachedWards)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resolveApproval ignores duplicate in-flight key and emits toast when action throws`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val vm = createViewModel(
                scope = scope,
                resolveApprovalAction = { _, _ ->
                    calls++
                    gate.await()
                    true
                }
            )
            val item = WardApprovalModel("com.dup.app", "Dup", "ward-1", "Ward", "PENDING", 100L)
            vm.resolveApproval(item, approve = true)
            vm.resolveApproval(item, approve = true)
            gate.complete(Unit)

            assertEquals(1, calls)
            assertEquals(ApprovalsHubEvent.ShowToastRes(R.string.toast_approval_granted), vm.events.first())

            val throwingVm = createViewModel(
                scope = scope,
                resolveApprovalAction = { _, _ -> throw IllegalStateException("network down") }
            )
            throwingVm.resolveApproval(item, approve = false)
            assertTrue(throwingVm.uiState.value.inFlightApprovalKeys.isEmpty())
            assertEquals(ApprovalsHubEvent.ShowToastRes(R.string.toast_approval_failed), throwingVm.events.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `filteredApprovalsForSelectedTab sorts descending by requestedAt and filters REJECTED`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(scope = scope)
            vm.processWardApprovals(
                "ward-1",
                listOf(
                    WardApprovalModel("com.a", "A", "ward-1", "W", "PENDING", 100L),
                    WardApprovalModel("com.b", "B", "ward-1", "W", "PENDING", 300L),
                    WardApprovalModel("com.r", "R", "ward-1", "W", "REJECTED", 200L)
                )
            )
            assertEquals(listOf("com.b", "com.a"), vm.uiState.value.filteredApprovalsForSelectedTab().map { it.packageName })

            vm.selectTab(2)
            assertEquals(listOf("com.r"), vm.uiState.value.filteredApprovalsForSelectedTab().map { it.packageName })
        } finally {
            scope.cancel()
        }
    }

    private class FakePartnerDao(private val wards: List<PartnerEntity>) : PartnerDao {
        override fun getAllPartnersFlow(): Flow<List<PartnerEntity>> = MutableStateFlow(wards)
        override suspend fun getAllPartners(): List<PartnerEntity> = wards
        override fun getAllPartnersBlocking(): List<PartnerEntity> = wards
        override fun getPartnersByRoleFlow(role: String): Flow<List<PartnerEntity>> = MutableStateFlow(wards)
        override suspend fun getPartnersByRole(role: String): List<PartnerEntity> = wards
        override fun getPartnersByRoleBlocking(role: String): List<PartnerEntity> = wards
        override suspend fun getPartnersById(id: String): List<PartnerEntity> = wards.filter { it.id == id }
        override fun getPartnersByIdBlocking(id: String): List<PartnerEntity> = wards.filter { it.id == id }
        override suspend fun getPartnerByIdAndRole(id: String, role: String): PartnerEntity? =
            wards.firstOrNull { it.id == id && it.role == role }
        override fun getPartnerByIdAndRoleBlocking(id: String, role: String): PartnerEntity? =
            wards.firstOrNull { it.id == id && it.role == role }
        override suspend fun savePartner(partner: PartnerEntity) {}
        override fun savePartnerBlocking(partner: PartnerEntity) {}
        override suspend fun deletePartnerByIdAndRole(id: String, role: String): Int = 0
        override fun deletePartnerByIdAndRoleBlocking(id: String, role: String): Int = 0
        override suspend fun updatePartnerDisplayNameByRole(id: String, role: String, newName: String, syncTimestamp: Long): Int = 0
        override fun updatePartnerDisplayNameByRoleBlocking(id: String, role: String, newName: String, syncTimestamp: Long): Int = 0
    }

    private class FakeDevicePolicyDao : DevicePolicyDao {
        private var entity: DevicePolicyEntity? = null
        private val flow = MutableStateFlow<DevicePolicyEntity?>(null)
        override fun getDevicePolicyFlow(): Flow<DevicePolicyEntity?> = flow
        override suspend fun getDevicePolicy(): DevicePolicyEntity? = entity
        override fun getDevicePolicyBlocking(): DevicePolicyEntity? = entity
        override suspend fun saveDevicePolicy(entity: DevicePolicyEntity) { this.entity = entity; flow.value = entity }
        override fun saveDevicePolicyBlocking(entity: DevicePolicyEntity) { this.entity = entity; flow.value = entity }
    }

    private class FakePendingApprovalDao : PendingApprovalDao {
        override fun getPendingApprovalsFlow(): Flow<List<PendingApprovalEntity>> = MutableStateFlow(emptyList())
        override suspend fun getPendingApprovals(): List<PendingApprovalEntity> = emptyList()
        override fun getPendingApprovalsBlocking(): List<PendingApprovalEntity> = emptyList()
        override suspend fun getActualPendingApprovals(): List<PendingApprovalEntity> = emptyList()
        override fun getActualPendingApprovalsBlocking(): List<PendingApprovalEntity> = emptyList()
        override suspend fun getUnsyncedApprovals(): List<PendingApprovalEntity> = emptyList()
        override fun getUnsyncedApprovalsBlocking(): List<PendingApprovalEntity> = emptyList()
        override suspend fun getApprovalByPackageName(packageName: String): PendingApprovalEntity? = null
        override fun getApprovalByPackageNameBlocking(packageName: String): PendingApprovalEntity? = null
        override suspend fun savePendingApproval(approval: PendingApprovalEntity) {}
        override fun savePendingApprovalBlocking(approval: PendingApprovalEntity) {}
        override suspend fun savePendingApprovals(approvals: List<PendingApprovalEntity>) {}
        override fun savePendingApprovalsBlocking(approvals: List<PendingApprovalEntity>) {}
        override suspend fun updateApprovalStatus(
            packageName: String,
            status: String,
            decidedBy: String?,
            decidedAt: Long,
            isSynced: Int
        ): Int = 0
        override fun updateApprovalStatusBlocking(
            packageName: String,
            status: String,
            decidedBy: String?,
            decidedAt: Long,
            isSynced: Int
        ): Int = 0
        override suspend fun markApprovalCloudSynced(packageName: String, isSynced: Int): Int = 0
        override fun markApprovalCloudSyncedBlocking(packageName: String, isSynced: Int): Int = 0
        override fun markApprovalCloudSyncedIfMatchingBlocking(
            packageName: String,
            expectedStatus: String,
            expectedDecidedAt: Long?,
            isSynced: Int
        ): Int = 0
        override suspend fun deletePendingApproval(packageName: String): Int = 0
        override fun deletePendingApprovalBlocking(packageName: String): Int = 0
        override fun clearAllPendingApprovalsBlocking(): Int = 0
    }

    private class FakeInAppNotificationDao : InAppNotificationDao {
        override suspend fun insertOrReplace(notification: InAppNotificationEntity): Long = 1L
        override suspend fun getAllNotifications(limit: Int): List<InAppNotificationEntity> = emptyList()
        override fun observeAllNotifications(limit: Int): Flow<List<InAppNotificationEntity>> = MutableStateFlow(emptyList())
        override suspend fun getUnreadCount(): Int = 0
        override fun observeUnreadCount(): Flow<Int> = MutableStateFlow(0)
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

    private class FakeApplication : Application() {
        private val prefsMap = mutableMapOf<String, InMemorySharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            prefsMap.getOrPut(name ?: "default") { InMemorySharedPreferences() }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = apply { if (key != null) data[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {}
            override fun putInt(key: String?, value: Int) = apply { if (key != null) data[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) data[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) data[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) data[key] = value }
            override fun remove(key: String?) = apply { if (key != null) data.remove(key) }
            override fun clear() = apply { data.clear() }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }
}
