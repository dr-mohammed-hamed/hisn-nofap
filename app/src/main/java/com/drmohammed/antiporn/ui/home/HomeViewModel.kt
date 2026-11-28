package com.drmohammed.antiporn.ui.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewForegroundWatcher
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.fortress.engine.EffectiveRestrictionResolver
import com.drmohammed.antiporn.fortress.engine.FortressSessionController
import com.drmohammed.antiporn.fortress.model.FortressProfile
import com.drmohammed.antiporn.ui.ProtectionUiState
import com.drmohammed.antiporn.ui.shields.ActiveShieldsEvaluator
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * ViewModel for HomeFragment hero shield dashboard and Fortresses orchestration.
 * Emits immutable [HomeUiState] flows and observes system policy updates.
 */
class HomeViewModel(
    application: Application,
    private val policyRepository: PolicyRepository = PolicyRepository.getInstance(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var napTickerJob: Job? = null

    private val refreshLock = Any()
    private var refreshJob: Job? = null
    private var hasNonEnforcingRefresh = false
    private var lastNonEnforcingRefreshMs = 0L
    private var lastRefreshWasEnforcing = false
    private var isEnforcementOperationActive = false
    private val pendingToggleCallbacks = mutableListOf<(Boolean, String?) -> Unit>()
    private val refreshGeneration = AtomicLong(0L)
    private val fortressMutationMutex = Mutex()

    init {
        observePolicy()
        observeActiveFortressSession()
        tickNap()
        refreshProtectionState(isEnforcing = false)
    }

    private fun observePolicy() {
        viewModelScope.launch {
            var lastObservedPolicy: DevicePolicyBundle? = null
            policyRepository.currentPolicyFlow.collect { policy ->
                val isPolicyChanged = lastObservedPolicy != null && lastObservedPolicy != policy
                lastObservedPolicy = policy
                _uiState.update { it.copy(fortressesConfig = policy.fortressesConfig) }
                tickNap()
                if (isPolicyChanged) {
                    refreshProtectionStateInternal(isEnforcing = false, force = true)
                } else {
                    refreshProtectionState(isEnforcing = false)
                }
            }
        }
    }

    private fun observeActiveFortressSession() {
        viewModelScope.launch {
            FortressSessionController.activeSessionFlow.collect {
                tickNap()
            }
        }
    }

    private fun startNapTicker() {
        napTickerJob?.cancel()
        napTickerJob = viewModelScope.launch {
            while (isActive) {
                tickNap()
                val remaining = FortressSessionController.remainingMs(getApplication())
                if (remaining <= 0L) {
                    stopNapTicker()
                    break
                }
                delay(1000L)
            }
        }
    }

    private fun stopNapTicker() {
        napTickerJob?.cancel()
        napTickerJob = null
    }

    /**
     * Ticks fortress countdown and updates night curfew / usage status.
     * Automatically engages or terminates the 1-second countdown job based on fortress activity.
     */
    fun tickNap() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val policy = policyRepository.getCurrentPolicy()
            val snapshot = EffectiveRestrictionResolver.getActiveModesSnapshot(
                context = app,
                policy = policy
            )
            val activeSession = snapshot.activeFortress
            val remaining = activeSession?.remainingMs ?: 0L
            val isActive = activeSession != null && remaining > 0L
            val isCurfew = isEnforcedCurfewActive(app)
            val hasUsage = CurfewForegroundWatcher.hasUsageStatsPermission(app)

            _uiState.update { current ->
                current.copy(
                    fortressesConfig = policy.fortressesConfig,
                    activeFortress = activeSession,
                    napRemainingMs = remaining,
                    isNapActive = isActive,
                    isCurfewActive = isCurfew,
                    isCurfewWindowNow = snapshot.isNightCurfewWindowActiveNow,
                    hasUsageAccess = hasUsage
                )
            }

            withContext(Dispatchers.Main) {
                if (isActive && napTickerJob == null) {
                    startNapTicker()
                } else if (!isActive && napTickerJob != null) {
                    stopNapTicker()
                }
            }
        }
    }

    /**
     * Evaluates active shields and updates [ProtectionUiState].
     * Coalesces redundant back-to-back non-enforcing refreshes within [REFRESH_COALESCE_WINDOW_MS]
     * while always honoring enforcing refreshes and state-changing updates.
     */
    fun refreshProtectionState(isEnforcing: Boolean = false, force: Boolean = false) {
        refreshProtectionStateInternal(isEnforcing = isEnforcing, force = force)
    }

    private fun refreshProtectionStateInternal(isEnforcing: Boolean, force: Boolean) {
        val app = getApplication<Application>()
        val nowMs = System.nanoTime() / 1_000_000L
        val requestGen: Long
        val effectiveEnforcing: Boolean
        synchronized(refreshLock) {
            val elapsedSinceLast = nowMs - lastNonEnforcingRefreshMs
            if (shouldSkipCoalescedRefresh(
                    isEnforcing = isEnforcing,
                    force = force,
                    isEnforcementOperationActive = isEnforcementOperationActive,
                    lastRefreshWasEnforcing = lastRefreshWasEnforcing,
                    hasPreviousNonEnforcing = hasNonEnforcingRefresh,
                    elapsedSinceLastMs = elapsedSinceLast
                )
            ) {
                return
            }

            effectiveEnforcing = resolveEffectiveEnforcing(isEnforcing, isEnforcementOperationActive)
            if (!effectiveEnforcing) {
                hasNonEnforcingRefresh = true
                lastNonEnforcingRefreshMs = nowMs
            }
            lastRefreshWasEnforcing = effectiveEnforcing

            refreshJob?.cancel()
            requestGen = refreshGeneration.incrementAndGet()
            refreshJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val isDo = ProtectionEngine.isDeviceOwner(app)
                    val shields = ProtectionEngine.evaluateSystemStatus(app)

                    val currentDnsHost = if (isDo) com.drmohammed.antiporn.telemetry.SafeDpmGateway.getGlobalPrivateDnsHost(app).getOrNull() else null

                    // Reuse precomputed shield statuses to avoid redundant ProtectionEngine evaluation
                    val activeShields = ActiveShieldsEvaluator.evaluateActiveShields(
                        context = app,
                        precomputedStatuses = shields
                    )
                    Timber.tag(TAG).d("Evaluated %d active shields (isDo=%b)", activeShields.size, isDo)

                    if (refreshGeneration.get() != requestGen) {
                        return@launch
                    }

                    val state = ProtectionUiState.Success(
                        isDeviceOwner = isDo,
                        shields = shields,
                        currentDnsHost = currentDnsHost,
                        isEnforcing = effectiveEnforcing
                    )

                    _uiState.update { current ->
                        if (refreshGeneration.get() != requestGen) {
                            current
                        } else {
                            current.copy(
                                protectionState = state,
                                isApplying = effectiveEnforcing
                            )
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed refreshing protection state")
                    if (refreshGeneration.get() == requestGen) {
                        val enforcingActive = synchronized(refreshLock) { isEnforcementOperationActive }
                        _uiState.update { current ->
                            current.copy(
                                protectionState = ProtectionUiState.Error(
                                    e.localizedMessage ?: app.getString(R.string.error_evaluating_protection)
                                ),
                                isApplying = enforcingActive
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Toggles/enforces system-wide protection shields.
     */
    fun toggleProtection(onResult: ((Boolean, String?) -> Unit)? = null) {
        synchronized(refreshLock) {
            if (_uiState.value.isApplying || isEnforcementOperationActive) {
                Timber.tag(TAG).d("toggleProtection: protection enforcement already in progress, attaching callback to active run.")
                onResult?.let { pendingToggleCallbacks.add(it) }
                return
            }
            isEnforcementOperationActive = true
            onResult?.let { pendingToggleCallbacks.add(it) }
        }
        val app = getApplication<Application>()
        _uiState.update { it.copy(isApplying = true) }
        refreshProtectionState(isEnforcing = true)

        ProtectionEngine.applyAllAsync(app) { results ->
            val allSuccess = results.all { it.success }
            val errorMsg = results.firstOrNull { !it.success }?.message
            val callbacks = synchronized(refreshLock) {
                isEnforcementOperationActive = false
                pendingToggleCallbacks.toList().also { pendingToggleCallbacks.clear() }
            }
            _uiState.update { it.copy(isApplying = false) }
            refreshProtectionStateInternal(isEnforcing = false, force = true)
            callbacks.forEach { it.invoke(allSuccess, errorMsg) }
        }
    }

    /**
     * Removes a single user-selected secondary/unaffiliated profile via Device Owner
     * [android.app.admin.DevicePolicyManager.removeUser] and polls Android until that profile is removed
     * before re-evaluating whether all secondary profiles are gone.
     */
    fun removeSecondaryProfile(
        profile: SecondaryProfileDetector.UnaffiliatedProfileInfo,
        onComplete: (removedTarget: Boolean, allCleared: Boolean) -> Unit
    ) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val result = SecondaryProfileDetector.removeSecondaryProfileAndAwait(app, profile)
            if (result.allSecondariesCleared) {
                CurfewVpnDnsBridge.updatePrivateDnsBlockedByUnaffiliatedUsers(app, false)
            }

            withContext(Dispatchers.Main) {
                onComplete(result.removedTarget, result.allSecondariesCleared)
                if (result.allSecondariesCleared) {
                    toggleProtection { _, _ -> }
                } else if (result.removedTarget) {
                    refreshProtectionState(isEnforcing = false, force = true)
                }
            }
        }
    }

    /**
     * Starts a Fortress session (allowed even during Night Curfew) if Device Owner is active
     * and no other Fortress is currently running.
     */
    fun startFortress(profile: FortressProfile, onComplete: ((Boolean) -> Unit)? = null) =
        runFortressMutation(
            precondition = { app -> !FortressSessionController.isFortressActive(app) },
            mutation = { app -> FortressSessionController.startFortress(app, profile) },
            onComplete = onComplete
        )

    /**
     * Extends the currently active Fortress session by its own configured duration.
     */
    fun extendActiveFortress(onComplete: ((Boolean) -> Unit)? = null) =
        runFortressMutation(
            precondition = { app -> FortressSessionController.isFortressActive(app) },
            mutation = { app -> FortressSessionController.extendActiveFortress(app) },
            onComplete = onComplete
        )

    private fun runFortressMutation(
        precondition: (Application) -> Boolean,
        mutation: (Application) -> Long,
        onComplete: ((Boolean) -> Unit)?
    ) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            fortressMutationMutex.withLock {
                if (!ProtectionEngine.isDeviceOwner(app) || !precondition(app)) {
                    withContext(Dispatchers.Main) { onComplete?.invoke(false) }
                    return@launch
                }
                if (mutation(app) <= 0L) {
                    withContext(Dispatchers.Main) { onComplete?.invoke(false) }
                    return@launch
                }
                CurfewScheduler.scheduleFortressEndAlarm(app, FortressSessionController.getEndWall(app))
                CurfewScheduler.scheduleNextAlarms(app)
                CurfewEnforcer.syncBlockState(app)
                tickNap()
                withContext(Dispatchers.Main) { onComplete?.invoke(true) }
            }
        }
    }

    override fun onCleared() {
        synchronized(refreshLock) {
            pendingToggleCallbacks.clear()
        }
        stopNapTicker()
        super.onCleared()
    }

    companion object {
        private const val TAG = "AntiPorn_HomeVM"
        internal const val REFRESH_COALESCE_WINDOW_MS = 1_000L

        internal fun shouldSkipCoalescedRefresh(
            isEnforcing: Boolean,
            force: Boolean,
            isEnforcementOperationActive: Boolean,
            lastRefreshWasEnforcing: Boolean,
            hasPreviousNonEnforcing: Boolean,
            elapsedSinceLastMs: Long,
            coalesceWindowMs: Long = REFRESH_COALESCE_WINDOW_MS
        ): Boolean {
            if (isEnforcing || force) return false
            if (isEnforcementOperationActive) return true
            if (lastRefreshWasEnforcing) return false
            if (!hasPreviousNonEnforcing) return false
            return elapsedSinceLastMs in 0L until coalesceWindowMs
        }

        internal fun resolveEffectiveEnforcing(
            requestedEnforcing: Boolean,
            isEnforcementOperationActive: Boolean
        ): Boolean = requestedEnforcing || isEnforcementOperationActive

        /**
         * Returns true only when Device Owner is active and Night Curfew is effectively enforced,
         * keeping Nap unlocked when curfew is disabled or before Device Owner provisioning.
         */
        internal fun isEnforcedCurfewActive(context: Context): Boolean =
            ProtectionEngine.isDeviceOwner(context) && CurfewEnforcer.isEffectiveCurfewActive(context)
    }
}
