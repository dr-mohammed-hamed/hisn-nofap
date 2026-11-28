package com.drmohammed.antiporn.data.repository

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.model.PolicyMergeUtils
import com.drmohammed.antiporn.fortress.model.FortressProfile
import com.drmohammed.antiporn.fortress.model.FortressesConfig
import com.drmohammed.antiporn.ui.accountability.RemoteAppManagerUiState
import com.drmohammed.antiporn.ui.accountability.RemoteAppManagerViewModel
import com.drmohammed.antiporn.ui.control.SelfControlViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurfewDataPreservationTest {

    // =========================================================================
    // 1. PolicyCloudSyncBridge.preserveWardSelfEditableFieldsOnEqualVersion
    // =========================================================================

    @Test
    fun `preserveWardSelfEditableFieldsOnEqualVersion preserves curfew lists and custom fortresses on equal version`() {
        val studyFortress = FortressProfile(
            id = "study",
            name = "Study Fortress",
            durationMinutes = 60,
            allowedPackages = setOf("com.xodo.pdf.reader")
        )
        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            guardianName = "Parent",
            isSelfControlAllowed = true,
            selfControlCurfewAllowed = true,
            selfControlFortressesAllowed = true,
            customCurfewWhitelistPackages = setOf("com.whatsapp", "com.google.android.gm"),
            curfewVpnBypassPackages = setOf("com.whatsapp"),
            fortressesConfig = FortressesConfig.default().upsert(studyFortress),
            version = 10L
        )
        val cloud = current.copy(
            customCurfewWhitelistPackages = emptySet(),
            curfewVpnBypassPackages = emptySet(),
            fortressesConfig = FortressesConfig.default(),
            version = 10L
        )

        val result = PolicyCloudSyncBridge.preserveWardSelfEditableFieldsOnEqualVersion(
            current = current,
            effectiveCloudPolicy = cloud,
            candidateCloudVersion = 10L,
            isFromGuardian = true
        )

        assertTrue(result.preservedCurfewLists)
        assertTrue(result.preservedCustomFortresses)
        assertEquals(setOf("com.whatsapp", "com.google.android.gm"), result.policy.customCurfewWhitelistPackages)
        assertEquals(setOf("com.whatsapp"), result.policy.curfewVpnBypassPackages)
        assertEquals(1, result.policy.fortressesConfig.getCustomFortresses().size)
        assertEquals("study", result.policy.fortressesConfig.getCustomFortresses().first().id)
    }

    @Test
    fun `preserveWardSelfEditableFieldsOnEqualVersion preserves curfew lists on older cloud version`() {
        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            isSelfControlAllowed = true,
            selfControlCurfewAllowed = true,
            customCurfewWhitelistPackages = setOf("com.whatsapp", "com.athanotify"),
            curfewVpnBypassPackages = setOf("com.whatsapp"),
            version = 12L
        )
        val olderCloud = current.copy(
            customCurfewWhitelistPackages = setOf("com.whatsapp"),
            curfewVpnBypassPackages = emptySet(),
            version = 11L
        )

        val result = PolicyCloudSyncBridge.preserveWardSelfEditableFieldsOnEqualVersion(
            current = current,
            effectiveCloudPolicy = olderCloud,
            candidateCloudVersion = 11L,
            isFromGuardian = true
        )

        assertTrue(result.preservedCurfewLists)
        assertEquals(setOf("com.whatsapp", "com.athanotify"), result.policy.customCurfewWhitelistPackages)
        assertEquals(setOf("com.whatsapp"), result.policy.curfewVpnBypassPackages)
    }

    @Test
    fun `preserveWardSelfEditableFieldsOnEqualVersion strips guardian blacklisted packages from preserved curfew lists`() {
        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            isSelfControlAllowed = true,
            selfControlCurfewAllowed = true,
            customCurfewWhitelistPackages = setOf("com.whatsapp", "com.blocked.app"),
            curfewVpnBypassPackages = setOf("com.whatsapp", "com.blocked.app"),
            version = 10L
        )
        val cloudWithBlacklist = current.copy(
            customBlacklistPackages = setOf("com.blocked.app"),
            customCurfewWhitelistPackages = emptySet(),
            curfewVpnBypassPackages = emptySet(),
            version = 10L
        )

        val result = PolicyCloudSyncBridge.preserveWardSelfEditableFieldsOnEqualVersion(
            current = current,
            effectiveCloudPolicy = cloudWithBlacklist,
            candidateCloudVersion = 10L,
            isFromGuardian = true
        )

        assertTrue(result.preservedCurfewLists)
        assertEquals(setOf("com.whatsapp"), result.policy.customCurfewWhitelistPackages)
        assertEquals(setOf("com.whatsapp"), result.policy.curfewVpnBypassPackages)
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("com.blocked.app"))
        assertFalse(result.policy.curfewVpnBypassPackages.contains("com.blocked.app"))
    }

    @Test
    fun `preserveWardSelfEditableFieldsOnEqualVersion lets newer guardian version win`() {
        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            isSelfControlAllowed = true,
            selfControlCurfewAllowed = true,
            customCurfewWhitelistPackages = setOf("com.whatsapp", "com.google.android.gm"),
            curfewVpnBypassPackages = setOf("com.whatsapp"),
            version = 10L
        )
        val newerCloud = current.copy(
            customCurfewWhitelistPackages = setOf("com.athanotify"),
            curfewVpnBypassPackages = emptySet(),
            version = 11L
        )

        val result = PolicyCloudSyncBridge.preserveWardSelfEditableFieldsOnEqualVersion(
            current = current,
            effectiveCloudPolicy = newerCloud,
            candidateCloudVersion = 11L,
            isFromGuardian = true
        )

        assertFalse(result.preservedCurfewLists)
        assertEquals(setOf("com.athanotify"), result.policy.customCurfewWhitelistPackages)
        assertEquals(emptySet<String>(), result.policy.curfewVpnBypassPackages)
    }

    @Test
    fun `preserveWardSelfEditableFieldsOnEqualVersion lets guardian cloud lists win when curfew or master self-control is locked`() {
        val lockedCurfewCurrent = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            isSelfControlAllowed = true,
            selfControlCurfewAllowed = false,
            customCurfewWhitelistPackages = setOf("com.whatsapp"),
            curfewVpnBypassPackages = setOf("com.whatsapp"),
            version = 10L
        )
        val cloud = lockedCurfewCurrent.copy(
            customCurfewWhitelistPackages = setOf("com.athanotify"),
            curfewVpnBypassPackages = emptySet(),
            version = 10L
        )

        val lockedCurfewResult = PolicyCloudSyncBridge.preserveWardSelfEditableFieldsOnEqualVersion(
            current = lockedCurfewCurrent,
            effectiveCloudPolicy = cloud,
            candidateCloudVersion = 10L,
            isFromGuardian = true
        )
        assertFalse(lockedCurfewResult.preservedCurfewLists)
        assertEquals(setOf("com.athanotify"), lockedCurfewResult.policy.customCurfewWhitelistPackages)
        assertEquals(emptySet<String>(), lockedCurfewResult.policy.curfewVpnBypassPackages)

        val lockedMasterCurrent = lockedCurfewCurrent.copy(
            isSelfControlAllowed = false,
            selfControlCurfewAllowed = true
        )
        val lockedMasterResult = PolicyCloudSyncBridge.preserveWardSelfEditableFieldsOnEqualVersion(
            current = lockedMasterCurrent,
            effectiveCloudPolicy = cloud,
            candidateCloudVersion = 10L,
            isFromGuardian = true
        )
        assertFalse(lockedMasterResult.preservedCurfewLists)
        assertEquals(setOf("com.athanotify"), lockedMasterResult.policy.customCurfewWhitelistPackages)
        assertEquals(emptySet<String>(), lockedMasterResult.policy.curfewVpnBypassPackages)
    }

    @Test
    fun `preserveWardSelfEditableFieldsOnEqualVersion lets guardian remote self-control reset win`() {
        val studyFortress = FortressProfile(id = "study", name = "Study", durationMinutes = 45)
        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            isSelfControlAllowed = true,
            selfControlCurfewAllowed = true,
            selfControlFortressesAllowed = true,
            customCurfewWhitelistPackages = setOf("com.whatsapp", "com.google.android.gm"),
            curfewVpnBypassPackages = setOf("com.whatsapp"),
            fortressesConfig = FortressesConfig.default().upsert(studyFortress),
            remoteSelfControlResetRequestedAt = 1_790_000_000_000L,
            version = 10L
        )
        val cloudReset = current.copy(
            customCurfewWhitelistPackages = emptySet(),
            curfewVpnBypassPackages = emptySet(),
            fortressesConfig = FortressesConfig.default(),
            remoteSelfControlResetRequestedAt = 1_790_000_500_000L,
            version = 10L
        )

        val result = PolicyCloudSyncBridge.preserveWardSelfEditableFieldsOnEqualVersion(
            current = current,
            effectiveCloudPolicy = cloudReset,
            candidateCloudVersion = 10L,
            isFromGuardian = true
        )

        assertFalse(result.preservedCurfewLists)
        assertFalse(result.preservedCustomFortresses)
        assertEquals(emptySet<String>(), result.policy.customCurfewWhitelistPackages)
        assertEquals(emptySet<String>(), result.policy.curfewVpnBypassPackages)
        assertTrue(result.policy.fortressesConfig.getCustomFortresses().isEmpty())
    }

    // =========================================================================
    // 2. AccountabilityRepository.buildPostSupervisionPolicy
    // =========================================================================

    @Test
    fun `buildPostSupervisionPolicy preserves curfew lists and normalized fortresses while clearing supervision`() {
        val customFortress = FortressProfile(
            id = "focus",
            name = "Focus Mode",
            durationMinutes = 90,
            allowedPackages = setOf("com.xodo.pdf.reader")
        )
        val existing = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-99",
            guardianName = "Mentor",
            customCurfewWhitelistPackages = setOf("com.whatsapp", "com.athanotify"),
            curfewVpnBypassPackages = setOf("com.whatsapp"),
            fortressesConfig = FortressesConfig(profiles = listOf(customFortress)),
            breakoutStatus = DevicePolicyBundle.BREAKOUT_STATUS_PENDING,
            breakoutRequestedAt = 1_790_000_000_000L,
            breakoutUnlockAt = 1_790_086_400_000L,
            version = 24L
        )

        val now = 1_790_100_000_000L
        val postPolicy = AccountabilityRepository.buildPostSupervisionPolicy(
            existingPolicy = existing,
            nextVersion = 25L,
            now = now,
            breakoutStatus = DevicePolicyBundle.BREAKOUT_STATUS_COMPLETED
        )

        assertNull(postPolicy.guardianUid)
        assertNull(postPolicy.guardianName)
        assertFalse(postPolicy.isSupervised)
        assertEquals(25L, postPolicy.version)
        assertEquals(now, postPolicy.updatedAt)
        assertEquals(DevicePolicyBundle.BREAKOUT_STATUS_COMPLETED, postPolicy.breakoutStatus)
        assertNull(postPolicy.breakoutRequestedAt)
        assertNull(postPolicy.breakoutUnlockAt)
        assertEquals(setOf("com.whatsapp", "com.athanotify"), postPolicy.customCurfewWhitelistPackages)
        assertEquals(setOf("com.whatsapp"), postPolicy.curfewVpnBypassPackages)
        assertEquals(
            existing.fortressesConfig.normalized().getAllOrdered(),
            postPolicy.fortressesConfig.getAllOrdered()
        )
        assertEquals(1, postPolicy.fortressesConfig.getCustomFortresses().size)
        assertEquals("focus", postPolicy.fortressesConfig.getCustomFortresses().first().id)
    }

    @Test
    fun `buildPostSupervisionPolicy falls back gracefully to default policy when existingPolicy is null`() {
        val now = 1_790_200_000_000L
        val defaultPolicy = DevicePolicyBundle.defaultPolicy()

        val postPolicy = AccountabilityRepository.buildPostSupervisionPolicy(
            existingPolicy = null,
            nextVersion = 5L,
            now = now
        )

        assertNull(postPolicy.guardianUid)
        assertNull(postPolicy.guardianName)
        assertFalse(postPolicy.isSupervised)
        assertNull(postPolicy.breakoutStatus)
        assertEquals(5L, postPolicy.version)
        assertEquals(now, postPolicy.updatedAt)
        assertEquals(defaultPolicy.customCurfewWhitelistPackages, postPolicy.customCurfewWhitelistPackages)
        assertEquals(defaultPolicy.curfewVpnBypassPackages, postPolicy.curfewVpnBypassPackages)
        assertEquals(defaultPolicy.fortressesConfig, postPolicy.fortressesConfig)
    }

    // =========================================================================
    // 3. SelfControlViewModel.applyDiffOntoLatest
    // =========================================================================

    @Test
    fun `applyDiffOntoLatest preserves concurrent curfew and fortress edits when draft only toggles unrelated field`() {
        val baseline = DevicePolicyBundle.defaultPolicy().copy(
            isAdBlockingEnabled = false,
            customCurfewWhitelistPackages = setOf("com.whatsapp"),
            curfewVpnBypassPackages = emptySet(),
            fortressesConfig = FortressesConfig.default()
        )
        val concurrentFortress = FortressProfile(
            id = "quran",
            name = "Quran Session",
            durationMinutes = 45
        )
        val latest = baseline.copy(
            customCurfewWhitelistPackages = setOf("com.whatsapp", "com.athanotify"),
            curfewVpnBypassPackages = setOf("com.whatsapp"),
            fortressesConfig = FortressesConfig.default().upsert(concurrentFortress)
        )
        val draft = baseline.copy(
            isAdBlockingEnabled = true
        )

        val merged = SelfControlViewModel.applyDiffOntoLatest(
            latest = latest,
            baseline = baseline,
            draft = draft
        )

        assertTrue(merged.isAdBlockingEnabled)
        assertEquals(setOf("com.whatsapp", "com.athanotify"), merged.customCurfewWhitelistPackages)
        assertEquals(setOf("com.whatsapp"), merged.curfewVpnBypassPackages)
        assertEquals(latest.fortressesConfig, merged.fortressesConfig)
    }

    @Test
    fun `applyDiffOntoLatest performs 3-way set merge preserving concurrent additions while applying user additions and removals`() {
        val baseline = DevicePolicyBundle.defaultPolicy().copy(
            customCurfewWhitelistPackages = setOf("com.keep.app", "com.user.removed"),
            curfewVpnBypassPackages = setOf("com.keep.app", "com.user.removed")
        )
        val latest = baseline.copy(
            customCurfewWhitelistPackages = setOf("com.keep.app", "com.user.removed", "com.concurrent.added"),
            curfewVpnBypassPackages = setOf("com.keep.app", "com.user.removed", "com.concurrent.added")
        )
        val draft = baseline.copy(
            customCurfewWhitelistPackages = setOf("com.keep.app", "com.user.added"),
            curfewVpnBypassPackages = setOf("com.keep.app", "com.user.added")
        )

        val merged = SelfControlViewModel.applyDiffOntoLatest(
            latest = latest,
            baseline = baseline,
            draft = draft
        )

        val expected = setOf("com.keep.app", "com.concurrent.added", "com.user.added")
        assertEquals(expected, merged.customCurfewWhitelistPackages)
        assertEquals(expected, merged.curfewVpnBypassPackages)
        assertFalse(merged.customCurfewWhitelistPackages.contains("com.user.removed"))
        assertFalse(merged.curfewVpnBypassPackages.contains("com.user.removed"))
    }

    // =========================================================================
    // 4. PolicyCloudSyncBridge.reconcileTransitionRules & migratedExemptPackages
    // =========================================================================

    @Test
    fun `reconcileTransitionRules preserves curfew lists, fortresses, and schedule when cloud keys are absent`() {
        val studyFortress = FortressProfile(
            id = "study",
            name = "Study Fortress",
            durationMinutes = 60,
            allowedPackages = setOf("com.xodo.pdf.reader")
        )
        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = false,
            customCurfewWhitelistPackages = setOf("com.whatsapp", "com.athanotify"),
            curfewVpnBypassPackages = setOf("com.whatsapp"),
            fortressesConfig = FortressesConfig.default().upsert(studyFortress),
            isCurfewEnabled = true,
            curfewStartHour = 23,
            curfewStartMinute = 15,
            curfewEndHour = 5,
            curfewEndMinute = 45,
            isWeekendCurfewEnabled = true,
            weekendCurfewStartHour = 23,
            weekendCurfewStartMinute = 30,
            weekendCurfewEndHour = 7,
            weekendCurfewEndMinute = 0,
            isVpnBlackholeEnabled = true
        )
        val cloud = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            customCurfewWhitelistPackages = emptySet(),
            curfewVpnBypassPackages = emptySet(),
            fortressesConfig = FortressesConfig.default(),
            isCurfewEnabled = false
        )

        val reconciled = PolicyCloudSyncBridge.reconcileTransitionRules(
            current = current,
            cloudPolicy = cloud,
            isTransitionToSupervised = true,
            cloudFieldPresence = PolicyCloudSyncBridge.CloudFieldPresence(
                hasCurfewWhitelist = false,
                hasCurfewVpnBypass = false,
                hasFortressesConfig = false,
                hasCurfewSchedule = false
            )
        )

        assertEquals(setOf("com.whatsapp", "com.athanotify"), reconciled.customCurfewWhitelistPackages)
        assertEquals(setOf("com.whatsapp"), reconciled.curfewVpnBypassPackages)
        assertEquals(1, reconciled.fortressesConfig.getCustomFortresses().size)
        assertEquals("study", reconciled.fortressesConfig.getCustomFortresses().first().id)
        assertTrue(reconciled.isCurfewEnabled)
        assertEquals(23, reconciled.curfewStartHour)
        assertEquals(15, reconciled.curfewStartMinute)
        assertEquals(5, reconciled.curfewEndHour)
        assertEquals(45, reconciled.curfewEndMinute)
        assertTrue(reconciled.isWeekendCurfewEnabled)
        assertEquals(23, reconciled.weekendCurfewStartHour)
        assertEquals(30, reconciled.weekendCurfewStartMinute)
        assertEquals(7, reconciled.weekendCurfewEndHour)
        assertEquals(0, reconciled.weekendCurfewEndMinute)
        assertTrue(reconciled.isVpnBlackholeEnabled)
    }

    @Test
    fun `reconcileTransitionRules respects explicit empty curfew lists when cloud keys are present`() {
        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = false,
            customCurfewWhitelistPackages = setOf("com.whatsapp", "com.athanotify"),
            curfewVpnBypassPackages = setOf("com.whatsapp")
        )
        val cloud = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            customCurfewWhitelistPackages = emptySet(),
            curfewVpnBypassPackages = emptySet()
        )

        val reconciled = PolicyCloudSyncBridge.reconcileTransitionRules(
            current = current,
            cloudPolicy = cloud,
            isTransitionToSupervised = true,
            cloudFieldPresence = PolicyCloudSyncBridge.CloudFieldPresence(
                hasCurfewWhitelist = true,
                hasCurfewVpnBypass = true,
                hasFortressesConfig = true,
                hasCurfewSchedule = true
            )
        )

        assertEquals(emptySet<String>(), reconciled.customCurfewWhitelistPackages)
        assertEquals(emptySet<String>(), reconciled.curfewVpnBypassPackages)
    }

    @Test
    fun `preserveWardSelfEditableFieldsOnEqualVersion preserves migratedExemptPackages when self-control is locked on equal or older version`() {
        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            isSelfControlAllowed = false,
            selfControlCurfewAllowed = false,
            customCurfewWhitelistPackages = setOf(
                "com.athanotify",
                "com.coloros.calculator",
                "com.instagram.android",
                "com.egyptianbanks.instapay"
            ),
            version = 10L
        )
        val cloud = current.copy(
            customCurfewWhitelistPackages = setOf("com.whatsapp"),
            customBlacklistPackages = setOf("com.egyptianbanks.instapay"),
            version = 10L
        )
        val migratedExempt = setOf(
            "com.athanotify",
            "com.coloros.calculator",
            "com.instagram.android",
            "com.egyptianbanks.instapay",
            "com.not.in.current"
        )

        val result = PolicyCloudSyncBridge.preserveWardSelfEditableFieldsOnEqualVersion(
            current = current,
            effectiveCloudPolicy = cloud,
            candidateCloudVersion = 10L,
            isFromGuardian = true,
            migratedExemptPackages = migratedExempt
        )

        assertTrue(result.preservedCurfewLists)
        assertEquals(
            setOf("com.whatsapp", "com.athanotify", "com.coloros.calculator"),
            result.policy.customCurfewWhitelistPackages
        )
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("com.instagram.android"))
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("com.egyptianbanks.instapay"))
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("com.not.in.current"))
    }

    @Test
    fun `preserveWardSelfEditableFieldsOnEqualVersion does not preserve migratedExemptPackages when guardian version is newer`() {
        val current = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            guardianUid = "guardian-1",
            isSelfControlAllowed = false,
            selfControlCurfewAllowed = false,
            customCurfewWhitelistPackages = setOf("com.athanotify", "com.coloros.calculator"),
            version = 10L
        )
        val newerCloud = current.copy(
            customCurfewWhitelistPackages = setOf("com.whatsapp"),
            version = 11L
        )

        val result = PolicyCloudSyncBridge.preserveWardSelfEditableFieldsOnEqualVersion(
            current = current,
            effectiveCloudPolicy = newerCloud,
            candidateCloudVersion = 11L,
            isFromGuardian = true,
            migratedExemptPackages = setOf("com.athanotify", "com.coloros.calculator")
        )

        assertFalse(result.preservedCurfewLists)
        assertEquals(setOf("com.whatsapp"), result.policy.customCurfewWhitelistPackages)
    }

    // =========================================================================
    // 5. PolicyMergeUtils & RemoteAppManagerViewModel.applyAppManagerDiffOntoLatest
    // =========================================================================

    @Test
    fun `PolicyMergeUtils mergeSet and pickIfChanged handle concurrent edits cleanly`() {
        val base = setOf("a", "b")
        val latest = setOf("a", "b", "c")
        val draftUnchanged = setOf("a", "b")
        val draftChanged = setOf("a", "d")

        assertEquals(latest, PolicyMergeUtils.mergeSet(latest, base, draftUnchanged))
        assertEquals(setOf("a", "c", "d"), PolicyMergeUtils.mergeSet(latest, base, draftChanged))

        assertEquals(true, PolicyMergeUtils.pickIfChanged(latestVal = true, baseVal = false, draftVal = false))
        assertEquals(true, PolicyMergeUtils.pickIfChanged(latestVal = false, baseVal = false, draftVal = true))
    }

    @Test
    fun `applyAppManagerDiffOntoLatest preserves concurrent curfew whitelist additions while applying user edits`() {
        val state = RemoteAppManagerUiState(
            initialBlacklist = setOf("com.bad.one"),
            customBlacklist = setOf("com.bad.one"),
            initialCurfewWhitelist = setOf("com.keep.app", "com.user.removed"),
            customCurfewWhitelist = setOf("com.keep.app", "com.user.added"),
            initialCurfewVpnBypass = setOf("com.keep.app"),
            curfewVpnBypass = setOf("com.keep.app"),
            initialLockNewInstalls = false,
            lockNewInstallsUntilApproved = false
        )
        val latest = DevicePolicyBundle.defaultPolicy().copy(
            customBlacklistPackages = setOf("com.bad.one", "com.concurrent.bad"),
            customCurfewWhitelistPackages = setOf("com.keep.app", "com.user.removed", "com.concurrent.added"),
            curfewVpnBypassPackages = setOf("com.keep.app", "com.concurrent.bypass"),
            lockNewInstallsUntilApproved = true
        )

        val merged = RemoteAppManagerViewModel.applyAppManagerDiffOntoLatest(latest, state)

        assertEquals(setOf("com.bad.one", "com.concurrent.bad"), merged.customBlacklistPackages)
        assertEquals(setOf("com.keep.app", "com.concurrent.added", "com.user.added"), merged.customCurfewWhitelistPackages)
        assertEquals(setOf("com.keep.app", "com.concurrent.bypass"), merged.curfewVpnBypassPackages)
        assertTrue(merged.lockNewInstallsUntilApproved)
    }
}
