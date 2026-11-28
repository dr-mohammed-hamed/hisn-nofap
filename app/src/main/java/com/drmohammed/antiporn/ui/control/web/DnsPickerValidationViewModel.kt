package com.drmohammed.antiporn.ui.control.web

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.core.network.DnsFilteringValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

sealed class DnsValidationEvent {
    data class Completed(
        val host: String,
        val result: DnsFilteringValidator.ValidationResult
    ) : DnsValidationEvent()

    data class ProbeException(val host: String) : DnsValidationEvent()
}

/**
 * ViewModel running DoT DNS probe validation in [viewModelScope] so in-flight probes
 * survive Fragment/Activity rotation inside [DnsPickerBottomSheet].
 */
class DnsPickerValidationViewModel : ViewModel() {

    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    @VisibleForTesting
    internal var coroutineScopeOverride: CoroutineScope? = null

    private val workScope: CoroutineScope
        get() = coroutineScopeOverride ?: viewModelScope

    @VisibleForTesting
    internal var validateDnsHost: (String) -> DnsFilteringValidator.ValidationResult = { host ->
        DnsFilteringValidator.validateDnsHost(host)
    }

    private val _isValidating = MutableStateFlow(false)
    val isValidating: StateFlow<Boolean> = _isValidating.asStateFlow()

    private val _events = Channel<DnsValidationEvent>(Channel.BUFFERED)
    val events: Flow<DnsValidationEvent> = _events.receiveAsFlow()

    fun validateCustomHost(host: String) {
        if (_isValidating.value) return
        _isValidating.value = true

        workScope.launch {
            try {
                val result = withContext(ioDispatcher + NonCancellable) {
                    validateDnsHost(host)
                }
                _isValidating.value = false
                _events.send(DnsValidationEvent.Completed(host, result))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w(e, "DNS probe validation failed for host %s", host)
                _isValidating.value = false
                _events.send(DnsValidationEvent.ProbeException(host))
            }
        }
    }
}
