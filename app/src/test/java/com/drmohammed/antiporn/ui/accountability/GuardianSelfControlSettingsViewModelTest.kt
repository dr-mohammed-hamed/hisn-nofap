package com.drmohammed.antiporn.ui.accountability

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.repository.PolicyRepository
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

class GuardianSelfControlSettingsViewModelTest {

    private fun createViewModel(
        scope: CoroutineScope,
        nowMillis: Long = 1_700_000_000_000L,
        pushPolicyAction: suspend (String, DevicePolicyBundle) -> Boolean = { _, _ -> true }
    ): GuardianSelfControlSettingsViewModel {
        val app = FakeApplication()
        val policyRepo = PolicyRepository(
            context = app,
            policyDao = FakeDevicePolicyDao(),
            partnerDao = FakePartnerDao()
        )
        return GuardianSelfControlSettingsViewModel(
            application = app,
            policyRepository = policyRepo
        ).apply {
            this.ioDispatcher = Dispatchers.Unconfined
            this.coroutineScopeOverride = scope
            this.nowProvider = { nowMillis }
            this.pushPolicyAction = pushPolicyAction
        }
    }

    @Test
    fun `initialize is idempotent and never reverts policy to stale initial extras`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(scope)
            val v1 = DevicePolicyBundle.defaultPolicy().copy(version = 3L, isSelfControlAllowed = true)
            val stale = DevicePolicyBundle.defaultPolicy().copy(version = 1L, isSelfControlAllowed = false)

            assertTrue(vm.initialize("ward-1", v1))
            vm.pushPolicyChange { it.copy(selfControlCurfewAllowed = false) }
            assertEquals(4L, vm.uiState.value.currentPolicy.version)
            assertFalse(vm.uiState.value.currentPolicy.selfControlCurfewAllowed)

            // Simulate rotation calling initialize again with stale Intent extras
            assertFalse(vm.initialize("ward-1", stale))
            assertEquals(4L, vm.uiState.value.currentPolicy.version)
            assertFalse(vm.uiState.value.currentPolicy.selfControlCurfewAllowed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `restorePolicyState restores newer saved policy and ignores older policy`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(scope)
            val initial = DevicePolicyBundle.defaultPolicy().copy(version = 2L, selfControlWebAllowed = true)
            vm.initialize("ward-1", initial)

            val older = initial.copy(version = 1L, selfControlWebAllowed = false)
            vm.restorePolicyState("ward-1", older.toJson())
            assertEquals(2L, vm.uiState.value.currentPolicy.version)
            assertTrue(vm.uiState.value.currentPolicy.selfControlWebAllowed)

            val newer = initial.copy(version = 5L, selfControlWebAllowed = false)
            vm.restorePolicyState("ward-2", newer.toJson())
            assertEquals("ward-2", vm.uiState.value.wardId)
            assertEquals(5L, vm.uiState.value.currentPolicy.version)
            assertFalse(vm.uiState.value.currentPolicy.selfControlWebAllowed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `pushPolicyChange failure preserves current policy and emits RevertSwitchesToPolicy`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(scope) { _, _ -> false }
            val initial = DevicePolicyBundle.defaultPolicy().copy(version = 2L, selfControlAppsAllowed = true)
            vm.initialize("ward-1", initial)

            vm.pushPolicyChange { it.copy(selfControlAppsAllowed = false) }

            assertEquals(2L, vm.uiState.value.currentPolicy.version)
            assertTrue(vm.uiState.value.currentPolicy.selfControlAppsAllowed)
            assertFalse(vm.uiState.value.isPushing)
            assertEquals(
                listOf(
                    GuardianSelfControlEvent.RevertSwitchesToPolicy,
                    GuardianSelfControlEvent.ShowToastRes(R.string.toast_guardian_policy_save_failed)
                ),
                vm.events.take(2).toList()
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `concurrent pushPolicyChange calls are serialized with monotonic versions`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val firstPushGate = CompletableDeferred<Unit>()
            val pushedVersions = mutableListOf<Long>()
            var callCount = 0

            val vm = createViewModel(scope) { _, bundle ->
                callCount++
                if (callCount == 1) {
                    firstPushGate.await()
                }
                pushedVersions.add(bundle.version)
                true
            }
            vm.initialize("ward-1", DevicePolicyBundle.defaultPolicy().copy(version = 10L))

            vm.pushPolicyChange { it.copy(selfControlCurfewAllowed = false) }
            assertTrue(vm.uiState.value.isPushing)

            vm.pushPolicyChange { it.copy(selfControlWebAllowed = false) }
            firstPushGate.complete(Unit)

            assertEquals(listOf(11L, 12L), pushedVersions)
            assertEquals(12L, vm.uiState.value.currentPolicy.version)
            assertFalse(vm.uiState.value.currentPolicy.selfControlCurfewAllowed)
            assertFalse(vm.uiState.value.currentPolicy.selfControlWebAllowed)
            assertFalse(vm.uiState.value.isPushing)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `executeRemoteQrReset bumps version and sets reset timestamp`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val expectedNow = 1_750_000_000_000L
            val vm = createViewModel(scope, nowMillis = expectedNow) { _, _ -> true }
            vm.initialize("ward-1", DevicePolicyBundle.defaultPolicy().copy(version = 7L))

            vm.executeRemoteQrReset()

            assertEquals(8L, vm.uiState.value.currentPolicy.version)
            assertEquals(expectedNow, vm.uiState.value.currentPolicy.remoteSelfControlResetRequestedAt)
            assertEquals(expectedNow, vm.uiState.value.currentPolicy.updatedAt)
            assertEquals(
                GuardianSelfControlEvent.ShowToastRes(R.string.toast_reset_ward_qr_success),
                vm.events.first()
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `blank wardId is ignored for pushPolicyChange and executeRemoteQrReset`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var pushCount = 0
            val vm = createViewModel(scope) { _, _ -> pushCount++; true }
            vm.initialize("", DevicePolicyBundle.defaultPolicy())
            vm.pushPolicyChange { it.copy(selfControlWebAllowed = false) }
            vm.executeRemoteQrReset()
            assertEquals(0, pushCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `restorePolicyState ignores malformed JSON and push or reset exceptions emit failure events`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(scope) { _, _ -> throw IllegalStateException("firestore offline") }
            val initial = DevicePolicyBundle.defaultPolicy().copy(version = 4L, selfControlWebAllowed = true)
            vm.initialize("ward-1", initial)

            vm.restorePolicyState("ward-2", "{malformed-json")
            assertEquals("ward-2", vm.uiState.value.wardId)
            assertEquals(4L, vm.uiState.value.currentPolicy.version)
            assertTrue(vm.uiState.value.currentPolicy.selfControlWebAllowed)

            vm.pushPolicyChange { it.copy(selfControlWebAllowed = false) }
            assertFalse(vm.uiState.value.isPushing)
            assertEquals(
                listOf(
                    GuardianSelfControlEvent.RevertSwitchesToPolicy,
                    GuardianSelfControlEvent.ShowToastRes(R.string.toast_guardian_policy_save_failed)
                ),
                vm.events.take(2).toList()
            )

            vm.executeRemoteQrReset()
            assertFalse(vm.uiState.value.isPushing)
            assertEquals(
                GuardianSelfControlEvent.ShowToastRes(R.string.toast_reset_ward_qr_failed),
                vm.events.first()
            )
        } finally {
            scope.cancel()
        }
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

    private class FakePartnerDao : PartnerDao {
        override fun getAllPartnersFlow(): Flow<List<PartnerEntity>> = MutableStateFlow(emptyList())
        override suspend fun getAllPartners(): List<PartnerEntity> = emptyList()
        override fun getAllPartnersBlocking(): List<PartnerEntity> = emptyList()
        override fun getPartnersByRoleFlow(role: String): Flow<List<PartnerEntity>> = MutableStateFlow(emptyList())
        override suspend fun getPartnersByRole(role: String): List<PartnerEntity> = emptyList()
        override fun getPartnersByRoleBlocking(role: String): List<PartnerEntity> = emptyList()
        override suspend fun getPartnerById(id: String): PartnerEntity? = null
        override fun getPartnerByIdBlocking(id: String): PartnerEntity? = null
        override suspend fun savePartner(partner: PartnerEntity) {}
        override fun savePartnerBlocking(partner: PartnerEntity) {}
        override suspend fun deletePartner(id: String): Int = 0
        override fun deletePartnerBlocking(id: String): Int = 0
        override suspend fun updatePartnerDisplayName(id: String, newName: String, syncTimestamp: Long): Int = 0
        override fun updatePartnerDisplayNameBlocking(id: String, newName: String, syncTimestamp: Long): Int = 0
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
