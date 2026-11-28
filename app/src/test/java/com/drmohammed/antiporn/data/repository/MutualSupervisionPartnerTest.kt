package com.drmohammed.antiporn.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.lifecycle.ViewModelStore
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.cloud.CloudSyncManager
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.ui.accountability.AccountabilityViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM regression tests verifying full mutual-supervision (dual GUARDIAN + WARD roles for
 * the same partner UID) across [PartnerDao], [PolicyCloudSyncBridge], [AccountabilityRepository],
 * and [AccountabilityViewModel] using real production classes and an in-memory composite-key DAO.
 */
class MutualSupervisionPartnerTest {

    private lateinit var fakeApp: FakeApplication
    private lateinit var partnerDao: InMemoryCompositePartnerDao
    private lateinit var policyDao: InMemoryDevicePolicyDao

    private var fakeElapsedMs = 10_000L
    private var fakeWallMs = 1_750_000_000_000L

    @Before
    fun setUp() {
        BlackBoxRecorder.awaitPendingWritesBlocking()
        fakeElapsedMs = 10_000L
        fakeWallMs = 1_750_000_000_000L
        TrustedTimeProvider.elapsedRealtimeProvider = { fakeElapsedMs }
        TrustedTimeProvider.wallTimeProvider = { fakeWallMs }
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.setAnchorForTesting(fakeWallMs, fakeElapsedMs, isStrictlyTrusted = true)

        CloudSyncManager.resetForTesting()
        AccountabilityRepository.setInstance(null)
        PolicyRepository.setInstance(null)

        fakeApp = FakeApplication()
        CloudSyncManager.init(fakeApp)
        partnerDao = InMemoryCompositePartnerDao()
        policyDao = InMemoryDevicePolicyDao()
    }

    @After
    fun tearDown() {
        BlackBoxRecorder.awaitPendingWritesBlocking()
        AccountabilityRepository.setInstance(null)
        PolicyRepository.setInstance(null)
        CloudSyncManager.resetForTesting()
        TrustedTimeProvider.resetForTesting()
    }

    @Test
    fun policyCloudSyncBridge_receivingGuardianSupervision_preservesExistingWardRole() = runBlocking {
        // Father's phone already supervises "pRO" as a WARD.
        val existingWard = PartnerEntity(
            id = "pRO",
            pairingCode = "WARD01",
            displayName = "Son Phone",
            role = PartnerEntity.ROLE_WARD,
            status = PartnerEntity.STATUS_ACTIVE,
            createdAt = 1_700_000_001_000L,
            lastSyncAt = 1_700_000_001_000L
        )
        partnerDao.savePartnerBlocking(existingWard)

        var currentPolicy = DevicePolicyBundle.defaultPolicy().copy(version = 1L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val remoteHandler = RemoteTeardownHandler(
                appContext = fakeApp,
                executeTeardown = { Result.success(Unit) },
                onTeardownCompletedNotify = {}
            )
            val bridge = PolicyCloudSyncBridge(
                appContext = fakeApp,
                partnerDao = partnerDao,
                repositoryScope = scope,
                currentPolicyProvider = { currentPolicy },
                remoteTeardownHandler = remoteHandler,
                onApplyCloudPolicy = { policy, _ ->
                    currentPolicy = policy
                    true
                },
                isCloudSyncEnabledProvider = { true }
            )

            val activeGen = bridge.currentListenerGeneration()
            val remotePolicyMap = DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = true,
                guardianUid = "pRO",
                guardianName = "Son Guardian",
                version = 2L,
                updatedAt = fakeWallMs
            ).toMap().filterValues { it != null }.mapValues { it.value!! }

            bridge.processSnapshotForTesting("father", activeGen, remotePolicyMap)

            val allForPro = partnerDao.getPartnersByIdBlocking("pRO")
            assertEquals("Both WARD and GUARDIAN rows must coexist for same UID", 2, allForPro.size)

            val preservedWard = partnerDao.getPartnerByIdAndRoleBlocking("pRO", PartnerEntity.ROLE_WARD)
            assertNotNull("Existing WARD role must not be overwritten", preservedWard)
            assertEquals("Son Phone", preservedWard?.displayName)
            assertEquals("WARD01", preservedWard?.pairingCode)

            val addedGuardian = partnerDao.getPartnerByIdAndRoleBlocking("pRO", PartnerEntity.ROLE_GUARDIAN)
            assertNotNull("Incoming GUARDIAN role must be stored alongside WARD", addedGuardian)
            assertEquals("Son Guardian", addedGuardian?.displayName)
            assertEquals(AccountabilityRepository.CODE_LOCKED, addedGuardian?.pairingCode)

            assertEquals(1, partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_WARD).size)
            assertEquals(1, partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_GUARDIAN).size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun getGuardians_selfHealingFromLocalPolicy_preservesExistingWardRole() = runBlocking {
        // User's phone already has "yyr" saved as a WARD after pairing.
        val existingWard = PartnerEntity(
            id = "yyr",
            pairingCode = "YYR999",
            displayName = "Father Ward",
            role = PartnerEntity.ROLE_WARD,
            status = PartnerEntity.STATUS_ACTIVE,
            createdAt = 1_700_000_002_000L,
            lastSyncAt = 1_700_000_002_000L
        )
        partnerDao.savePartnerBlocking(existingWard)

        // Local policy marks "yyr" as this device's Guardian, while DAO does not yet have the GUARDIAN row.
        val supervisedPolicy = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "yyr",
            guardianName = "Father Guardian",
            version = 4L,
            updatedAt = fakeWallMs
        )
        policyDao.saveDevicePolicyBlocking(DevicePolicyEntity.fromBundle(supervisedPolicy))

        val repo = AccountabilityRepository(
            context = fakeApp,
            partnerDao = partnerDao,
            policyDao = policyDao
        )

        val guardians = repo.getGuardians()
        assertEquals(1, guardians.size)
        assertEquals("yyr", guardians[0].id)
        assertEquals(PartnerEntity.ROLE_GUARDIAN, guardians[0].role)
        assertEquals("Father Guardian", guardians[0].displayName)
        assertEquals(AccountabilityRepository.CODE_LOCKED, guardians[0].pairingCode)

        val preservedWard = partnerDao.getPartnerByIdAndRoleBlocking("yyr", PartnerEntity.ROLE_WARD)
        assertNotNull("Self-healing GUARDIAN must preserve existing WARD row", preservedWard)
        assertEquals("Father Ward", preservedWard?.displayName)
        assertEquals("YYR999", preservedWard?.pairingCode)

        val storedGuardian = partnerDao.getPartnerByIdAndRoleBlocking("yyr", PartnerEntity.ROLE_GUARDIAN)
        assertNotNull("Self-healed GUARDIAN must be persisted in PartnerDao", storedGuardian)
        assertEquals(2, partnerDao.getAllPartnersBlocking().size)
    }

    @Test
    fun onSupervisionRevokedLocally_deletesOnlyGuardianRole_preservesWardRole() = runBlocking {
        partnerDao.savePartnerBlocking(
            PartnerEntity(
                id = "partner_mutual",
                pairingCode = "WARD77",
                displayName = "Supervised Ward",
                role = PartnerEntity.ROLE_WARD,
                status = PartnerEntity.STATUS_ACTIVE,
                createdAt = 1_700_000_003_000L,
                lastSyncAt = 1_700_000_003_000L
            )
        )
        partnerDao.savePartnerBlocking(
            PartnerEntity(
                id = "partner_mutual",
                pairingCode = AccountabilityRepository.CODE_LOCKED,
                displayName = "Former Guardian",
                role = PartnerEntity.ROLE_GUARDIAN,
                status = PartnerEntity.STATUS_ACTIVE,
                createdAt = 1_700_000_004_000L,
                lastSyncAt = 1_700_000_004_000L
            )
        )

        val repo = AccountabilityRepository(
            context = fakeApp,
            partnerDao = partnerDao,
            policyDao = policyDao
        )

        repo.onSupervisionRevokedLocally()

        assertNull(
            "GUARDIAN row must be removed on local supervision revocation",
            partnerDao.getPartnerByIdAndRoleBlocking("partner_mutual", PartnerEntity.ROLE_GUARDIAN)
        )
        assertTrue(repo.getGuardians().isEmpty())

        val remainingWard = partnerDao.getPartnerByIdAndRoleBlocking("partner_mutual", PartnerEntity.ROLE_WARD)
        assertNotNull("WARD row for same UID must remain intact", remainingWard)
        assertEquals("Supervised Ward", remainingWard?.displayName)
        assertEquals("WARD77", remainingWard?.pairingCode)
        assertEquals(listOf(remainingWard), repo.getWards())
    }

    @Test
    fun partnerCloudDocId_isolatesWardAndGuardianDocuments_andPreservesLegacyWardCompatibility() {
        val wardDocId = partnerCloudDocId("uid1", PartnerEntity.ROLE_WARD)
        val guardianDocId = partnerCloudDocId("uid1", PartnerEntity.ROLE_GUARDIAN)

        assertEquals("uid1", wardDocId)
        assertEquals("uid1_GUARDIAN", guardianDocId)
        assertNotEquals(wardDocId, guardianDocId)
    }

    @Test
    fun accountabilityViewModel_observesLivePartnerFlows_updatesWardsAndGuardiansOnSync() = runBlocking {
        val repo = AccountabilityRepository(
            context = fakeApp,
            partnerDao = partnerDao,
            policyDao = policyDao
        )

        val vmStore = ViewModelStore()
        val vm = object : AccountabilityViewModel(
            application = fakeApp,
            accountabilityRepo = repo,
            approvalRepo = null,
            googleAuthManager = null,
            policyRepository = null
        ) {
            override fun shouldAutoObserveOnInit(): Boolean = true
            override fun createIoDispatcher(): CoroutineDispatcher = Dispatchers.Unconfined
            override fun createMainDispatcher(): CoroutineDispatcher = Dispatchers.Unconfined
        }
        vmStore.put("test_vm", vm)

        try {
            withTimeout(2_000L) {
                vm.uiState.first { it.wards.isEmpty() && it.guardians.isEmpty() }
            }

            val wardPartner = PartnerEntity(
                id = "mutual_uid",
                pairingCode = "ABC123",
                displayName = "Ward Peer",
                role = PartnerEntity.ROLE_WARD,
                status = PartnerEntity.STATUS_ACTIVE,
                createdAt = fakeWallMs,
                lastSyncAt = fakeWallMs
            )
            partnerDao.savePartnerBlocking(wardPartner)

            val stateAfterWard = withTimeout(2_000L) {
                vm.uiState.first { it.wards == listOf(wardPartner) }
            }
            assertEquals(listOf(wardPartner), stateAfterWard.wards)
            assertTrue(stateAfterWard.guardians.isEmpty())

            val guardianPartner = PartnerEntity(
                id = "mutual_uid",
                pairingCode = AccountabilityRepository.CODE_LOCKED,
                displayName = "Guardian Peer",
                role = PartnerEntity.ROLE_GUARDIAN,
                status = PartnerEntity.STATUS_ACTIVE,
                createdAt = fakeWallMs + 1000L,
                lastSyncAt = fakeWallMs + 1000L
            )
            partnerDao.savePartnerBlocking(guardianPartner)

            val stateAfterGuardian = withTimeout(2_000L) {
                vm.uiState.first {
                    it.wards == listOf(wardPartner) && it.guardians == listOf(guardianPartner)
                }
            }
            assertEquals(listOf(wardPartner), stateAfterGuardian.wards)
            assertEquals(listOf(guardianPartner), stateAfterGuardian.guardians)

            partnerDao.deletePartnerByIdAndRoleBlocking("mutual_uid", PartnerEntity.ROLE_GUARDIAN)

            val stateAfterRevoke = withTimeout(2_000L) {
                vm.uiState.first { it.guardians.isEmpty() }
            }
            assertEquals(listOf(wardPartner), stateAfterRevoke.wards)
            assertTrue(stateAfterRevoke.guardians.isEmpty())
        } finally {
            vmStore.clear()
        }
    }

    @Test
    fun renamePartner_updatesOnlySpecifiedRole() = runBlocking {
        val initialWard = PartnerEntity(
            id = "mutual_rename_uid",
            pairingCode = "REN111",
            displayName = "Initial Ward Name",
            role = PartnerEntity.ROLE_WARD,
            status = PartnerEntity.STATUS_ACTIVE,
            createdAt = 1_700_000_010_000L,
            lastSyncAt = 1_700_000_010_000L
        )
        val initialGuardian = PartnerEntity(
            id = "mutual_rename_uid",
            pairingCode = AccountabilityRepository.CODE_LOCKED,
            displayName = "Initial Guardian Name",
            role = PartnerEntity.ROLE_GUARDIAN,
            status = PartnerEntity.STATUS_ACTIVE,
            createdAt = 1_700_000_011_000L,
            lastSyncAt = 1_700_000_011_000L
        )
        partnerDao.savePartnerBlocking(initialWard)
        partnerDao.savePartnerBlocking(initialGuardian)

        val daoUpdatedRows = partnerDao.updatePartnerDisplayNameByRoleBlocking(
            id = "mutual_rename_uid",
            role = PartnerEntity.ROLE_WARD,
            newName = "DAO Renamed Ward",
            syncTimestamp = fakeWallMs + 500L
        )
        assertEquals(1, daoUpdatedRows)
        assertEquals(
            "DAO Renamed Ward",
            partnerDao.getPartnerByIdAndRoleBlocking("mutual_rename_uid", PartnerEntity.ROLE_WARD)?.displayName
        )
        assertEquals(
            "Initial Guardian Name",
            partnerDao.getPartnerByIdAndRoleBlocking("mutual_rename_uid", PartnerEntity.ROLE_GUARDIAN)?.displayName
        )

        val repo = AccountabilityRepository(
            context = fakeApp,
            partnerDao = partnerDao,
            policyDao = policyDao
        )
        val repoResult = repo.renamePartner(
            partnerId = "mutual_rename_uid",
            newName = "Repo Renamed Ward",
            role = PartnerEntity.ROLE_WARD
        )
        assertTrue(repoResult.isSuccess)
        assertEquals(
            "Repo Renamed Ward",
            partnerDao.getPartnerByIdAndRoleBlocking("mutual_rename_uid", PartnerEntity.ROLE_WARD)?.displayName
        )
        assertEquals(
            "Initial Guardian Name",
            partnerDao.getPartnerByIdAndRoleBlocking("mutual_rename_uid", PartnerEntity.ROLE_GUARDIAN)?.displayName
        )

        val blankResult = repo.renamePartner(
            partnerId = "mutual_rename_uid",
            newName = "   ",
            role = PartnerEntity.ROLE_WARD
        )
        assertTrue(blankResult.isFailure)
        assertEquals(
            "Repo Renamed Ward",
            partnerDao.getPartnerByIdAndRoleBlocking("mutual_rename_uid", PartnerEntity.ROLE_WARD)?.displayName
        )
    }

    /**
     * In-memory [PartnerDao] faithfully emulating Room's composite primary key `(id, role)`,
     * `OnConflictStrategy.REPLACE`, `ORDER BY created_at DESC`, and reactive [Flow] emissions.
     */
    private class InMemoryCompositePartnerDao : PartnerDao {
        private val lock = Any()
        private val rows = linkedMapOf<Pair<String, String>, PartnerEntity>()
        private val allPartnersFlow = MutableStateFlow<List<PartnerEntity>>(emptyList())

        private fun snapshotSortedLocked(): List<PartnerEntity> =
            rows.values.sortedByDescending { it.createdAt }

        private fun emitLocked() {
            allPartnersFlow.value = snapshotSortedLocked()
        }

        override fun getAllPartnersFlow(): Flow<List<PartnerEntity>> = allPartnersFlow.asStateFlow()

        override suspend fun getAllPartners(): List<PartnerEntity> = getAllPartnersBlocking()

        override fun getAllPartnersBlocking(): List<PartnerEntity> = synchronized(lock) {
            snapshotSortedLocked()
        }

        override fun getPartnersByRoleFlow(role: String): Flow<List<PartnerEntity>> =
            allPartnersFlow.map { list -> list.filter { it.role == role } }

        override suspend fun getPartnersByRole(role: String): List<PartnerEntity> =
            getPartnersByRoleBlocking(role)

        override fun getPartnersByRoleBlocking(role: String): List<PartnerEntity> = synchronized(lock) {
            rows.values.filter { it.role == role }.sortedByDescending { it.createdAt }
        }

        override suspend fun getPartnersById(id: String): List<PartnerEntity> = getPartnersByIdBlocking(id)

        override fun getPartnersByIdBlocking(id: String): List<PartnerEntity> = synchronized(lock) {
            rows.values.filter { it.id == id }.sortedByDescending { it.createdAt }
        }

        override suspend fun getPartnerByIdAndRole(id: String, role: String): PartnerEntity? =
            getPartnerByIdAndRoleBlocking(id, role)

        override fun getPartnerByIdAndRoleBlocking(id: String, role: String): PartnerEntity? = synchronized(lock) {
            rows[id to role]
        }

        override suspend fun savePartner(partner: PartnerEntity) = savePartnerBlocking(partner)

        override fun savePartnerBlocking(partner: PartnerEntity) {
            synchronized(lock) {
                rows[partner.id to partner.role] = partner
                emitLocked()
            }
        }

        override suspend fun deletePartnerByIdAndRole(id: String, role: String): Int =
            deletePartnerByIdAndRoleBlocking(id, role)

        override fun deletePartnerByIdAndRoleBlocking(id: String, role: String): Int = synchronized(lock) {
            val removed = rows.remove(id to role)
            if (removed != null) {
                emitLocked()
                1
            } else {
                0
            }
        }

        override suspend fun updatePartnerDisplayNameByRole(
            id: String,
            role: String,
            newName: String,
            syncTimestamp: Long
        ): Int = updatePartnerDisplayNameByRoleBlocking(id, role, newName, syncTimestamp)

        override fun updatePartnerDisplayNameByRoleBlocking(
            id: String,
            role: String,
            newName: String,
            syncTimestamp: Long
        ): Int = synchronized(lock) {
            val existing = rows[id to role] ?: return 0
            rows[id to role] = existing.copy(displayName = newName, lastSyncAt = syncTimestamp)
            emitLocked()
            1
        }
    }

    private class InMemoryDevicePolicyDao : DevicePolicyDao {
        private val lock = Any()
        private var current: DevicePolicyEntity? = null
        private val flow = MutableStateFlow<DevicePolicyEntity?>(null)

        override fun getDevicePolicyFlow(): Flow<DevicePolicyEntity?> = flow.asStateFlow()

        override suspend fun getDevicePolicy(): DevicePolicyEntity? = getDevicePolicyBlocking()

        override fun getDevicePolicyBlocking(): DevicePolicyEntity? = synchronized(lock) { current }

        override suspend fun saveDevicePolicy(entity: DevicePolicyEntity) = saveDevicePolicyBlocking(entity)

        override fun saveDevicePolicyBlocking(entity: DevicePolicyEntity) {
            synchronized(lock) {
                current = entity
                flow.value = entity
            }
        }
    }

    private class FakeApplication : Application() {
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
                R.string.guardian_default_name -> "Guardian"
                R.string.status_ward_badge -> "Ward"
                R.string.partner_default_name -> "Accountability Partner"
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
            override fun commit(): Boolean {
                apply()
                return true
            }
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
