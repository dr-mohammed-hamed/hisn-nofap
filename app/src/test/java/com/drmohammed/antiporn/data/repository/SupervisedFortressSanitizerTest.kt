package com.drmohammed.antiporn.data.repository

import android.os.SystemClock
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.fortress.model.FortressProfile
import com.drmohammed.antiporn.fortress.model.FortressesConfig
import com.drmohammed.antiporn.testing.FakeTestApplicationContext
import com.drmohammed.antiporn.testing.FakeTestDevicePolicyDao
import com.drmohammed.antiporn.testing.FakeTestPartnerDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SupervisedFortressSanitizerTest {

    private lateinit var context: FakeTestApplicationContext
    private lateinit var repository: PolicyRepository

    @Before
    fun setUp() {
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { 10_000L }
        TrustedTimeProvider.wallTimeProvider = { 1_790_000_000_000L }
        TrustedTimeProvider.setAnchorForTesting(1_790_000_000_000L, 10_000L)
        context = FakeTestApplicationContext()
        AccountabilityRepository.setInstance(
            AccountabilityRepository(
                context = context,
                partnerDao = FakeTestPartnerDao(),
                policyDao = FakeTestDevicePolicyDao()
            )
        )
        repository = PolicyRepository(
            context = context,
            policyDao = FakeTestDevicePolicyDao(),
            partnerDao = FakeTestPartnerDao()
        )
    }

    @After
    fun tearDown() {
        PolicyRepository.setInstance(null)
        AccountabilityRepository.setInstance(null)
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { SystemClock.elapsedRealtime() }
        TrustedTimeProvider.wallTimeProvider = { System.currentTimeMillis() }
    }

    @Test
    fun sanitizeSupervisedSelfPolicy_protectsFortressesCurfewAndRemoteUnlockWhenLocked() {
        val guardianFortresses = FortressesConfig.default()
            .upsert(
                FortressProfile(
                    id = FortressProfile.EMERGENCY_FORTRESS_ID,
                    name = "Guardian Emergency",
                    durationMinutes = 120,
                    isEmergency = true
                )
            )
            .upsert(
                FortressProfile(
                    id = "study",
                    name = "Study",
                    durationMinutes = 60,
                    isInternetCutEnabled = true,
                    allowedPackages = setOf("com.xodo.pdf.reader")
                )
            )

        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-uid",
            isSelfControlAllowed = true,
            selfControlFortressesAllowed = false,
            selfControlFortressUnlockAllowed = false,
            selfControlCurfewAllowed = false,
            isCurfewEnabled = true,
            curfewStartHour = 21,
            curfewEndHour = 6,
            isInstantInternetKillEnabled = true,
            customCurfewWhitelistPackages = setOf("com.whatsapp"),
            fortressesConfig = guardianFortresses,
            remoteModeUnlockTarget = DevicePolicyBundle.REMOTE_MODE_UNLOCK_ALL,
            remoteModeUnlockRequestedAt = 1_790_500_000_000L
        )

        val tampered = current.copy(
            fortressesConfig = FortressesConfig.default(),
            isCurfewEnabled = false,
            curfewStartHour = 23,
            curfewEndHour = 5,
            isInstantInternetKillEnabled = false,
            customCurfewWhitelistPackages = setOf("com.android.chrome"),
            remoteModeUnlockTarget = null,
            remoteModeUnlockRequestedAt = null
        )

        val sanitized = repository.sanitizeSupervisedSelfPolicy(tampered, current)

        assertEquals(guardianFortresses.getAllOrdered(), sanitized.fortressesConfig.getAllOrdered())
        assertTrue(sanitized.isCurfewEnabled)
        assertEquals(21, sanitized.curfewStartHour)
        assertEquals(6, sanitized.curfewEndHour)
        assertTrue(sanitized.isInstantInternetKillEnabled)
        assertEquals(setOf("com.whatsapp"), sanitized.customCurfewWhitelistPackages)
        assertEquals(DevicePolicyBundle.REMOTE_MODE_UNLOCK_ALL, sanitized.remoteModeUnlockTarget)
        assertEquals(1_790_500_000_000L, sanitized.remoteModeUnlockRequestedAt)
    }

    @Test
    fun mergeAndSaveSelfPolicyPatch_preservesGuardianPermissionFlagsAndLockedFortressConfig() = runBlocking {
        val guardianFortresses = FortressesConfig.default().upsert(
            FortressProfile(id = "locked", name = "Locked", durationMinutes = 90)
        )
        val initial = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            guardianName = "Parent",
            isSelfControlAllowed = true,
            selfControlFortressesAllowed = false,
            selfControlFortressUnlockAllowed = false,
            selfControlCurfewAllowed = false,
            isCurfewEnabled = true,
            fortressesConfig = guardianFortresses,
            remoteModeUnlockTarget = DevicePolicyBundle.REMOTE_MODE_UNLOCK_CURFEW,
            remoteModeUnlockRequestedAt = 1_790_600_000_000L
        )
        repository.updateLocalPolicyWithoutEnforcing(initial)

        val saved = repository.mergeAndSaveSelfPolicyPatch { current ->
            current.copy(
                selfControlFortressesAllowed = true,
                selfControlFortressUnlockAllowed = true,
                selfControlCurfewAllowed = true,
                fortressesConfig = FortressesConfig.default(),
                isCurfewEnabled = false,
                remoteModeUnlockTarget = null,
                remoteModeUnlockRequestedAt = null
            )
        }

        assertNotNull(saved)
        assertFalse(saved?.selfControlFortressesAllowed ?: true)
        assertFalse(saved?.selfControlFortressUnlockAllowed ?: true)
        assertFalse(saved?.selfControlCurfewAllowed ?: true)
        assertEquals(guardianFortresses.getAllOrdered(), saved?.fortressesConfig?.getAllOrdered())
        assertTrue(saved?.isCurfewEnabled ?: false)
        assertEquals(DevicePolicyBundle.REMOTE_MODE_UNLOCK_CURFEW, saved?.remoteModeUnlockTarget)
        assertEquals(1_790_600_000_000L, saved?.remoteModeUnlockRequestedAt)
    }

    @Test
    fun processSnapshot_equalVersionCloudSyncPreservesWardCustomFortressesWhileNewerVersionApplies() = runBlocking {
        val study = FortressProfile(id = "study", name = "حصن المذاكرة", durationMinutes = 60, isInternetCutEnabled = false)
        var currentPolicy = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            guardianName = "Parent",
            isSelfControlAllowed = true,
            selfControlFortressesAllowed = true,
            fortressesConfig = FortressesConfig.default().upsert(study),
            version = 172L,
            updatedAt = 1_790_000_000_000L
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val bridge = PolicyCloudSyncBridge(
                appContext = context,
                partnerDao = FakeTestPartnerDao(),
                repositoryScope = scope,
                currentPolicyProvider = { currentPolicy },
                remoteTeardownHandler = RemoteTeardownHandler(
                    appContext = context,
                    executeTeardown = { Result.success(Unit) },
                    onTeardownCompletedNotify = {}
                ),
                onApplyCloudPolicy = { policy, _ ->
                    currentPolicy = policy
                    true
                },
                isCloudSyncEnabledProvider = { true }
            )
            val gen = bridge.currentListenerGeneration()

            // Equal version (172 == 172) with newer updatedAt and empty custom fortresses -> MUST preserve "study"
            val equalVersionCloudMap = DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = true,
                guardianUid = "guardian-1",
                guardianName = "Parent",
                isSelfControlAllowed = true,
                selfControlFortressesAllowed = true,
                fortressesConfig = FortressesConfig.default(),
                version = 172L,
                updatedAt = 1_790_000_050_000L
            ).toMap().filterValues { it != null }.mapValues { it.value!! }

            bridge.processSnapshotForTesting("ward-1", gen, equalVersionCloudMap)
            assertEquals(1, currentPolicy.fortressesConfig.getCustomFortresses().size)
            assertEquals("study", currentPolicy.fortressesConfig.getCustomFortresses().first().id)

            // Strictly newer version (173 > 172) from Guardian explicitly removing custom fortress -> MUST apply
            val newerVersionCloudMap = DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = true,
                guardianUid = "guardian-1",
                guardianName = "Parent",
                isSelfControlAllowed = true,
                selfControlFortressesAllowed = true,
                fortressesConfig = FortressesConfig.default(),
                version = 173L,
                updatedAt = 1_790_000_060_000L
            ).toMap().filterValues { it != null }.mapValues { it.value!! }

            bridge.processSnapshotForTesting("ward-1", gen, newerVersionCloudMap)
            assertTrue(currentPolicy.fortressesConfig.getCustomFortresses().isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
