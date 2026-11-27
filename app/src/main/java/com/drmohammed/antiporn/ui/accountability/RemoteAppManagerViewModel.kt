package com.drmohammed.antiporn.ui.accountability

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.ai.service.ScreenAiProjectionService
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.data.model.InstalledAppDto
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.AppInventoryManager
import com.drmohammed.antiporn.data.repository.ApprovalDecisionResult
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Clean MVVM ViewModel for RemoteAppManagerActivity.
 * Handles state management, Firestore synchronization, app search filtering,
 * package suspension toggling, and repair routines.
 */
class RemoteAppManagerViewModel(
    application: Application,
    private val policyRepository: PolicyRepository = PolicyRepository.getInstance(application),
    private val accountabilityRepository: AccountabilityRepository = AccountabilityRepository.getInstance(application),
    private val appInventoryManager: AppInventoryManager = AppInventoryManager.getInstance(application),
    private val approvalRepository: ApprovalRepository = ApprovalRepository.getInstance(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(RemoteAppManagerUiState())
    val uiState: StateFlow<RemoteAppManagerUiState> = _uiState.asStateFlow()

    private val _events = Channel<RemoteAppManagerEvent>(Channel.BUFFERED)
    val events: Flow<RemoteAppManagerEvent> = _events.receiveAsFlow()

    private var inventoryListener: ListenerRegistration? = null
    private var loadJob: Job? = null
    private val mutationMutex = Mutex()
    private var isInitialized = false

    fun initialize(isLocalMode: Boolean, wardId: String, wardName: String) {
        if (isInitialized) return
        isInitialized = true
        _uiState.update {
            it.copy(
                isLocalMode = isLocalMode,
                wardId = wardId,
                wardName = wardName
            )
        }
        loadData()
        if (isLocalMode) {
            observeLocalPolicyChanges()
        }
    }

    private fun observeLocalPolicyChanges() {
        viewModelScope.launch {
            policyRepository.currentPolicyFlow.collect { policy ->
                if (!_uiState.value.isLocalMode) return@collect
                val state = _uiState.value
                if (state.hasUnsavedChanges || state.isSaving) return@collect
                val newCurfewWhitelist = policy.customCurfewWhitelistPackages.toSet()
                val newCurfewVpnBypass = policy.curfewVpnBypassPackages.toSet()
                val newBlacklist = policy.customBlacklistPackages.toSet()
                val newProtected = policy.protectedAppPackages.toSet()
                val newAiScan = policy.customAiScanPackages.toSet()
                val newExcludedAiScan = policy.excludedAiScanPackages.toSet()
                val newLockNewInstalls = policy.lockNewInstallsUntilApproved

                if (state.customCurfewWhitelist != newCurfewWhitelist ||
                    state.curfewVpnBypass != newCurfewVpnBypass ||
                    state.customBlacklist != newBlacklist ||
                    state.protectedApps != newProtected ||
                    state.customAiScanPackages != newAiScan ||
                    state.excludedAiScanPackages != newExcludedAiScan ||
                    state.lockNewInstallsUntilApproved != newLockNewInstalls ||
                    state.isScreenAiEnabledGlobally != policy.isScreenAiEnabledGlobally
                ) {
                    _uiState.update { current ->
                        current.copy(
                            currentPolicy = policy,
                            customBlacklist = newBlacklist,
                            customCurfewWhitelist = newCurfewWhitelist,
                            customAiScanPackages = newAiScan,
                            excludedAiScanPackages = newExcludedAiScan,
                            curfewVpnBypass = newCurfewVpnBypass,
                            protectedApps = newProtected,
                            lockNewInstallsUntilApproved = newLockNewInstalls,
                            isScreenAiEnabledGlobally = policy.isScreenAiEnabledGlobally,
                            initialBlacklist = newBlacklist,
                            initialCurfewWhitelist = newCurfewWhitelist,
                            initialAiScanPackages = newAiScan,
                            initialExcludedAiScanPackages = newExcludedAiScan,
                            initialCurfewVpnBypass = newCurfewVpnBypass,
                            initialProtectedApps = newProtected,
                            initialLockNewInstalls = newLockNewInstalls
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            combine(
                ScreenAiProjectionService.isRunningFlow,
                HisnAccessibilityService.isStandaloneAiActiveFlow
            ) { projectionRunning, standaloneActive ->
                projectionRunning || standaloneActive
            }.collect { isRunning ->
                if (!_uiState.value.isLocalMode) return@collect
                _uiState.update { current ->
                    if (current.isScreenAiServiceRunning == isRunning) current
                    else current.copy(isScreenAiServiceRunning = isRunning)
                }
            }
        }
    }

    fun loadData() {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true) }
        val state = _uiState.value

        loadJob = viewModelScope.launch(Dispatchers.IO) {
            if (state.isLocalMode) {
                loadLocalData()
            } else {
                loadRemoteData(state.wardId)
            }
        }
    }

    private suspend fun loadLocalData() {
        try {
            val policy = policyRepository.getCurrentPolicy()
            val localApps = appInventoryManager.getInstalledApps()
            val sortedList = localApps.sortedWith(
                compareBy({ it.appName.lowercase() }, { it.packageName })
            )

            val query = _uiState.value.searchQuery.trim().lowercase()
            val filtered = filterAppsList(sortedList, query)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    currentPolicy = policy,
                    customBlacklist = policy.customBlacklistPackages.toSet(),
                    customCurfewWhitelist = policy.customCurfewWhitelistPackages.toSet(),
                    customAiScanPackages = policy.customAiScanPackages.toSet(),
                    excludedAiScanPackages = policy.excludedAiScanPackages.toSet(),
                    curfewVpnBypass = policy.curfewVpnBypassPackages.toSet(),
                    protectedApps = policy.protectedAppPackages.toSet(),
                    lockNewInstallsUntilApproved = policy.lockNewInstallsUntilApproved,
                    initialBlacklist = policy.customBlacklistPackages.toSet(),
                    initialCurfewWhitelist = policy.customCurfewWhitelistPackages.toSet(),
                    initialAiScanPackages = policy.customAiScanPackages.toSet(),
                    initialExcludedAiScanPackages = policy.excludedAiScanPackages.toSet(),
                    initialCurfewVpnBypass = policy.curfewVpnBypassPackages.toSet(),
                    initialProtectedApps = policy.protectedAppPackages.toSet(),
                    initialLockNewInstalls = policy.lockNewInstallsUntilApproved,
                    isScreenAiEnabledGlobally = policy.isScreenAiEnabledGlobally,
                    isScreenAiServiceRunning = ScreenAiProjectionService.isRunning || HisnAccessibilityService.isStandaloneAiActive,
                    allApps = sortedList,
                    displayedApps = filtered
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed loading local app manager data", e)
            _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
        }
    }

    private suspend fun loadRemoteData(wardId: String) {
        try {
            val firestore = FirebaseFirestore.getInstance()

            // 1. Fetch current policy bundle
            var policySnapshot: DocumentSnapshot? = null
            try {
                policySnapshot = firestore.collection("policies").document(wardId).get().await()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Network fetch failed for ward policy $wardId, trying cache", e)
                try {
                    policySnapshot = firestore.collection("policies").document(wardId).get(Source.CACHE).await()
                } catch (cacheEx: Exception) {
                    Log.w(TAG, "Cache fetch failed for ward policy $wardId", cacheEx)
                }
            }

            val policyData = policySnapshot?.data
            val policy = if (policySnapshot != null && policySnapshot.exists() && policyData != null) {
                DevicePolicyBundle.fromMap(policyData)
            } else {
                DevicePolicyBundle.defaultPolicy()
            }

            // 2. Fetch inventory
            var inventoryDoc: DocumentSnapshot? = null
            try {
                inventoryDoc = firestore.collection("inventories").document(wardId).get().await()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Network fetch failed for inventory $wardId, trying cache", e)
                try {
                    inventoryDoc = firestore.collection("inventories").document(wardId).get(Source.CACHE).await()
                } catch (cacheEx: Exception) {
                    Log.w(TAG, "Cache fetch failed for inventory $wardId", cacheEx)
                }
            }

            val sortedList = parseAndSortInventory(inventoryDoc, policy)
            val query = _uiState.value.searchQuery.trim().lowercase()
            val filtered = filterAppsList(sortedList, query)

            _uiState.update {
                it.copy(
                    currentPolicy = policy,
                    customBlacklist = policy.customBlacklistPackages.toSet(),
                    customCurfewWhitelist = policy.customCurfewWhitelistPackages.toSet(),
                    customAiScanPackages = policy.customAiScanPackages.toSet(),
                    excludedAiScanPackages = policy.excludedAiScanPackages.toSet(),
                    curfewVpnBypass = policy.curfewVpnBypassPackages.toSet(),
                    protectedApps = policy.protectedAppPackages.toSet(),
                    lockNewInstallsUntilApproved = policy.lockNewInstallsUntilApproved,
                    initialBlacklist = policy.customBlacklistPackages.toSet(),
                    initialCurfewWhitelist = policy.customCurfewWhitelistPackages.toSet(),
                    initialAiScanPackages = policy.customAiScanPackages.toSet(),
                    initialExcludedAiScanPackages = policy.excludedAiScanPackages.toSet(),
                    initialCurfewVpnBypass = policy.curfewVpnBypassPackages.toSet(),
                    initialProtectedApps = policy.protectedAppPackages.toSet(),
                    initialLockNewInstalls = policy.lockNewInstallsUntilApproved,
                    isScreenAiEnabledGlobally = policy.isScreenAiEnabledGlobally,
                    isScreenAiServiceRunning = policy.isScreenAiEnabledGlobally && policy.wardHasAccessibilityService == true,
                    allApps = sortedList,
                    displayedApps = filtered
                )
            }

            attachRealtimeInventoryListener(firestore, wardId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed loading remote app manager data", e)
            _uiState.update { it.copy(errorMessage = e.message) }
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun parseAndSortInventory(
        inventoryDoc: DocumentSnapshot?,
        policy: DevicePolicyBundle
    ): List<InstalledAppDto> {
        @Suppress("UNCHECKED_CAST")
        val rawApps = (inventoryDoc?.get("apps") as? List<*>) ?: emptyList<Any?>()
        @Suppress("UNCHECKED_CAST")
        val parsedApps = rawApps.mapNotNull { raw ->
            (raw as? Map<String, Any?>)?.let { InstalledAppDto.fromMap(it) }
        }.filter { it.packageName.isNotBlank() }.toMutableList()

        val existingPackages = parsedApps.map { it.packageName }.toSet()
        val extraPackages = (policy.customBlacklistPackages + policy.customCurfewWhitelistPackages +
                policy.curfewVpnBypassPackages + policy.protectedAppPackages + policy.customAiScanPackages)
            .filter { !existingPackages.contains(it) }

        for (orphan in extraPackages) {
            parsedApps.add(
                InstalledAppDto(
                    packageName = orphan,
                    appName = orphan,
                    isSuspended = policy.customBlacklistPackages.contains(orphan),
                    isCurfewWhitelisted = policy.customCurfewWhitelistPackages.contains(orphan),
                    isCurfewVpnBypassed = policy.curfewVpnBypassPackages.contains(orphan),
                    isProtected = policy.protectedAppPackages.contains(orphan),
                    isAiScanTarget = policy.customAiScanPackages.contains(orphan) || com.drmohammed.antiporn.ai.core.policy.AiExemptionRegistry.isTarget(orphan)
                )
            )
        }

        return parsedApps.sortedWith(
            compareBy({ it.appName.lowercase() }, { it.packageName })
        )
    }

    private fun attachRealtimeInventoryListener(firestore: FirebaseFirestore, wardId: String) {
        inventoryListener?.remove()
        inventoryListener = firestore.collection("inventories").document(wardId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Real-time inventory listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists() && snapshot.data != null) {
                    val currentPolicy = _uiState.value.currentPolicy
                    val sortedList = parseAndSortInventory(snapshot, currentPolicy)
                    val query = _uiState.value.searchQuery.trim().lowercase()
                    val filtered = filterAppsList(sortedList, query)

                    _uiState.update {
                        it.copy(
                            allApps = sortedList,
                            displayedApps = filtered
                        )
                    }
                }
            }
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                displayedApps = filterAppsList(state.allApps, q)
            )
        }
    }

    private fun filterAppsList(apps: List<InstalledAppDto>, query: String): List<InstalledAppDto> {
        if (query.isEmpty()) return apps
        return apps.filter {
            it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
        }
    }

    fun setFreeze247(packageName: String, isFrozen: Boolean) {
        _uiState.update { state ->
            val blacklist = state.customBlacklist.toMutableSet()
            val curfewWhitelist = state.customCurfewWhitelist.toMutableSet()
            val curfewVpn = state.curfewVpnBypass.toMutableSet()

            if (isFrozen) {
                blacklist.add(packageName)
                curfewWhitelist.remove(packageName)
                curfewVpn.remove(packageName)
            } else {
                blacklist.remove(packageName)
                if (state.initialCurfewWhitelist.contains(packageName)) {
                    curfewWhitelist.add(packageName)
                }
                if (state.initialCurfewVpnBypass.contains(packageName)) {
                    curfewVpn.add(packageName)
                }
            }
            state.copy(
                customBlacklist = blacklist,
                customCurfewWhitelist = curfewWhitelist,
                curfewVpnBypass = curfewVpn
            )
        }
    }

    fun setCurfewWhitelist(packageName: String, isAllowed: Boolean) {
        _uiState.update { state ->
            val curfewWhitelist = state.customCurfewWhitelist.toMutableSet()
            val curfewVpn = state.curfewVpnBypass.toMutableSet()

            if (isAllowed) {
                curfewWhitelist.add(packageName)
            } else {
                curfewWhitelist.remove(packageName)
                curfewVpn.remove(packageName)
            }
            state.copy(
                customCurfewWhitelist = curfewWhitelist,
                curfewVpnBypass = curfewVpn
            )
        }
    }

    fun setCurfewVpnBypass(packageName: String, isBypassed: Boolean) {
        _uiState.update { state ->
            val curfewVpn = state.curfewVpnBypass.toMutableSet()
            if (isBypassed) {
                curfewVpn.add(packageName)
            } else {
                curfewVpn.remove(packageName)
            }
            state.copy(curfewVpnBypass = curfewVpn)
        }
    }

    fun setProtectedApp(packageName: String, isProtected: Boolean) {
        _uiState.update { state ->
            val protected = state.protectedApps.toMutableSet()
            if (isProtected) {
                protected.add(packageName)
            } else {
                protected.remove(packageName)
            }
            state.copy(protectedApps = protected)
        }
    }

    fun setAiScanTarget(packageName: String, isTarget: Boolean) {
        _uiState.update { state ->
            val aiScan = state.customAiScanPackages.toMutableSet()
            val excluded = state.excludedAiScanPackages.toMutableSet()
            if (isTarget) {
                aiScan.add(packageName)
                excluded.remove(packageName)
            } else {
                aiScan.remove(packageName)
                if (com.drmohammed.antiporn.ai.core.policy.AiExemptionRegistry.isTarget(packageName)) {
                    excluded.add(packageName)
                }
            }
            state.copy(
                customAiScanPackages = aiScan,
                excludedAiScanPackages = excluded
            )
        }
    }

    fun setLockNewInstalls(enabled: Boolean) {
        _uiState.update { it.copy(lockNewInstallsUntilApproved = enabled) }
    }

    fun executeQuickUnsuspend(app: InstalledAppDto) {
        val snapshot = _uiState.value
        if (snapshot.isLoading || snapshot.isSaving || snapshot.isRepairing) return
        val pkg = app.packageName
        val displayName = app.appName.ifBlank { pkg }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {
            mutationMutex.withLock {
                val context = getApplication<Application>()
                try {
                    val state = _uiState.value
                    if (state.isLocalMode) {
                        val decision = approvalRepository.unsuspendAndApprove(pkg, decidedBy = "SELF", overrideRejected = true)
                        when (decision) {
                            ApprovalDecisionResult.APPROVED -> {
                                _uiState.update { current ->
                                    current.copy(
                                        allApps = current.allApps.map { if (it.packageName == pkg) it.copy(isSuspended = false) else it },
                                        displayedApps = current.displayedApps.map { if (it.packageName == pkg) it.copy(isSuspended = false) else it }
                                    )
                                }
                                _events.send(
                                    RemoteAppManagerEvent.ShowToastString(
                                        context.getString(R.string.toast_quick_unsuspend_local_success, displayName)
                                    )
                                )
                            }
                            ApprovalDecisionResult.APPROVED_CURFEW_DEFERRED -> {
                                _events.send(
                                    RemoteAppManagerEvent.ShowToastString(
                                        context.getString(R.string.notif_self_qr_approval_deferred_curfew_desc, displayName),
                                        isLong = true
                                    )
                                )
                            }
                            ApprovalDecisionResult.BLOCKED_REJECTED -> {
                                _events.send(
                                    RemoteAppManagerEvent.ShowToastRes(R.string.toast_quick_unsuspend_rejected, isLong = true)
                                )
                            }
                            ApprovalDecisionResult.BLOCKED_BLACKLISTED -> {
                                _events.send(
                                    RemoteAppManagerEvent.ShowToastRes(R.string.toast_quick_unsuspend_blacklisted, isLong = true)
                                )
                            }
                            ApprovalDecisionResult.BLOCKED_SELF_CONTROL_FORBIDDEN -> {
                                _events.send(
                                    RemoteAppManagerEvent.ShowToastRes(R.string.toast_quick_unsuspend_forbidden, isLong = true)
                                )
                            }
                            ApprovalDecisionResult.FAILED_NOT_DEVICE_OWNER -> {
                                _events.send(
                                    RemoteAppManagerEvent.ShowToastString(
                                        context.getString(
                                            R.string.toast_quick_unsuspend_failed,
                                            context.getString(R.string.toast_not_device_owner)
                                        ),
                                        isLong = true
                                    )
                                )
                            }
                            ApprovalDecisionResult.FAILED_DPM_ERROR -> {
                                _events.send(
                                    RemoteAppManagerEvent.ShowToastString(
                                        context.getString(
                                            R.string.toast_quick_unsuspend_failed,
                                            context.getString(R.string.error_unknown)
                                        ),
                                        isLong = true
                                    )
                                )
                            }
                        }
                    } else {
                        val updatedBlacklist = state.customBlacklist - pkg
                        val myUid = accountabilityRepository.getCachedUid()
                        val myName = accountabilityRepository.getMyDisplayName()
                            ?: context.getString(R.string.guardian_default_name)

                        val updatedPolicy = state.currentPolicy.copy(
                            guardianUid = state.currentPolicy.guardianUid?.takeIf { it.isNotBlank() } ?: myUid,
                            guardianName = state.currentPolicy.guardianName?.takeIf { it.isNotBlank() } ?: myName,
                            isSupervised = true,
                            customBlacklistPackages = updatedBlacklist,
                            updatedAt = TrustedTimeProvider.currentTimeMillis(context)
                        )

                        // 1. Push policy FIRST so ward removes pkg from customBlacklistPackages before approval ticket arrives
                        val success = policyRepository.pushPolicyToWard(state.wardId, updatedPolicy)

                        // 2. Resolve Zero-Trust approval on ward cloud ticket only after policy is successfully updated in cloud
                        if (success) {
                            val approvalSuccess = try {
                                approvalRepository.resolveWardApproval(
                                    wardUid = state.wardId,
                                    packageName = pkg,
                                    approve = true,
                                    guardianName = myName
                                )
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.e(TAG, "Quick unsuspend: Error resolving ward approval for $pkg", e)
                                false
                            }
                            Log.i(TAG, "Quick unsuspend: Auto-resolved ward approval for $pkg (success=$approvalSuccess)")

                            _uiState.update { current ->
                                current.copy(
                                    customBlacklist = current.customBlacklist - pkg,
                                    currentPolicy = updatedPolicy,
                                    initialBlacklist = current.initialBlacklist - pkg,
                                    allApps = if (approvalSuccess) {
                                        current.allApps.map { if (it.packageName == pkg) it.copy(isSuspended = false) else it }
                                    } else current.allApps,
                                    displayedApps = if (approvalSuccess) {
                                        current.displayedApps.map { if (it.packageName == pkg) it.copy(isSuspended = false) else it }
                                    } else current.displayedApps
                                )
                            }

                            if (approvalSuccess) {
                                _events.send(
                                    RemoteAppManagerEvent.ShowToastString(
                                        context.getString(R.string.toast_quick_unsuspend_remote_sent, displayName, state.wardName),
                                        isLong = true
                                    )
                                )
                            } else {
                                _events.send(
                                    RemoteAppManagerEvent.ShowToastString(
                                        context.getString(R.string.toast_quick_unsuspend_failed, context.getString(R.string.error_unknown)),
                                        isLong = true
                                    )
                                )
                            }
                        } else {
                            _events.send(
                                RemoteAppManagerEvent.ShowToastString(
                                    context.getString(R.string.toast_quick_unsuspend_failed, context.getString(R.string.error_unknown)),
                                    isLong = true
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Quick unsuspend failed for $pkg", e)
                    _events.send(
                        RemoteAppManagerEvent.ShowToastString(
                            context.getString(R.string.toast_quick_unsuspend_failed, e.localizedMessage ?: context.getString(R.string.error_unknown)),
                            isLong = true
                        )
                    )
                } finally {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun executeRepairStuckApps() {
        val state = _uiState.value
        if (state.isRepairing || state.isSaving || state.isLoading) return
        if (state.hasUnsavedChanges) {
            viewModelScope.launch {
                _events.send(RemoteAppManagerEvent.ShowToastRes(R.string.dialog_unsaved_changes_msg))
            }
            return
        }

        _uiState.update { it.copy(isRepairing = true, isLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val count = CurfewEnforcer.repairStuckSuspendedApps(context)
                if (count > 0) {
                    _events.send(
                        RemoteAppManagerEvent.ShowToastString(
                            context.getString(R.string.toast_repair_stuck_apps_success, count),
                            isLong = true
                        )
                    )
                    loadData()
                } else {
                    _events.send(RemoteAppManagerEvent.ShowToastRes(R.string.toast_no_stuck_apps))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error repairing stuck suspended apps", e)
                _events.send(RemoteAppManagerEvent.ShowToastRes(R.string.toast_no_stuck_apps))
            } finally {
                _uiState.update { it.copy(isRepairing = false, isLoading = false) }
            }
        }
    }

    fun saveChanges() {
        val snapshot = _uiState.value
        if (snapshot.isSaving || snapshot.isLoading || snapshot.isRepairing || !snapshot.hasUnsavedChanges) return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            mutationMutex.withLock {
                val context = getApplication<Application>()
                try {
                    val state = _uiState.value
                    val myUid = if (!state.isLocalMode) accountabilityRepository.getCachedUid() else null
                    val myName = if (!state.isLocalMode) accountabilityRepository.getMyDisplayName() else null
                    val unFrozenPackages = state.initialBlacklist - state.customBlacklist

                    val updatedPolicy: DevicePolicyBundle? = if (state.isLocalMode) {
                        policyRepository.mergeAndSaveSelfPolicyPatch(awaitCloudSync = unFrozenPackages.isNotEmpty()) { current ->
                            current.copy(
                                customBlacklistPackages = state.customBlacklist,
                                customCurfewWhitelistPackages = state.customCurfewWhitelist,
                                customAiScanPackages = state.customAiScanPackages,
                                excludedAiScanPackages = state.excludedAiScanPackages,
                                curfewVpnBypassPackages = state.curfewVpnBypass,
                                protectedAppPackages = state.protectedApps,
                                lockNewInstallsUntilApproved = state.lockNewInstallsUntilApproved
                            )
                        }
                    } else {
                        val candidate = state.currentPolicy.copy(
                            guardianUid = state.currentPolicy.guardianUid?.takeIf { it.isNotBlank() } ?: myUid,
                            guardianName = state.currentPolicy.guardianName?.takeIf { it.isNotBlank() } ?: myName,
                            isSupervised = true,
                            customBlacklistPackages = state.customBlacklist,
                            customCurfewWhitelistPackages = state.customCurfewWhitelist,
                            customAiScanPackages = state.customAiScanPackages,
                            excludedAiScanPackages = state.excludedAiScanPackages,
                            curfewVpnBypassPackages = state.curfewVpnBypass,
                            protectedAppPackages = state.protectedApps,
                            lockNewInstallsUntilApproved = state.lockNewInstallsUntilApproved,
                            version = state.currentPolicy.version + 1,
                            updatedAt = TrustedTimeProvider.currentTimeMillis(context)
                        )
                        if (policyRepository.pushPolicyToWard(state.wardId, candidate)) candidate else null
                    }

                    if (updatedPolicy != null) {
                        // Auto-resolve Zero-Trust approvals for any packages removed from 24/7 blacklist AFTER policy push succeeds
                        var allResolved = true
                        for (pkg in unFrozenPackages) {
                            try {
                                val resolved = if (state.isLocalMode) {
                                    val res = approvalRepository.unsuspendAndApprove(pkg, decidedBy = "SELF", overrideRejected = true)
                                    res == ApprovalDecisionResult.APPROVED || res == ApprovalDecisionResult.APPROVED_CURFEW_DEFERRED
                                } else {
                                    approvalRepository.resolveWardApproval(state.wardId, pkg, approve = true, guardianName = myName ?: "GUARDIAN")
                                }
                                if (!resolved) allResolved = false
                                Log.i(TAG, "saveChanges: Auto-approved un-frozen package '$pkg' (resolved=$resolved)")
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                allResolved = false
                                Log.e(TAG, "saveChanges: Error auto-approving package '$pkg'", e)
                            }
                        }

                        _uiState.update {
                            it.copy(
                                currentPolicy = updatedPolicy,
                                initialBlacklist = updatedPolicy.customBlacklistPackages.toSet(),
                                initialCurfewWhitelist = updatedPolicy.customCurfewWhitelistPackages.toSet(),
                                initialAiScanPackages = updatedPolicy.customAiScanPackages.toSet(),
                                initialExcludedAiScanPackages = updatedPolicy.excludedAiScanPackages.toSet(),
                                initialCurfewVpnBypass = updatedPolicy.curfewVpnBypassPackages.toSet(),
                                initialProtectedApps = updatedPolicy.protectedAppPackages.toSet(),
                                initialLockNewInstalls = updatedPolicy.lockNewInstallsUntilApproved
                            )
                        }
                        if (allResolved) {
                            _events.send(RemoteAppManagerEvent.ShowToastRes(R.string.toast_app_manager_saved))
                            _events.send(RemoteAppManagerEvent.SaveSuccess)
                        } else {
                            _events.send(RemoteAppManagerEvent.ShowToastRes(R.string.toast_app_manager_save_failed, isLong = true))
                        }
                    } else {
                        _events.send(RemoteAppManagerEvent.ShowToastRes(R.string.toast_app_manager_save_failed, isLong = true))
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Error pushing updated policies from RemoteAppManagerViewModel", e)
                    _events.send(RemoteAppManagerEvent.ShowToastRes(R.string.toast_app_manager_save_failed, isLong = true))
                } finally {
                    _uiState.update { it.copy(isSaving = false) }
                }
            }
        }
    }

    override fun onCleared() {
        inventoryListener?.remove()
        inventoryListener = null
        AppIconResolver.clear()
        super.onCleared()
    }

    companion object {
        private const val TAG = "AntiPorn_RemoteAppVM"
    }
}
