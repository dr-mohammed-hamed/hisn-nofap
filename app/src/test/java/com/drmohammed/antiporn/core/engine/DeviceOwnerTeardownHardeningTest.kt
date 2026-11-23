package com.drmohammed.antiporn.core.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.drmohammed.antiporn.cloud.CloudSyncManager
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.NapConfig
import com.drmohammed.antiporn.curfew.NapController
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.repository.PolicyCloudSyncBridge
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.data.repository.RemoteTeardownHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Pure JVM unit tests for Device Owner teardown server verification,
 * post-teardown local policy cleanup, remote command gating, and transition guards.
 */
class DeviceOwnerTeardownHardeningTest {

    @After
    fun tearDown() {
        ProtectionTransitionGuard.isTeardownInProgress = false
    }

    @Test
    fun validateGuardianTeardownServerState_acceptsValidCommand() {
        val requestedAt = 1_790_000_100_000L
        val unlockAt = requestedAt - 60_000L
        assertTrue(
            DeviceOwnerTeardownManager.validateGuardianTeardownServerState(
                remoteStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
                remoteUnlockAt = unlockAt,
                remoteTeardownRequestedAt = requestedAt,
                expectedRequestedAt = requestedAt,
                nowMs = requestedAt
            )
        )
    }

    @Test
    fun validateGuardianTeardownServerState_rejectsNonPendingStatuses() {
        val ts = 1_790_000_100_000L
        val invalidStatuses = listOf(
            DevicePolicyBundle.DEACTIVATION_STATUS_CANCELLED,
            DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED,
            DevicePolicyBundle.DEACTIVATION_STATUS_REJECTED,
            null
        )
        for (status in invalidStatuses) {
            assertFalse(
                "Expected rejection for status=$status",
                DeviceOwnerTeardownManager.validateGuardianTeardownServerState(
                    remoteStatus = status,
                    remoteUnlockAt = ts - 60_000L,
                    remoteTeardownRequestedAt = ts,
                    expectedRequestedAt = ts,
                    nowMs = ts
                )
            )
        }
    }

    @Test
    fun validateGuardianTeardownServerState_rejectsInvalidOrMismatchedTimestamps() {
        val ts = 1_790_000_100_000L
        assertFalse(
            DeviceOwnerTeardownManager.validateGuardianTeardownServerState(
                remoteStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
                remoteUnlockAt = ts - 60_000L,
                remoteTeardownRequestedAt = ts,
                expectedRequestedAt = 0L,
                nowMs = ts
            )
        )
        assertFalse(
            DeviceOwnerTeardownManager.validateGuardianTeardownServerState(
                remoteStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
                remoteUnlockAt = ts - 60_000L,
                remoteTeardownRequestedAt = ts - 1L,
                expectedRequestedAt = ts,
                nowMs = ts
            )
        )
        assertFalse(
            DeviceOwnerTeardownManager.validateGuardianTeardownServerState(
                remoteStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
                remoteUnlockAt = ts - 60_000L,
                remoteTeardownRequestedAt = 0L,
                expectedRequestedAt = ts,
                nowMs = ts
            )
        )
    }

    @Test
    fun validateGuardianTeardownServerState_rejectsUnelapsedOrNonPositiveUnlockAt() {
        val ts = 1_790_000_100_000L
        assertFalse(
            DeviceOwnerTeardownManager.validateGuardianTeardownServerState(
                remoteStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
                remoteUnlockAt = 0L,
                remoteTeardownRequestedAt = ts,
                expectedRequestedAt = ts,
                nowMs = ts
            )
        )
        assertFalse(
            DeviceOwnerTeardownManager.validateGuardianTeardownServerState(
                remoteStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
                remoteUnlockAt = ts + 60_000L,
                remoteTeardownRequestedAt = ts,
                expectedRequestedAt = ts,
                nowMs = ts
            )
        )
    }

    @Test
    fun buildPostTeardownLocalPolicy_stripsAllLocksAndIncrementsVersion() {
        val lockedPolicy = DevicePolicyBundle.defaultPolicy().copy(
            isCurfewEnabled = true,
            isInstantInternetKillEnabled = true,
            isDateTimeLocked = true,
            isSupervised = true,
            guardianUid = "g1",
            guardianName = "Abu",
            remoteTeardownRequestedAt = 1000L,
            deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
            deactivationRequestedAt = 900L,
            deactivationUnlockAt = 950L,
            deactivationHasQr = true,
            version = 10L
        )
        val now = 1_790_000_200_000L
        val cleaned = DeviceOwnerTeardownManager.buildPostTeardownLocalPolicy(lockedPolicy, now)

        assertFalse(cleaned.isCurfewEnabled)
        assertFalse(cleaned.isInstantInternetKillEnabled)
        assertFalse(cleaned.isDateTimeLocked)
        assertFalse(cleaned.isSupervised)
        assertNull(cleaned.guardianUid)
        assertNull(cleaned.guardianName)
        assertNull(cleaned.remoteTeardownRequestedAt)
        assertNull(cleaned.deactivationRequestedAt)
        assertNull(cleaned.deactivationUnlockAt)
        assertFalse(cleaned.deactivationHasQr)
        assertEquals(DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED, cleaned.deactivationStatus)
        assertEquals(11L, cleaned.version)
        assertEquals(now, cleaned.updatedAt)
    }

    @Test
    fun buildPostTeardownLocalPolicy_enforcesMinimumVersionTwo() {
        val v0Policy = DevicePolicyBundle.defaultPolicy().copy(version = 0L)
        val cleaned = DeviceOwnerTeardownManager.buildPostTeardownLocalPolicy(v0Policy, 5000L)
        assertEquals(2L, cleaned.version)
    }

    @Test
    fun shouldExecuteGuardianTeardown_enforcesSupervisionStatusAndIdempotency() {
        val base = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
            remoteTeardownRequestedAt = 2_000L
        )

        assertTrue(PolicyRepository.shouldExecuteGuardianTeardown(base, lastProcessedRequestedAt = 1_000L))
        assertFalse(PolicyRepository.shouldExecuteGuardianTeardown(base, lastProcessedRequestedAt = 2_000L))
        assertFalse(PolicyRepository.shouldExecuteGuardianTeardown(base, lastProcessedRequestedAt = 3_000L))
        assertFalse(PolicyRepository.shouldExecuteGuardianTeardown(base.copy(remoteTeardownRequestedAt = null), 0L))
        assertFalse(PolicyRepository.shouldExecuteGuardianTeardown(base.copy(remoteTeardownRequestedAt = 0L), 0L))
        assertFalse(PolicyRepository.shouldExecuteGuardianTeardown(base.copy(isSupervised = false), 0L))
        assertFalse(PolicyRepository.shouldExecuteGuardianTeardown(base.copy(guardianUid = null), 0L))
        assertFalse(PolicyRepository.shouldExecuteGuardianTeardown(base.copy(guardianUid = "  "), 0L))
        assertFalse(
            PolicyRepository.shouldExecuteGuardianTeardown(
                base.copy(deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_CANCELLED),
                0L
            )
        )
        assertFalse(
            PolicyRepository.shouldExecuteGuardianTeardown(
                base.copy(deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED),
                0L
            )
        )
        assertFalse(
            PolicyRepository.shouldExecuteGuardianTeardown(
                base.copy(deactivationStatus = null),
                0L
            )
        )
    }

    @Test
    fun remoteTeardownHandler_failureLeavesMarkerUnsetAndAllowsRetry() = runBlocking {
        val context = FakePrefsContext()
        var attempts = 0
        val handler = RemoteTeardownHandler(
            appContext = context,
            executeTeardown = {
                attempts++
                if (attempts == 1) Result.failure(IllegalStateException("Transient failure"))
                else Result.success(Unit)
            },
            onTeardownCompletedNotify = {}
        )
        val requestedAt = 1_790_000_250_000L
        val pendingTeardownPolicy = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-uid",
            deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
            remoteTeardownRequestedAt = requestedAt
        )

        assertFalse(handler.checkAndExecutePendingGuardianTeardown(pendingTeardownPolicy))
        val prefs = context.getSharedPreferences(PolicyRepository.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(0L, prefs.getLong(PolicyRepository.KEY_LAST_PROCESSED_REMOTE_TEARDOWN_AT, 0L))

        assertTrue(handler.retryPendingGuardianTeardownIfNeeded(pendingTeardownPolicy))
        assertEquals(2, attempts)
        assertEquals(requestedAt, prefs.getLong(PolicyRepository.KEY_LAST_PROCESSED_REMOTE_TEARDOWN_AT, 0L))
    }

    @Test
    fun remoteTeardownHandler_successPersistsMarkerAndIsIdempotentAcrossRetries() = runBlocking {
        val context = FakePrefsContext()
        var executions = 0
        val handler = RemoteTeardownHandler(
            appContext = context,
            executeTeardown = {
                executions++
                Result.success(Unit)
            },
            onTeardownCompletedNotify = {}
        )
        val requestedAt = 1_790_000_300_000L
        val pendingTeardownPolicy = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-uid",
            deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
            remoteTeardownRequestedAt = requestedAt
        )

        assertTrue(handler.checkAndExecutePendingGuardianTeardown(pendingTeardownPolicy))
        assertFalse(handler.checkAndExecutePendingGuardianTeardown(pendingTeardownPolicy))
        assertFalse(handler.retryPendingGuardianTeardownIfNeeded(pendingTeardownPolicy))
        assertFalse(
            handler.checkAndExecutePendingGuardianTeardown(
                pendingTeardownPolicy.copy(remoteTeardownRequestedAt = requestedAt - 1L)
            )
        )
        assertEquals(1, executions)
    }

    @Test
    fun curfewConfig_clearTransientFlagsForTeardownResetsAllTransientState() {
        val context = FakePrefsContext()
        CurfewConfig.setLastBlockEnforced(context, true)
        CurfewConfig.setDnsRestorePending(context, true)
        CurfewConfig.setPrivateDnsBlockedByUnaffiliatedUsers(context, true)
        CurfewConfig.addTrackedSuspendedPackages(context, listOf("com.example.one", "com.example.two"))
        val curfewPrefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        curfewPrefs.edit()
            .putBoolean(CurfewConfig.KEY_MANUAL_TEST_ACTIVE, true)
            .putLong(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL, 1_790_000_999_000L)
            .commit()

        CurfewConfig.clearTransientFlagsForTeardown(context)

        assertFalse(CurfewConfig.isLastBlockEnforced(context))
        assertFalse(CurfewConfig.isDnsRestorePending(context))
        assertFalse(CurfewConfig.isPrivateDnsBlockedByUnaffiliatedUsers(context))
        assertTrue(CurfewConfig.getTrackedSuspendedPackages(context).isEmpty())
        assertFalse(curfewPrefs.getBoolean(CurfewConfig.KEY_MANUAL_TEST_ACTIVE, true))
        assertFalse(curfewPrefs.contains(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL))
    }

    @Test
    fun napController_clearNapForTeardownClearsActiveNapSynchronously() {
        val context = FakePrefsContext()
        val napPrefs = context.getSharedPreferences(NapConfig.PREFS_NAME, Context.MODE_PRIVATE)
        napPrefs.edit()
            .putBoolean(NapConfig.KEY_NAP_ACTIVE, true)
            .putLong(NapConfig.KEY_NAP_START_ELAPSED, 10_000L)
            .putLong(NapConfig.KEY_NAP_END_ELAPSED, 3_610_000L)
            .putLong(NapConfig.KEY_NAP_END_WALL_MS, 1_790_003_600_000L)
            .putInt(NapConfig.KEY_NAP_BOOT_COUNT, 7)
            .commit()

        NapController.clearNapForTeardown(context)

        assertFalse(napPrefs.getBoolean(NapConfig.KEY_NAP_ACTIVE, true))
        assertEquals(0L, napPrefs.getLong(NapConfig.KEY_NAP_START_ELAPSED, -1L))
        assertEquals(0L, napPrefs.getLong(NapConfig.KEY_NAP_END_ELAPSED, -1L))
        assertEquals(0L, napPrefs.getLong(NapConfig.KEY_NAP_END_WALL_MS, -1L))
        assertEquals(-1, napPrefs.getInt(NapConfig.KEY_NAP_BOOT_COUNT, 0))
        assertFalse(NapController.isNapActive(context))
    }

    @Test
    fun cloudSyncManager_pendingTeardownAckRoundTripAndValidation() {
        val context = FakePrefsContext()
        assertFalse(CloudSyncManager.hasPendingCloudTeardownAck(context))
        assertNull(CloudSyncManager.getPendingCloudTeardownAck(context))

        val ackTs = 1_790_000_500_000L
        CloudSyncManager.setPendingCloudTeardownAck(context, "ward-uid-123", ackTs)

        assertTrue(CloudSyncManager.hasPendingCloudTeardownAck(context))
        assertEquals("ward-uid-123" to ackTs, CloudSyncManager.getPendingCloudTeardownAck(context))

        // Invalid timestamp must not surface as valid ack
        CloudSyncManager.setPendingCloudTeardownAck(context, "ward-uid-123", 0L)
        assertFalse(CloudSyncManager.hasPendingCloudTeardownAck(context))
        assertNull(CloudSyncManager.getPendingCloudTeardownAck(context))

        CloudSyncManager.setPendingCloudTeardownAck(context, "ward-uid-123", ackTs)
        CloudSyncManager.clearPendingCloudTeardownAck(context)
        assertFalse(CloudSyncManager.hasPendingCloudTeardownAck(context))
    }

    @Test
    fun policyCloudSyncBridge_ignoresSnapshotWhenTeardownInProgressOrGenerationStale() = runBlocking {
        val context = FakePrefsContext()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var teardownInvocations = 0
        var applyInvocations = 0
        var cloudSyncEnabled = true
        val remoteHandler = RemoteTeardownHandler(
            appContext = context,
            executeTeardown = {
                teardownInvocations++
                Result.success(Unit)
            },
            onTeardownCompletedNotify = {}
        )
        val dummyPartnerDao = Proxy.newProxyInstance(
            PartnerDao::class.java.classLoader,
            arrayOf(PartnerDao::class.java)
        ) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                List::class.java -> emptyList<Any>()
                else -> null
            }
        } as PartnerDao

        try {
            val bridge = PolicyCloudSyncBridge(
                appContext = context,
                partnerDao = dummyPartnerDao,
                repositoryScope = scope,
                currentPolicyProvider = { DevicePolicyBundle.defaultPolicy() },
                remoteTeardownHandler = remoteHandler,
                onApplyCloudPolicy = { _, _ ->
                    applyInvocations++
                    true
                },
                isCloudSyncEnabledProvider = { cloudSyncEnabled }
            )

            val activeGen = bridge.currentListenerGeneration()
            val remotePolicyMap = DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = true,
                guardianUid = "guardian-1",
                deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
                remoteTeardownRequestedAt = 1_790_000_600_000L,
                version = 5L
            ).toMap().filterValues { it != null }.mapValues { it.value!! }

            // 1. Stale generation must be ignored
            bridge.processSnapshotForTesting("ward-1", activeGen - 1L, remotePolicyMap)
            assertEquals(0, teardownInvocations)
            assertEquals(0, applyInvocations)

            // 2. Teardown in progress must ignore queued snapshot
            ProtectionTransitionGuard.isTeardownInProgress = true
            bridge.processSnapshotForTesting("ward-1", activeGen, remotePolicyMap)
            assertEquals(0, teardownInvocations)
            assertEquals(0, applyInvocations)

            // 3. Disabled cloud sync must ignore queued snapshot
            ProtectionTransitionGuard.isTeardownInProgress = false
            cloudSyncEnabled = false
            bridge.processSnapshotForTesting("ward-1", activeGen, remotePolicyMap)
            assertEquals(0, teardownInvocations)
            assertEquals(0, applyInvocations)

            // 4. Advance generation -> old generation still rejected
            cloudSyncEnabled = true
            val nextGen = bridge.advanceListenerGenerationForTesting()
            bridge.processSnapshotForTesting("ward-1", activeGen, remotePolicyMap)
            assertEquals(0, teardownInvocations)

            // 5. Current generation + enabled + no teardown executes remote teardown handler
            bridge.processSnapshotForTesting("ward-1", nextGen, remotePolicyMap)
            assertEquals(1, teardownInvocations)
        } finally {
            scope.cancel()
        }
    }

    private class FakePrefsContext : ContextWrapper(null) {
        private val prefsByName = mutableMapOf<String, InMemorySharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getPackageName(): String = "com.drmohammed.antiporn"

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefsByName.getOrPut(name ?: "default") { InMemorySharedPreferences() }
        }

        override fun getSystemService(name: String): Any? = null
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
