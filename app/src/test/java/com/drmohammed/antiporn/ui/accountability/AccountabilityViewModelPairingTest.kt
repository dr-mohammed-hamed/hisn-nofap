package com.drmohammed.antiporn.ui.accountability

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.drmohammed.antiporn.cloud.CloudSyncManager
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class AccountabilityViewModelPairingTest {

    private lateinit var fakeApp: FakeApplication
    private var fakeElapsed = 10_000L
    private var fakeWall = 1_700_000_000_000L

    @Before
    fun setUp() {
        fakeElapsed = 10_000L
        fakeWall = 1_700_000_000_000L
        TrustedTimeProvider.elapsedRealtimeProvider = { fakeElapsed }
        TrustedTimeProvider.wallTimeProvider = { fakeWall }
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.setAnchorForTesting(fakeWall, fakeElapsed, isStrictlyTrusted = true)
        CloudSyncManager.resetForTesting()
        fakeApp = FakeApplication()
        CloudSyncManager.init(fakeApp)
    }

    @After
    fun tearDown() {
        CloudSyncManager.resetForTesting()
        TrustedTimeProvider.resetForTesting()
    }

    @Test
    fun guardianWithActiveWards_canStartTemporaryPairingSession_andReceivesCodeInUiState() = runBlocking {
        val activeWard = PartnerEntity(
            id = "ward-1",
            pairingCode = "WARD01",
            displayName = "Ward One",
            role = PartnerEntity.ROLE_WARD,
            status = PartnerEntity.STATUS_ACTIVE,
            createdAt = fakeWall,
            lastSyncAt = fakeWall
        )
        val expectedExpiresAt = fakeWall + 15 * 60 * 1000L
        val repo = FakeAccountabilityRepository(
            context = fakeApp,
            wardsList = listOf(activeWard),
            onGetOrCreateCode = {
                CloudSyncManager.startTemporaryPairingSession(fakeApp, "ABC123", expectedExpiresAt)
                "ABC123"
            }
        )

        val vm = AccountabilityViewModel(
            application = fakeApp,
            accountabilityRepo = repo,
            approvalRepo = null,
            googleAuthManager = null,
            policyRepository = null,
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
            policyProvider = { DevicePolicyBundle.defaultPolicy() },
            startFirestoreSyncAction = {},
            rollbackCloudSyncAction = {},
            autoObserve = false
        )

        CloudSyncManager.setCloudSyncEnabled(fakeApp, true)
        vm.loadPairingCode()
        assertEquals("", vm.uiState.value.myPairingCode)
        assertFalse(vm.uiState.value.isTemporaryPairingActive)

        var callbackResult: Result<String>? = null
        vm.startTemporaryPairingSession { callbackResult = it }

        assertEquals(Result.success("ABC123"), callbackResult)
        assertTrue(vm.uiState.value.isCloudSyncEnabled)
        assertTrue(vm.uiState.value.isTemporaryPairingActive)
        assertEquals("ABC123", vm.uiState.value.myPairingCode)
        assertEquals(expectedExpiresAt, vm.uiState.value.pairingExpiresAt)

        vm.cancelTemporaryPairingSession()
    }

    @Test
    fun loadPairingCode_restoresActiveSessionFromSharedPreferences_evenWhenWardsExist() = runBlocking {
        val activeWard = PartnerEntity(
            id = "ward-1",
            pairingCode = "WARD01",
            displayName = "Ward One",
            role = PartnerEntity.ROLE_WARD,
            status = PartnerEntity.STATUS_ACTIVE,
            createdAt = fakeWall,
            lastSyncAt = fakeWall
        )
        val expectedExpiresAt = fakeWall + 10 * 60 * 1000L
        CloudSyncManager.setCloudSyncEnabled(fakeApp, true)
        CloudSyncManager.startTemporaryPairingSession(fakeApp, "XYZ789", expectedExpiresAt)

        // Reset in-memory cache so restoration must read from SharedPreferences via getActivePairingSession(app)
        CloudSyncManager.resetForTesting()

        val repo = FakeAccountabilityRepository(
            context = fakeApp,
            wardsList = listOf(activeWard)
        )

        val vm = AccountabilityViewModel(
            application = fakeApp,
            accountabilityRepo = repo,
            approvalRepo = null,
            googleAuthManager = null,
            policyRepository = null,
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
            policyProvider = { DevicePolicyBundle.defaultPolicy() },
            startFirestoreSyncAction = {},
            rollbackCloudSyncAction = {},
            autoObserve = false
        )

        vm.loadPairingCode()

        assertTrue(vm.uiState.value.isCloudSyncEnabled)
        assertTrue(vm.uiState.value.isTemporaryPairingActive)
        assertEquals("XYZ789", vm.uiState.value.myPairingCode)
        assertEquals(expectedExpiresAt, vm.uiState.value.pairingExpiresAt)

        vm.cancelTemporaryPairingSession()
    }

    @Test
    fun failedGetOrCreateMyPairingCode_doesNotExposeFakeCode_andRollsBackCloudSyncWhenUnsupervisedWithoutWards() = runBlocking {
        var rollbackInvoked = false
        val repo = FakeAccountabilityRepository(
            context = fakeApp,
            wardsList = emptyList(),
            onGetOrCreateCode = {
                throw IllegalStateException("Firestore publish failed")
            }
        )

        val vm = AccountabilityViewModel(
            application = fakeApp,
            accountabilityRepo = repo,
            approvalRepo = null,
            googleAuthManager = null,
            policyRepository = null,
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
            policyProvider = { DevicePolicyBundle.defaultPolicy() },
            startFirestoreSyncAction = {},
            rollbackCloudSyncAction = { app ->
                rollbackInvoked = true
                CloudSyncManager.setCloudSyncEnabled(app, false)
            },
            autoObserve = false
        )

        var callbackResult: Result<String>? = null
        vm.startTemporaryPairingSession { callbackResult = it }

        assertTrue(callbackResult?.isFailure == true)
        assertTrue(rollbackInvoked)
        assertFalse(vm.uiState.value.isTemporaryPairingActive)
        assertEquals("", vm.uiState.value.myPairingCode)
        assertEquals(0L, vm.uiState.value.pairingExpiresAt)
        assertEquals(0L, vm.uiState.value.pairingRemainingMs)
        assertFalse(vm.uiState.value.isCloudSyncEnabled)
        assertFalse(CloudSyncManager.isCloudSyncEnabled(fakeApp))
    }

    private class FakeAccountabilityRepository(
        context: Context,
        private val wardsList: List<PartnerEntity> = emptyList(),
        private val onGetOrCreateCode: suspend () -> String = { "DEF456" }
    ) : AccountabilityRepository(
        context = context,
        partnerDao = dummyPartnerDao(),
        policyDao = dummyPolicyDao()
    ) {
        override suspend fun getOrCreateMyPairingCode(): String = onGetOrCreateCode()
        override suspend fun getWards(): List<PartnerEntity> = wardsList
        override suspend fun getGuardians(): List<PartnerEntity> = emptyList()
        override fun getMyDisplayName(): String = "Test Device"
        override suspend fun cancelMyPairingCode(): Result<Unit> {
            CloudSyncManager.resetForTesting()
            return Result.success(Unit)
        }
    }

    private class FakeApplication : Application() {
        private val prefsByName = mutableMapOf<String, InMemorySharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            val key = name ?: "default"
            return prefsByName.getOrPut(key) { InMemorySharedPreferences() }
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val store = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = store.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = store[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (store[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = store[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = store[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = store[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = store[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = store.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var cleared = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) { removed.remove(key); pending[key] = value }
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) { removed.remove(key); pending[key] = values }
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) { removed.remove(key); pending[key] = value }
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) { removed.remove(key); pending[key] = value }
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) { removed.remove(key); pending[key] = value }
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) { removed.remove(key); pending[key] = value }
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) { pending.remove(key); removed.add(key) }
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                cleared = true; pending.clear(); removed.clear()
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (cleared) store.clear()
                removed.forEach { store.remove(it) }
                store.putAll(pending)
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    companion object {
        private fun dummyPartnerDao(): PartnerDao {
            return Proxy.newProxyInstance(
                PartnerDao::class.java.classLoader,
                arrayOf(PartnerDao::class.java)
            ) { _, method, _ ->
                if (Flow::class.java.isAssignableFrom(method.returnType)) emptyFlow<Any>()
                else if (List::class.java.isAssignableFrom(method.returnType)) emptyList<Any>()
                else null
            } as PartnerDao
        }

        private fun dummyPolicyDao(): DevicePolicyDao {
            return Proxy.newProxyInstance(
                DevicePolicyDao::class.java.classLoader,
                arrayOf(DevicePolicyDao::class.java)
            ) { _, method, _ ->
                if (Flow::class.java.isAssignableFrom(method.returnType)) emptyFlow<Any>()
                else null
            } as DevicePolicyDao
        }
    }
}
