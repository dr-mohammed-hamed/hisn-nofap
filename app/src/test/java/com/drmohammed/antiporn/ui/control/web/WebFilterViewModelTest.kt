package com.drmohammed.antiporn.ui.control.web

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.network.DnsFilteringValidator
import com.drmohammed.antiporn.curfew.WebFilterCapabilityResult
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFilterViewModelTest {

    private fun createViewModel(
        scope: CoroutineScope,
        validateDnsHost: (String) -> DnsFilteringValidator.ValidationResult = { DnsFilteringValidator.ValidationResult.Valid },
        isKnownFamilyPreset: (String) -> Boolean = { DnsFilteringValidator.isKnownFamilyPreset(it) },
        evaluateCapability: (android.content.Context, String, String, Boolean) -> WebFilterCapabilityResult = { _, _, _, _ -> WebFilterCapabilityResult.Allowed },
        canAccessSelfControl: (android.content.Context) -> Boolean = { true },
        isUnaffiliatedBlocked: (android.content.Context) -> Boolean = { false },
        canWriteSecure: (android.content.Context) -> Boolean = { false }
    ): WebFilterViewModel {
        val app = FakeApplication()
        val policyDao = FakeDevicePolicyDao()
        val partnerDao = FakePartnerDao()
        val policyRepo = PolicyRepository(
            context = app,
            policyDao = policyDao,
            partnerDao = partnerDao
        )
        val accRepo = AccountabilityRepository(
            context = app,
            partnerDao = partnerDao,
            policyDao = policyDao
        )
        return WebFilterViewModel(
            application = app,
            policyRepository = policyRepo,
            accountabilityRepository = accRepo
        ).apply {
            this.ioDispatcher = Dispatchers.Unconfined
            this.validateDnsHost = validateDnsHost
            this.isKnownFamilyPreset = isKnownFamilyPreset
            this.evaluateCapability = evaluateCapability
            this.canAccessSelfControl = canAccessSelfControl
            this.isUnaffiliatedBlockedProvider = isUnaffiliatedBlocked
            this.canWriteSecureSettingsProvider = canWriteSecure
            this.coroutineScopeOverride = scope
        }
    }

    @Test
    fun `initialize is idempotent and returns true only on first call`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(scope)
            val first = DevicePolicyBundle.defaultPolicy().copy(dnsHost = DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST)
            val second = DevicePolicyBundle.defaultPolicy().copy(dnsHost = DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST)

            assertTrue(vm.initialize(isRemote = false, wardId = "", initialPolicy = first))
            assertFalse(vm.initialize(isRemote = true, wardId = "w1", initialPolicy = second))
            assertEquals(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST, vm.uiState.value.dnsHost)
            assertFalse(vm.uiState.value.isRemote)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `saveChanges emits EmptyWhitelistError when whitelist mode has no allowed urls`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(scope)
            vm.initialize(false, "", DevicePolicyBundle.defaultPolicy())
            vm.setWhitelistOnlyMode(true)
            vm.saveChanges()

            val event = vm.events.first()
            assertTrue(event is WebFilterUiEvent.EmptyWhitelistError)
            assertFalse(vm.uiState.value.isSaving)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `saveChanges emits InvalidDnsHostFormat for malformed custom host`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(scope)
            vm.initialize(false, "", DevicePolicyBundle.defaultPolicy())
            vm.updateDnsHost("..bad..host")
            vm.saveChanges()

            val event = vm.events.first()
            assertTrue(event is WebFilterUiEvent.InvalidDnsHostFormat)
            assertFalse(vm.uiState.value.isSaving)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `saveChanges emits SaveFailed when self-control access gate denies access`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(scope, canAccessSelfControl = { false })
            vm.initialize(false, "", DevicePolicyBundle.defaultPolicy())
            vm.setIncognitoBlocked(false)
            vm.saveChanges()

            val event = vm.events.first()
            assertEquals(WebFilterUiEvent.SaveFailed(isRemote = false), event)
            assertFalse(vm.uiState.value.isSaving)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `saveChanges skips probe for validated custom host and emits CapabilityBlocked when blocked`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var probeCalls = 0
            val vm = createViewModel(
                scope = scope,
                validateDnsHost = {
                    probeCalls++
                    DnsFilteringValidator.ValidationResult.Valid
                },
                evaluateCapability = { _, _, _, _ ->
                    WebFilterCapabilityResult.BlockedLocalFilterByLockedDot("family.cloudflare-dns.com")
                }
            )
            vm.initialize(false, "", DevicePolicyBundle.defaultPolicy())
            vm.updateDnsHost("custom.family.dns.org", validated = true)
            vm.setFullSystemFiltering(true)
            vm.saveChanges()

            assertEquals(0, probeCalls)
            val event = vm.events.first()
            assertTrue(event is WebFilterUiEvent.CapabilityBlocked)
            assertFalse(vm.uiState.value.isSaving)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `refreshUnaffiliatedStatusAsync catches exceptions without crashing`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(
                scope = scope,
                isUnaffiliatedBlocked = { throw IllegalStateException("binder failure") }
            )
            vm.initialize(false, "", DevicePolicyBundle.defaultPolicy())
            vm.refreshUnaffiliatedStatusAsync()
            assertFalse(vm.uiState.value.isUnaffiliatedBlocked)
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
        override suspend fun getPartnersById(id: String): List<PartnerEntity> = emptyList()
        override fun getPartnersByIdBlocking(id: String): List<PartnerEntity> = emptyList()
        override suspend fun getPartnerByIdAndRole(id: String, role: String): PartnerEntity? = null
        override fun getPartnerByIdAndRoleBlocking(id: String, role: String): PartnerEntity? = null
        override suspend fun savePartner(partner: PartnerEntity) {}
        override fun savePartnerBlocking(partner: PartnerEntity) {}
        override suspend fun deletePartnerByIdAndRole(id: String, role: String): Int = 0
        override fun deletePartnerByIdAndRoleBlocking(id: String, role: String): Int = 0
        override suspend fun updatePartnerDisplayNameByRole(id: String, role: String, newName: String, syncTimestamp: Long): Int = 0
        override fun updatePartnerDisplayNameByRoleBlocking(id: String, role: String, newName: String, syncTimestamp: Long): Int = 0
    }

    private class FakeApplication : Application() {
        private val prefsMap = mutableMapOf<String, InMemorySharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefsMap.getOrPut(name ?: "default") { InMemorySharedPreferences() }
        }
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
