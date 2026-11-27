package com.drmohammed.antiporn.ui.control

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.fortress.model.ActiveModesSnapshot

/**
 * Immutable UI State for SelfControlFragment.
 * Encapsulates the active policy bundle, lock/tamper status, active restriction modes, and save state.
 */
data class SelfControlUiState(
    val policy: DevicePolicyBundle = DevicePolicyBundle.defaultPolicy(),
    val initialSnapshot: DevicePolicyBundle? = null,
    val activeModesSnapshot: ActiveModesSnapshot = ActiveModesSnapshot(
        isNightCurfewActive = false,
        isNightCurfewWindowActiveNow = false,
        isNightCurfewOverriddenForWindow = false,
        nightCurfewWindowEndWallMs = 0L,
        activeFortress = null,
        isInstantInternetKillEnabled = false
    ),
    val isLocked: Boolean = false,
    val isSaving: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val isQrLoading: Boolean = false,
    val pendingApprovalsCount: Int = 0,
    val errorMessage: String? = null
)
