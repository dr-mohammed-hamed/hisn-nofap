package com.drmohammed.antiporn.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PolicySanitizationTest {

    private lateinit var repository: PolicyRepository

    @Before
    fun setUp() {
        repository = PolicyRepository(
            context = FakeAppContext(),
            policyDao = FakeDevicePolicyDao(),
            partnerDao = FakePartnerDao()
        )
    }

    @Test
    fun `sanitizeSupervisedSelfPolicy preserves all web fields including isBlockExternalVpnEnabled when selfControlWebAllowed is false`() {
        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            selfControlWebAllowed = false,
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY,
            isFullSystemFilteringEnabled = true,
            customBlockedUrls = setOf("bad.com"),
            customAllowedUrls = setOf("good.com"),
            dnsHost = DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST,
            isYouTubeRestrictedModeEnabled = true,
            isAdBlockingEnabled = true,
            isTelegramWebBlocked = true,
            isIncognitoBlocked = true,
            isBlockExternalVpnEnabled = true
        )

        val tampered = current.copy(
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_OFF,
            isFullSystemFilteringEnabled = false,
            customBlockedUrls = emptySet(),
            customAllowedUrls = emptySet(),
            dnsHost = "8.8.8.8",
            isYouTubeRestrictedModeEnabled = false,
            isAdBlockingEnabled = false,
            isTelegramWebBlocked = false,
            isIncognitoBlocked = false,
            isBlockExternalVpnEnabled = false
        )

        val sanitized = repository.sanitizeSupervisedSelfPolicy(tampered, current)

        assertEquals(current.browserFilterMode, sanitized.browserFilterMode)
        assertEquals(current.isFullSystemFilteringEnabled, sanitized.isFullSystemFilteringEnabled)
        assertEquals(current.customBlockedUrls, sanitized.customBlockedUrls)
        assertEquals(current.customAllowedUrls, sanitized.customAllowedUrls)
        assertEquals(current.dnsHost, sanitized.dnsHost)
        assertEquals(current.isYouTubeRestrictedModeEnabled, sanitized.isYouTubeRestrictedModeEnabled)
        assertEquals(current.isAdBlockingEnabled, sanitized.isAdBlockingEnabled)
        assertEquals(current.isTelegramWebBlocked, sanitized.isTelegramWebBlocked)
        assertEquals(current.isIncognitoBlocked, sanitized.isIncognitoBlocked)
        assertTrue(sanitized.isBlockExternalVpnEnabled)
    }

    @Test
    fun `sanitizeSupervisedSelfPolicy allows web field changes when selfControlWebAllowed is true`() {
        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            selfControlWebAllowed = true,
            isBlockExternalVpnEnabled = true,
            dnsHost = DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST
        )

        val updated = current.copy(
            isBlockExternalVpnEnabled = false,
            dnsHost = DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST
        )

        val sanitized = repository.sanitizeSupervisedSelfPolicy(updated, current)

        assertFalse(sanitized.isBlockExternalVpnEnabled)
        assertEquals(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST, sanitized.dnsHost)
    }

    private class FakeAppContext : ContextWrapper(null) {
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
}
