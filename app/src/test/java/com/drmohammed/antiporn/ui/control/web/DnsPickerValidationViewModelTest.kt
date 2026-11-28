package com.drmohammed.antiporn.ui.control.web

import com.drmohammed.antiporn.core.network.DnsFilteringValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPickerValidationViewModelTest {

    @Test
    fun `validateCustomHost emits Completed and resets isValidating for Valid result`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = DnsPickerValidationViewModel().apply {
                ioDispatcher = Dispatchers.Unconfined
                coroutineScopeOverride = scope
                validateDnsHost = { DnsFilteringValidator.ValidationResult.Valid }
            }

            vm.validateCustomHost("dns.example.com")

            assertFalse(vm.isValidating.value)
            assertEquals(
                DnsValidationEvent.Completed("dns.example.com", DnsFilteringValidator.ValidationResult.Valid),
                vm.events.first()
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `validateCustomHost emits ProbeException and resets isValidating when probe throws`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = DnsPickerValidationViewModel().apply {
                ioDispatcher = Dispatchers.Unconfined
                coroutineScopeOverride = scope
                validateDnsHost = { throw IllegalStateException("socket failed") }
            }

            vm.validateCustomHost("broken.example.com")

            assertFalse(vm.isValidating.value)
            assertEquals(
                DnsValidationEvent.ProbeException("broken.example.com"),
                vm.events.first()
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `concurrent validateCustomHost is ignored while probe is in flight`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = CompletableDeferred<Unit>()
            var invocationCount = 0
            val vm = DnsPickerValidationViewModel().apply {
                ioDispatcher = Dispatchers.IO
                coroutineScopeOverride = scope
                validateDnsHost = { host ->
                    invocationCount++
                    runBlocking { gate.await() }
                    if (host == "first.example.com") {
                        DnsFilteringValidator.ValidationResult.Valid
                    } else {
                        DnsFilteringValidator.ValidationResult.Unreachable("timeout")
                    }
                }
            }

            vm.validateCustomHost("first.example.com")
            assertTrue(vm.isValidating.value)

            vm.validateCustomHost("second.example.com")
            gate.complete(Unit)

            val event = vm.events.first()
            assertEquals(1, invocationCount)
            assertEquals(
                DnsValidationEvent.Completed("first.example.com", DnsFilteringValidator.ValidationResult.Valid),
                event
            )
            assertFalse(vm.isValidating.value)
        } finally {
            scope.cancel()
        }
    }
}
