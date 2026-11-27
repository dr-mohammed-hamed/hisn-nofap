package com.drmohammed.antiporn.ui.home

import com.drmohammed.antiporn.fortress.model.ActiveFortressSession
import com.drmohammed.antiporn.fortress.model.FortressesConfig
import com.drmohammed.antiporn.ui.ProtectionUiState

/**
 * Immutable UI State for HomeFragment dashboard.
 * Encapsulates hero shield state, Fortresses config/active session timers, and enforcement progress.
 */
data class HomeUiState(
    val protectionState: ProtectionUiState = ProtectionUiState.Loading(),
    val fortressesConfig: FortressesConfig = FortressesConfig.default(),
    val activeFortress: ActiveFortressSession? = null,
    val napRemainingMs: Long = 0L,
    val isNapActive: Boolean = false,
    val isCurfewActive: Boolean = false,
    val isCurfewWindowNow: Boolean = false,
    val isApplying: Boolean = false,
    val hasUsageAccess: Boolean = true
)
