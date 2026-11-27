package com.drmohammed.antiporn.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.gate.SelfControlSection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PolicySanitizationTest {

    private lateinit var fakeContext: FakeAppContext
    private lateinit var policyDao: FakeDevicePolicyDao
    private lateinit var partnerDao: FakePartnerDao
    private lateinit var repository: PolicyRepository

    @Before
    fun setUp() {
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { 10_000L }
        TrustedTimeProvider.wallTimeProvider = { 1_790_000_000_000L }
        TrustedTimeProvider.setAnchorForTesting(1_790_000_000_000L, 10_000L)

        fakeContext = FakeAppContext()
        policyDao = FakeDevicePolicyDao()
        partnerDao = FakePartnerDao()
        AccountabilityRepository.setInstance(
            AccountabilityRepository(
                context = fakeContext,
                partnerDao = partnerDao,
                policyDao = policyDao
            )
        )
        repository = PolicyRepository(
            context = fakeContext,
            policyDao = policyDao,
            partnerDao = partnerDao
        )
        awaitInitialPolicyLoaded(repository)
    }

    private fun awaitInitialPolicyLoaded(repo: PolicyRepository) = runBlocking {
        withTimeout(5_000L) {
            while (!repo.isInitialPolicyLoaded) {
                delay(5L)
            }
        }
    }

    @After
    fun tearDown() {
        PolicyRepository.setInstance(null)
        AccountabilityRepository.setInstance(null)
        SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = true
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { SystemClock.elapsedRealtime() }
        TrustedTimeProvider.wallTimeProvider = { System.currentTimeMillis() }
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

    @Test
    fun `sanitizeSupervisedSelfPolicy allows other settings and instant internet kill when selfControlOtherSettingsAllowed and selfControlCurfewAllowed are true`() {
        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            isSelfControlAllowed = true,
            selfControlOtherSettingsAllowed = true,
            selfControlCurfewAllowed = true,
            isDateTimeLocked = false,
            isClearAppDataAllowed = true,
            timeZoneId = "Africa/Cairo",
            isInstantInternetKillEnabled = false
        )

        val updated = current.copy(
            isDateTimeLocked = true,
            isClearAppDataAllowed = false,
            timeZoneId = "Asia/Riyadh",
            isInstantInternetKillEnabled = true
        )

        val sanitized = repository.sanitizeSupervisedSelfPolicy(updated, current)

        assertTrue(sanitized.isDateTimeLocked)
        assertFalse(sanitized.isClearAppDataAllowed)
        assertEquals("Asia/Riyadh", sanitized.timeZoneId)
        assertTrue(sanitized.isInstantInternetKillEnabled)
    }

    @Test
    fun `sanitizeSupervisedSelfPolicy preserves other settings and instant internet kill when selfControlOtherSettingsAllowed is false`() {
        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            isSelfControlAllowed = true,
            selfControlOtherSettingsAllowed = false,
            selfControlCurfewAllowed = true,
            isDateTimeLocked = true,
            isClearAppDataAllowed = false,
            timeZoneId = "Asia/Riyadh",
            isInstantInternetKillEnabled = true
        )

        val tampered = current.copy(
            isDateTimeLocked = false,
            isClearAppDataAllowed = true,
            timeZoneId = "Africa/Cairo",
            isInstantInternetKillEnabled = false
        )

        val sanitized = repository.sanitizeSupervisedSelfPolicy(tampered, current)

        assertTrue(sanitized.isDateTimeLocked)
        assertFalse(sanitized.isClearAppDataAllowed)
        assertEquals("Asia/Riyadh", sanitized.timeZoneId)
        assertTrue(sanitized.isInstantInternetKillEnabled)
    }

    @Test
    fun `sanitizeSupervisedSelfPolicy preserves instant internet kill when selfControlCurfewAllowed is false even if selfControlOtherSettingsAllowed is true`() {
        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            isSelfControlAllowed = true,
            selfControlOtherSettingsAllowed = true,
            selfControlCurfewAllowed = false,
            isDateTimeLocked = false,
            isClearAppDataAllowed = true,
            timeZoneId = "Africa/Cairo",
            isInstantInternetKillEnabled = true
        )

        val updated = current.copy(
            isDateTimeLocked = true,
            isClearAppDataAllowed = false,
            timeZoneId = "Asia/Dubai",
            isInstantInternetKillEnabled = false
        )

        val sanitized = repository.sanitizeSupervisedSelfPolicy(updated, current)

        assertTrue(sanitized.isDateTimeLocked)
        assertFalse(sanitized.isClearAppDataAllowed)
        assertEquals("Asia/Dubai", sanitized.timeZoneId)
        assertTrue(sanitized.isInstantInternetKillEnabled)
    }

    @Test
    fun `mergeAndSaveSelfPolicyPatch prevents supervised ward from flipping selfControlOtherSettingsAllowed from false to true`() = runBlocking {
        val initial = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            guardianName = "Parent",
            isSelfControlAllowed = true,
            selfControlOtherSettingsAllowed = false,
            selfControlCurfewAllowed = true,
            isDateTimeLocked = true,
            isClearAppDataAllowed = false,
            timeZoneId = "Asia/Riyadh",
            isInstantInternetKillEnabled = true
        )
        repository.updateLocalPolicyWithoutEnforcing(initial)

        val saved = repository.mergeAndSaveSelfPolicyPatch { current ->
            current.copy(
                selfControlOtherSettingsAllowed = true,
                isDateTimeLocked = false,
                isClearAppDataAllowed = true,
                timeZoneId = "Africa/Cairo",
                isInstantInternetKillEnabled = false
            )
        }

        assertNotNull(saved)
        assertFalse(saved!!.selfControlOtherSettingsAllowed)
        assertTrue(saved.isDateTimeLocked)
        assertFalse(saved.isClearAppDataAllowed)
        assertEquals("Asia/Riyadh", saved.timeZoneId)
        assertTrue(saved.isInstantInternetKillEnabled)
    }

    @Test
    fun `SelfControlAccessGateRegistry isSectionPermitted handles OTHER_SETTINGS for supervised and unsupervised policies`() {
        val unsupervisedDisabledFlag = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = false,
            guardianUid = null,
            selfControlOtherSettingsAllowed = false
        )
        assertTrue(SelfControlAccessGateRegistry.isSectionPermitted(unsupervisedDisabledFlag, SelfControlSection.OTHER_SETTINGS))

        val supervisedAllowed = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            isSelfControlAllowed = true,
            selfControlOtherSettingsAllowed = true
        )
        assertTrue(SelfControlAccessGateRegistry.isSectionPermitted(supervisedAllowed, SelfControlSection.OTHER_SETTINGS))

        val supervisedDenied = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            isSelfControlAllowed = true,
            selfControlOtherSettingsAllowed = false
        )
        assertFalse(SelfControlAccessGateRegistry.isSectionPermitted(supervisedDenied, SelfControlSection.OTHER_SETTINGS))

        val supervisedMasterDenied = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            isSelfControlAllowed = false,
            selfControlOtherSettingsAllowed = true
        )
        assertFalse(SelfControlAccessGateRegistry.isSectionPermitted(supervisedMasterDenied, SelfControlSection.OTHER_SETTINGS))

        val guardianUidOnlyDenied = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = false,
            guardianUid = "guardian-1",
            isSelfControlAllowed = true,
            selfControlOtherSettingsAllowed = false
        )
        assertFalse(SelfControlAccessGateRegistry.isSectionPermitted(guardianUidOnlyDenied, SelfControlSection.OTHER_SETTINGS))
    }

    @Test
    fun `cold start reconciliation normalizes legacy false flags for unsupervised policy while leaving supervised policy untouched`() = runBlocking {
        // Case 1: Unsupervised policy with legacy false flags is normalized to all true on cold start
        val legacyUnsupervised = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = false,
            guardianUid = null,
            isSelfControlAllowed = true,
            selfControlCurfewAllowed = false,
            selfControlWebAllowed = false,
            selfControlAppsAllowed = false,
            selfControlWaqfaAllowed = false,
            selfControlNewAppsAllowed = false,
            selfControlPageShieldAllowed = false,
            selfControlFortressesAllowed = false,
            selfControlFortressUnlockAllowed = false,
            selfControlOtherSettingsAllowed = false,
            version = 3L,
            updatedAt = 1_700_000_000_000L
        )
        val unsupervisedContext = FakeAppContext()
        val unsupervisedDao = FakeDevicePolicyDao().apply {
            saveDevicePolicyBlocking(DevicePolicyEntity.fromBundle(legacyUnsupervised))
        }
        val unsupervisedRepo = PolicyRepository(
            context = unsupervisedContext,
            policyDao = unsupervisedDao,
            partnerDao = FakePartnerDao()
        )
        awaitInitialPolicyLoaded(unsupervisedRepo)
        withTimeout(5_000L) {
            while (unsupervisedDao.getDevicePolicyBlocking()?.toBundle()?.hasAllSelfControlPermissionsEnabled() != true) {
                delay(10L)
            }
        }

        val reconciledUnsupervised = unsupervisedRepo.getCurrentPolicy()
        assertFalse(reconciledUnsupervised.isSupervised)
        assertNull(reconciledUnsupervised.guardianUid)
        assertTrue(reconciledUnsupervised.hasAllSelfControlPermissionsEnabled())
        assertTrue(unsupervisedDao.getDevicePolicyBlocking()!!.toBundle().hasAllSelfControlPermissionsEnabled())

        // Case 2: Already-supervised policy with explicit false flags must remain completely untouched
        val supervisedLocked = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-uid-1",
            guardianName = "Guardian",
            isSelfControlAllowed = false,
            selfControlCurfewAllowed = false,
            selfControlWebAllowed = false,
            selfControlAppsAllowed = false,
            selfControlWaqfaAllowed = false,
            selfControlNewAppsAllowed = false,
            selfControlPageShieldAllowed = false,
            selfControlFortressesAllowed = false,
            selfControlFortressUnlockAllowed = false,
            selfControlOtherSettingsAllowed = false,
            version = 5L,
            updatedAt = 1_700_000_005_000L
        )
        val supervisedContext = FakeAppContext()
        val supervisedDao = FakeDevicePolicyDao().apply {
            saveDevicePolicyBlocking(DevicePolicyEntity.fromBundle(supervisedLocked))
        }
        val supervisedPartnerDao = FakePartnerDao(
            mutableListOf(
                PartnerEntity(
                    id = "guardian-uid-1",
                    pairingCode = AccountabilityRepository.CODE_LOCKED,
                    displayName = "Guardian",
                    role = PartnerEntity.ROLE_GUARDIAN,
                    status = PartnerEntity.STATUS_ACTIVE,
                    createdAt = 1_700_000_005_000L,
                    lastSyncAt = 1_700_000_005_000L
                )
            )
        )
        val supervisedRepo = PolicyRepository(
            context = supervisedContext,
            policyDao = supervisedDao,
            partnerDao = supervisedPartnerDao
        )
        awaitInitialPolicyLoaded(supervisedRepo)

        val currentSupervised = supervisedRepo.getCurrentPolicy()
        assertTrue(currentSupervised.isSupervised)
        assertEquals("guardian-uid-1", currentSupervised.guardianUid)
        assertFalse(currentSupervised.isSelfControlAllowed)
        assertFalse(currentSupervised.selfControlCurfewAllowed)
        assertFalse(currentSupervised.selfControlWebAllowed)
        assertFalse(currentSupervised.selfControlAppsAllowed)
        assertFalse(currentSupervised.selfControlWaqfaAllowed)
        assertFalse(currentSupervised.selfControlNewAppsAllowed)
        assertFalse(currentSupervised.selfControlPageShieldAllowed)
        assertFalse(currentSupervised.selfControlFortressesAllowed)
        assertFalse(currentSupervised.selfControlFortressUnlockAllowed)
        assertFalse(currentSupervised.selfControlOtherSettingsAllowed)
        assertEquals(supervisedLocked, supervisedDao.getDevicePolicyBlocking()?.toBundle())
    }

    private class FakeAppContext : ContextWrapper(null) {
        private val prefsMap = java.util.concurrent.ConcurrentHashMap<String, InMemorySharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getPackageName(): String = "com.drmohammed.antiporn"

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefsMap.getOrPut(name ?: "default") { InMemorySharedPreferences() }
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = java.util.concurrent.ConcurrentHashMap<String, Any?>()

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
        @Volatile
        private var entity: DevicePolicyEntity? = null
        private val flow = MutableStateFlow<DevicePolicyEntity?>(null)
        override fun getDevicePolicyFlow(): Flow<DevicePolicyEntity?> = flow
        override suspend fun getDevicePolicy(): DevicePolicyEntity? = entity
        override fun getDevicePolicyBlocking(): DevicePolicyEntity? = entity
        override suspend fun saveDevicePolicy(entity: DevicePolicyEntity) { this.entity = entity; flow.value = entity }
        override fun saveDevicePolicyBlocking(entity: DevicePolicyEntity) { this.entity = entity; flow.value = entity }
    }

    private class FakePartnerDao(
        private val partners: MutableList<PartnerEntity> = mutableListOf()
    ) : PartnerDao {
        override fun getAllPartnersFlow(): Flow<List<PartnerEntity>> = MutableStateFlow(partners.toList())
        override suspend fun getAllPartners(): List<PartnerEntity> = partners.toList()
        override fun getAllPartnersBlocking(): List<PartnerEntity> = partners.toList()
        override fun getPartnersByRoleFlow(role: String): Flow<List<PartnerEntity>> =
            MutableStateFlow(partners.filter { it.role == role })
        override suspend fun getPartnersByRole(role: String): List<PartnerEntity> =
            partners.filter { it.role == role }
        override fun getPartnersByRoleBlocking(role: String): List<PartnerEntity> =
            partners.filter { it.role == role }
        override suspend fun getPartnersById(id: String): List<PartnerEntity> =
            partners.filter { it.id == id }
        override fun getPartnersByIdBlocking(id: String): List<PartnerEntity> =
            partners.filter { it.id == id }
        override suspend fun getPartnerByIdAndRole(id: String, role: String): PartnerEntity? =
            partners.firstOrNull { it.id == id && it.role == role }
        override fun getPartnerByIdAndRoleBlocking(id: String, role: String): PartnerEntity? =
            partners.firstOrNull { it.id == id && it.role == role }
        override suspend fun savePartner(partner: PartnerEntity) = savePartnerBlocking(partner)
        override fun savePartnerBlocking(partner: PartnerEntity) {
            partners.removeAll { it.id == partner.id && it.role == partner.role }
            partners.add(partner)
        }
        override suspend fun deletePartnerByIdAndRole(id: String, role: String): Int =
            deletePartnerByIdAndRoleBlocking(id, role)
        override fun deletePartnerByIdAndRoleBlocking(id: String, role: String): Int {
            val before = partners.size
            partners.removeAll { it.id == id && it.role == role }
            return before - partners.size
        }
        override suspend fun updatePartnerDisplayNameByRole(id: String, role: String, newName: String, syncTimestamp: Long): Int = 0
        override fun updatePartnerDisplayNameByRoleBlocking(id: String, role: String, newName: String, syncTimestamp: Long): Int = 0
    }
}
