package com.drmohammed.antiporn.provisioning.ui

import android.app.Application
import com.drmohammed.antiporn.provisioning.engine.BlockingSecondaryUser
import com.drmohammed.antiporn.provisioning.engine.BlockingUserCategory
import com.drmohammed.antiporn.provisioning.engine.PromotionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningWizardViewModelTest {

    private fun createViewModel(
        scope: CoroutineScope,
        pairAction: suspend (Int, Int, String) -> PromotionResult = { _, _, _ -> PromotionResult.Success },
        removeAction: suspend (Int, Set<Int>) -> PromotionResult = { _, _ -> PromotionResult.Success },
        isActiveCheck: () -> Boolean = { false }
    ): ProvisioningWizardViewModel {
        return ProvisioningWizardViewModel(Application()).apply {
            ioDispatcher = Dispatchers.Unconfined
            coroutineScopeOverride = scope
            pairAndPromoteAction = pairAction
            removeSecondaryUsersAndPromoteAction = removeAction
            isPromotionActiveCheck = isActiveCheck
        }
    }

    @Test
    fun `updateDiscoveredPorts preserves existing connect port when new connect port is zero`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(scope)
            vm.updateDiscoveredPorts(pairingPort = 37000, connectPort = 41000)
            vm.updateDiscoveredPorts(pairingPort = 37001, connectPort = 0)

            assertEquals(37001, vm.uiState.value.discoveredPairingPort)
            assertEquals(41000, vm.uiState.value.discoveredConnectPort)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `startPairingAndPromotion emits PromotionSucceeded and clears promoting flag`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(
                scope = scope,
                pairAction = { pairing, connect, code ->
                    assertEquals(37000, pairing)
                    assertEquals(41000, connect)
                    assertEquals("123456", code)
                    PromotionResult.Success
                }
            )
            vm.updateDiscoveredPorts(37000, 41000)

            vm.startPairingAndPromotion("123456")

            assertFalse(vm.uiState.value.isPromoting)
            assertNull(vm.uiState.value.pendingBlockingUsersJson)
            assertEquals(ProvisioningWizardEvent.PromotionSucceeded, vm.events.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `startPairingAndPromotion stores pending users JSON on SecondaryUsersBlocking`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val users = listOf(
                BlockingSecondaryUser(
                    userId = 10,
                    rawName = "Work",
                    flagsHex = "0x30",
                    category = BlockingUserCategory.WORK_PROFILE,
                    installedAppNames = emptyList(),
                    installedPackageNames = emptyList(),
                    isAppsInspectionBlocked = false
                )
            )
            val vm = createViewModel(
                scope = scope,
                pairAction = { _, _, _ -> PromotionResult.SecondaryUsersBlocking(users) }
            )

            vm.startPairingAndPromotion("654321")

            assertFalse(vm.uiState.value.isPromoting)
            assertFalse(vm.uiState.value.pendingBlockingUsersJson.isNullOrBlank())
            assertEquals(ProvisioningWizardEvent.SecondaryUsersBlocking(users), vm.events.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `startPairingAndPromotion ignores duplicate calls while promotion is active`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val vm = createViewModel(
                scope = scope,
                pairAction = { _, _, _ ->
                    calls++
                    gate.await()
                    PromotionResult.Success
                }
            ).apply {
                ioDispatcher = Dispatchers.IO
            }

            vm.startPairingAndPromotion("111111")
            assertTrue(vm.uiState.value.isPromoting)

            vm.startPairingAndPromotion("222222")
            gate.complete(Unit)

            assertEquals(ProvisioningWizardEvent.PromotionSucceeded, vm.events.first())
            assertEquals(1, calls)
            assertFalse(vm.uiState.value.isPromoting)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `startRemoveSecondaryUsersAndPromote emits UnexpectedError when action throws`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = createViewModel(
                scope = scope,
                removeAction = { _, _ -> throw IllegalStateException("adb dropped") }
            )
            vm.updateDiscoveredPorts(37000, 41000)

            vm.startRemoveSecondaryUsersAndPromote(setOf(10))

            assertFalse(vm.uiState.value.isPromoting)
            assertFalse(vm.uiState.value.isCleaningSecondaryUsers)
            assertEquals(
                ProvisioningWizardEvent.UnexpectedError("IllegalStateException", "adb dropped"),
                vm.events.first()
            )
        } finally {
            scope.cancel()
        }
    }
}
