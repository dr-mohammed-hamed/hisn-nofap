package com.drmohammed.antiporn.ui.accountability

import android.app.Application
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accountability.coordinator.GuardianSupervisionCoordinator
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class GuardianApprovalsHubUiState(
    val allApprovals: List<WardApprovalModel> = emptyList(),
    val selectedTabIndex: Int = 0,
    val hasAutoSelectedTargetTab: Boolean = false,
    val hasScrolledToTarget: Boolean = false,
    val inFlightApprovalKeys: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val targetWardId: String? = null,
    val targetPackage: String? = null,
    val canonicalTargetWardId: String? = null
) {
    fun filteredApprovalsForSelectedTab(): List<WardApprovalModel> {
        val targetStatus = when (selectedTabIndex) {
            0 -> "PENDING"
            1 -> "APPROVED"
            2 -> "REJECTED"
            else -> "PENDING"
        }
        return allApprovals
            .filter { it.status.equals(targetStatus, ignoreCase = true) }
            .sortedByDescending { it.requestedAt }
    }

    fun countForStatus(status: String): Int =
        allApprovals.count { it.status.equals(status, ignoreCase = true) }
}

sealed class ApprovalsHubEvent {
    data class ShowToastRes(val resId: Int) : ApprovalsHubEvent()
}

/**
 * ViewModel for [GuardianApprovalsHubActivity] retaining approval lists, tab selection,
 * target scroll state, in-flight approval operations, and Firestore listeners across rotation.
 */
class GuardianApprovalsHubViewModel(
    application: Application,
    private val accountabilityRepository: AccountabilityRepository,
    private val approvalRepository: ApprovalRepository,
    private val partnerDao: PartnerDao,
    private val appNotificationManager: AppNotificationManager
) : AndroidViewModel(application) {

    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    @VisibleForTesting
    internal var defaultDispatcher: CoroutineDispatcher = Dispatchers.Default

    @VisibleForTesting
    internal var coroutineScopeOverride: CoroutineScope? = null

    @VisibleForTesting
    internal var resolveApprovalAction: suspend (WardApprovalModel, Boolean) -> Boolean = { item, approve ->
        val guardianName = accountabilityRepository.getMyDisplayName()
        approvalRepository.resolveWardApproval(
            wardUid = item.wardId,
            packageName = item.packageName,
            approve = approve,
            guardianName = guardianName
        )
    }

    @VisibleForTesting
    internal var cancelSystemNotificationAction: (Int) -> Unit = { notifId ->
        appNotificationManager.cancelSystemNotification(notifId)
    }

    @VisibleForTesting
    internal var nowProvider: () -> Long = {
        TrustedTimeProvider.currentTimeMillis(getApplication())
    }

    @VisibleForTesting
    internal var attachListenersOverride: ((List<PartnerEntity>) -> Unit)? = null

    @VisibleForTesting
    internal var resolvePartnerUidAction: suspend (String, String?) -> String = { uid, code ->
        accountabilityRepository.resolveActivePartnerUid(uid, code)
    }

    private val workScope: CoroutineScope
        get() = coroutineScopeOverride ?: viewModelScope

    private val _uiState = MutableStateFlow(GuardianApprovalsHubUiState())
    val uiState: StateFlow<GuardianApprovalsHubUiState> = _uiState.asStateFlow()

    private val _events = Channel<ApprovalsHubEvent>(Channel.BUFFERED)
    val events: Flow<ApprovalsHubEvent> = _events.receiveAsFlow()

    private val firestoreListeners = mutableListOf<ListenerRegistration>()
    private val approvalsLock = Any()
    private var isInitialized = false
    private var loadWardsJob: Job? = null

    fun initialize(targetWardId: String?, targetPackage: String?, forceReset: Boolean = false) {
        if (isInitialized && !forceReset) return
        isInitialized = true
        loadWardsJob?.cancel()
        loadWardsJob = null
        detachFirestoreListeners()
        synchronized(approvalsLock) {
            _uiState.update { current ->
                current.copy(
                    allApprovals = if (forceReset) emptyList() else current.allApprovals,
                    selectedTabIndex = if (forceReset) 0 else current.selectedTabIndex,
                    hasAutoSelectedTargetTab = false,
                    hasScrolledToTarget = false,
                    isLoading = true,
                    targetWardId = targetWardId,
                    targetPackage = targetPackage,
                    canonicalTargetWardId = null
                )
            }
        }
        loadWardsAndListenApprovals(targetWardId)
    }

    fun selectTab(index: Int) {
        val clamped = index.coerceIn(0, 2)
        _uiState.update { current ->
            if (current.selectedTabIndex == clamped) current else current.copy(selectedTabIndex = clamped)
        }
    }

    fun markScrolledToTarget() {
        _uiState.update { current ->
            if (current.hasScrolledToTarget) current else current.copy(hasScrolledToTarget = true)
        }
    }

    private fun loadWardsAndListenApprovals(targetWardId: String?) {
        val app = getApplication<Application>()
        loadWardsJob = workScope.launch(ioDispatcher) {
            try {
                val localWards = partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_WARD).toMutableList()
                targetWardId?.let { twId ->
                    if (twId.isNotBlank() && localWards.none { it.id == twId }) {
                        val specificPartner = partnerDao.getPartnerById(twId)
                        if (specificPartner != null) {
                            localWards.add(specificPartner)
                        } else {
                            localWards.add(
                                PartnerEntity(
                                    id = twId,
                                    pairingCode = "",
                                    displayName = runCatching { app.getString(R.string.status_ward_badge) }.getOrDefault(twId),
                                    role = PartnerEntity.ROLE_WARD,
                                    status = PartnerEntity.STATUS_ACTIVE,
                                    createdAt = nowProvider()
                                )
                            )
                        }
                    }
                }

                val resolvedTarget = targetWardId?.let { twId ->
                    if (twId.isNotBlank()) resolvePartnerUidAction(twId, null) else null
                }

                val resolvedWards = localWards.map { ward ->
                    val canonicalUid = resolvePartnerUidAction(ward.id, ward.pairingCode)
                    if (canonicalUid != ward.id) ward.copy(id = canonicalUid) else ward
                }.distinctBy { it.id }

                _uiState.update { it.copy(canonicalTargetWardId = resolvedTarget) }

                if (resolvedWards.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }

                coroutineContext.ensureActive()
                val override = attachListenersOverride
                if (override != null) {
                    override(resolvedWards)
                } else {
                    attachFirestoreListeners(resolvedWards)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Failed loading wards for approvals hub")
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun attachFirestoreListeners(wards: List<PartnerEntity>) {
        detachFirestoreListeners()
        val firestore = FirebaseFirestore.getInstance()
        val app = getApplication<Application>()
        val remainingWards = java.util.Collections.synchronizedSet(wards.map { it.id }.toMutableSet())

        fun markWardResponded(wardId: String) {
            if (remainingWards.remove(wardId) && remainingWards.isEmpty()) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }

        for (ward in wards) {
            val wardId = ward.id
            val wardDisplayName = ward.displayName
            try {
                val listener = firestore.collection("policies").document(wardId)
                    .collection("approvals")
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Timber.tag(TAG).w(error, "Error in approvals listener for ward %s", wardId)
                            markWardResponded(wardId)
                            return@addSnapshotListener
                        }
                        if (snapshot == null) {
                            markWardResponded(wardId)
                            return@addSnapshotListener
                        }

                        workScope.launch(defaultDispatcher) {
                            try {
                                val currentState = _uiState.value
                                val itemsForWard = mutableListOf<WardApprovalModel>()
                                for (doc in snapshot.documents) {
                                    val pkg = doc.getString("packageName") ?: doc.id
                                    val name = doc.getString("appName") ?: pkg
                                    val status = doc.getString("status") ?: "PENDING"
                                    val requestedAt = doc.getLong("requestedAt")
                                        ?: TrustedTimeProvider.currentTimeMillis(app)
                                    val decidedBy = doc.getString("decidedBy")
                                    val decidedAt = doc.getLong("decidedAt")
                                    val isHighlighted = isTargetItem(
                                        pkg = pkg,
                                        wardId = wardId,
                                        targetPackage = currentState.targetPackage,
                                        targetWardId = currentState.targetWardId,
                                        canonicalTargetWardId = currentState.canonicalTargetWardId
                                    )

                                    if (pkg.isNotBlank()) {
                                        itemsForWard.add(
                                            WardApprovalModel(
                                                packageName = pkg,
                                                appName = name,
                                                wardId = wardId,
                                                wardName = wardDisplayName,
                                                status = status,
                                                requestedAt = requestedAt,
                                                decidedBy = decidedBy,
                                                decidedAt = decidedAt,
                                                isHighlighted = isHighlighted
                                            )
                                        )
                                    }
                                }
                                processWardApprovals(wardId, itemsForWard)
                            } finally {
                                markWardResponded(wardId)
                            }
                        }
                    }
                synchronized(firestoreListeners) {
                    firestoreListeners.add(listener)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed attaching approvals listener for %s", wardId)
                markWardResponded(wardId)
            }
        }
    }

    @VisibleForTesting
    internal fun processWardApprovals(wardId: String, itemsForWard: List<WardApprovalModel>) {
        synchronized(approvalsLock) {
            _uiState.update { current ->
                val merged = current.allApprovals.filterNot { it.wardId == wardId } + itemsForWard
                var nextTabIndex = current.selectedTabIndex
                var nextAutoSelected = current.hasAutoSelectedTargetTab

                if (!current.hasAutoSelectedTargetTab && !current.targetPackage.isNullOrBlank()) {
                    val targetItem = merged.firstOrNull {
                        isTargetItem(
                            pkg = it.packageName,
                            wardId = it.wardId,
                            targetPackage = current.targetPackage,
                            targetWardId = current.targetWardId,
                            canonicalTargetWardId = current.canonicalTargetWardId
                        )
                    }
                    if (targetItem != null) {
                        nextAutoSelected = true
                        nextTabIndex = when (targetItem.status.uppercase()) {
                            "PENDING" -> 0
                            "APPROVED" -> 1
                            "REJECTED" -> 2
                            else -> 0
                        }
                    }
                }

                current.copy(
                    allApprovals = merged,
                    selectedTabIndex = nextTabIndex,
                    hasAutoSelectedTargetTab = nextAutoSelected,
                    isLoading = false
                )
            }
        }
    }

    fun resolveApproval(item: WardApprovalModel, approve: Boolean) {
        val itemKey = approvalKey(item.wardId, item.packageName)
        if (_uiState.value.inFlightApprovalKeys.contains(itemKey)) return

        _uiState.update { it.copy(inFlightApprovalKeys = it.inFlightApprovalKeys + itemKey) }

        workScope.launch(ioDispatcher) {
            try {
                val success = try {
                    withContext(NonCancellable) {
                        resolveApprovalAction(item, approve)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).e(e, "Error resolving approval for %s", item.packageName)
                    false
                }

                if (success) {
                    val systemNotifId = GuardianSupervisionCoordinator
                        .computeSystemNotificationId(item.wardId, item.packageName)
                    cancelSystemNotificationAction(systemNotifId)
                    val toastRes = if (approve) {
                        R.string.toast_approval_granted
                    } else {
                        R.string.toast_approval_rejected
                    }
                    _events.send(ApprovalsHubEvent.ShowToastRes(toastRes))
                } else {
                    _events.send(ApprovalsHubEvent.ShowToastRes(R.string.toast_approval_failed))
                }
            } finally {
                _uiState.update { it.copy(inFlightApprovalKeys = it.inFlightApprovalKeys - itemKey) }
            }
        }
    }

    private fun isTargetItem(
        pkg: String,
        wardId: String,
        targetPackage: String?,
        targetWardId: String?,
        canonicalTargetWardId: String?
    ): Boolean {
        if (pkg != targetPackage) return false
        return targetWardId.isNullOrBlank() || wardId == targetWardId || wardId == canonicalTargetWardId
    }

    @VisibleForTesting
    internal fun addListenerForTesting(registration: ListenerRegistration) {
        synchronized(firestoreListeners) {
            firestoreListeners.add(registration)
        }
    }

    private fun detachFirestoreListeners() {
        synchronized(firestoreListeners) {
            for (listener in firestoreListeners) {
                listener.remove()
            }
            firestoreListeners.clear()
        }
    }

    public override fun onCleared() {
        loadWardsJob?.cancel()
        loadWardsJob = null
        detachFirestoreListeners()
        super.onCleared()
    }

    companion object {
        private const val TAG = "ApprovalsHubViewModel"

        fun approvalKey(wardId: String, packageName: String): String = "${wardId}_$packageName"
    }
}
