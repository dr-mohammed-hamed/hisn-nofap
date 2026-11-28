package com.drmohammed.antiporn.ui.waqf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.waqf.model.WaqfSnapshot
import com.drmohammed.antiporn.waqf.repository.WaqfRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for [WaqfSupportActivity].
 *
 * Exposes the immediately available Stale-While-Revalidate [snapshot] from [WaqfRepository]
 * and triggers a silent background refresh on screen launch so remote R2 updates appear
 * without blocking cached or cold-fallback rendering.
 */
class WaqfSupportViewModel(
    application: Application,
    private val waqfRepository: WaqfRepository = WaqfRepository.getInstance(application)
) : AndroidViewModel(application) {

    val snapshot: StateFlow<WaqfSnapshot> = waqfRepository.snapshot

    init {
        refreshSilently()
    }

    private fun refreshSilently() {
        viewModelScope.launch {
            waqfRepository.refresh(force = true)
        }
    }

    /**
     * Retries fetching the remote Waqf configuration when the user taps Retry in offline fallback mode.
     */
    fun retryRefresh() {
        viewModelScope.launch {
            waqfRepository.refresh(force = true)
        }
    }
}
