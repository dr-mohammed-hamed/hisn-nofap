package com.drmohammed.antiporn.provisioning.ui

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.telemetry.DpmErrorType
import com.drmohammed.antiporn.telemetry.DpmExecutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAppsManagerViewModelTest {

    private class FakeApplication : Application() {
        override fun getPackageName(): String = "com.drmohammed.antiporn"
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }

    private fun fakeAppInfo(pkg: String, label: String, isSystem: Boolean): ApplicationInfo {
        return ApplicationInfo().apply {
            packageName = pkg
            nonLocalizedLabel = label
            flags = if (isSystem) ApplicationInfo.FLAG_SYSTEM else 0
        }
    }

    private fun createViewModel(
        installedApps: List<ApplicationInfo> = emptyList(),
        setAction: suspend (Context, String, Boolean) -> DpmExecutionResult<Boolean> =
            { _, _, _ -> DpmExecutionResult.Success(true, 1L, "setApplicationHidden") },
        isHiddenAction: suspend (Context, String) -> DpmExecutionResult<Boolean> =
            { _, _ -> DpmExecutionResult.Success(false, 1L, "isApplicationHidden") }
    ): SystemAppsManagerViewModel {
        val app = FakeApplication()
        return SystemAppsManagerViewModel(app).apply {
            ioDispatcher = Dispatchers.Unconfined
            isDeviceOwnerCheck = { false }
            queryInstalledAppsAction = { installedApps }
            loadAppLabelAction = { _, info, pkg -> info.nonLocalizedLabel?.toString() ?: pkg }
            setApplicationHiddenAction = setAction
            isApplicationHiddenAction = isHiddenAction
        }
    }

    @Test
    fun `loadInstalledApplicationsIfNeeded loads once and computes counts and filters`() {
        var queryCount = 0
        val apps = listOf(
            fakeAppInfo("com.example.user", "User App", isSystem = false),
            fakeAppInfo("com.android.sys", "System Tool", isSystem = true),
            fakeAppInfo("com.android.vending", "Play Store", isSystem = true)
        )
        val vm = SystemAppsManagerViewModel(FakeApplication()).apply {
            ioDispatcher = Dispatchers.Unconfined
            isDeviceOwnerCheck = { false }
            queryInstalledAppsAction = {
                queryCount++
                apps
            }
            loadAppLabelAction = { _, info, pkg -> info.nonLocalizedLabel?.toString() ?: pkg }
        }

        vm.loadInstalledApplicationsIfNeeded()
        vm.loadInstalledApplicationsIfNeeded()

        assertEquals(1, queryCount)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(3, vm.uiState.value.totalCount)
        assertEquals(2, vm.uiState.value.systemCount)
        assertEquals(0, vm.uiState.value.frozenCount)

        vm.setFilterMode(SystemAppsFilterMode.SYSTEM)
        assertEquals(2, vm.uiState.value.filteredApps.size)

        vm.setSearchQuery("  vending ")
        assertEquals(1, vm.uiState.value.filteredApps.size)
        assertEquals("com.android.vending", vm.uiState.value.filteredApps.first().packageName)

        vm.setFilterMode(SystemAppsFilterMode.FROZEN)
        assertTrue(vm.uiState.value.filteredApps.isEmpty())
    }

    @Test
    fun `executeFreezeChange unfreeze emits FreezeFailed when verification fails`() = runBlocking {
        val vm = createViewModel(
            setAction = { _, _, _ -> DpmExecutionResult.Success(true, 1L, "setApplicationHidden") },
            isHiddenAction = { _, _ ->
                DpmExecutionResult.Failure(
                    DpmErrorType.SECURITY_EXCEPTION,
                    "DPM error",
                    1L,
                    "isApplicationHidden"
                )
            }
        )
        val item = SystemAppItem(
            packageName = "com.example.frozen",
            label = "Frozen App",
            isSystem = false,
            isHidden = true,
            isSelfApp = false,
            isCritical = false
        )

        vm.executeFreezeChange(item, targetHidden = false)

        val event = vm.events.first()
        assertEquals(
            SystemAppsManagerEvent.FreezeFailed("com.example.frozen", R.string.toast_freeze_protected_error),
            event
        )
        assertFalse(vm.uiState.value.inFlightPackages.contains("com.example.frozen"))
    }

    @Test
    fun `executeFreezeChange emits FreezeFailed when verification returns opposite hidden state`() = runBlocking {
        val vm = createViewModel(
            setAction = { _, _, _ -> DpmExecutionResult.Success(true, 1L, "setApplicationHidden") },
            isHiddenAction = { _, _ -> DpmExecutionResult.Success(false, 1L, "isApplicationHidden") }
        )
        val item = SystemAppItem(
            packageName = "com.example.stubborn",
            label = "Stubborn",
            isSystem = true,
            isHidden = false,
            isSelfApp = false,
            isCritical = false
        )

        vm.executeFreezeChange(item, targetHidden = true)

        assertEquals(
            SystemAppsManagerEvent.FreezeFailed("com.example.stubborn", R.string.toast_freeze_protected_error),
            vm.events.first()
        )
        assertFalse(vm.uiState.value.inFlightPackages.contains("com.example.stubborn"))
    }

    @Test
    fun `executeFreezeChange unfreeze emits FreezeFailed when setApplicationHidden fails`() = runBlocking {
        val vm = createViewModel(
            setAction = { _, _, _ ->
                DpmExecutionResult.Failure(
                    DpmErrorType.NOT_DEVICE_OWNER,
                    "Not DO",
                    1L,
                    "setApplicationHidden"
                )
            },
            isHiddenAction = { _, _ -> DpmExecutionResult.Success(false, 1L, "isApplicationHidden") }
        )
        val item = SystemAppItem(
            packageName = "com.example.frozen",
            label = "Frozen App",
            isSystem = false,
            isHidden = true,
            isSelfApp = false,
            isCritical = false
        )

        vm.executeFreezeChange(item, targetHidden = false)

        val event = vm.events.first()
        assertEquals(
            SystemAppsManagerEvent.FreezeFailed("com.example.frozen", R.string.toast_freeze_protected_error),
            event
        )
        assertFalse(vm.uiState.value.inFlightPackages.contains("com.example.frozen"))
    }

    @Test
    fun `executeFreezeChange emits FreezeFailed and clears inFlight when action throws`() = runBlocking {
        val vm = createViewModel(
            setAction = { _, _, _ -> throw IllegalStateException("binder dead") }
        )
        val item = SystemAppItem(
            packageName = "com.example.crash",
            label = "Crash",
            isSystem = false,
            isHidden = false,
            isSelfApp = false,
            isCritical = false
        )

        vm.executeFreezeChange(item, targetHidden = true)

        assertEquals(
            SystemAppsManagerEvent.FreezeFailed("com.example.crash", R.string.toast_freeze_protected_error),
            vm.events.first()
        )
        assertTrue(vm.uiState.value.inFlightPackages.isEmpty())
    }

    @Test
    fun `executeFreezeChange freeze succeeds, updates masterAppList and frozenCount, and ignores duplicate in-flight`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var setCalls = 0
        val vm = createViewModel(
            installedApps = listOf(fakeAppInfo("com.example.app", "Example", isSystem = false)),
            setAction = { _, _, _ ->
                setCalls++
                gate.await()
                DpmExecutionResult.Success(true, 1L, "setApplicationHidden")
            },
            isHiddenAction = { _, _ -> DpmExecutionResult.Success(true, 1L, "isApplicationHidden") }
        )
        vm.loadInstalledApplicationsIfNeeded()
        gate.complete(Unit)
        val item = vm.uiState.value.masterAppList.first()

        val freezeGate = CompletableDeferred<Unit>()
        vm.ioDispatcher = Dispatchers.IO
        vm.setApplicationHiddenAction = { _, _, _ ->
            setCalls++
            freezeGate.await()
            DpmExecutionResult.Success(true, 1L, "setApplicationHidden")
        }
        setCalls = 0

        vm.executeFreezeChange(item, targetHidden = true)
        assertTrue(vm.uiState.value.inFlightPackages.contains("com.example.app"))
        vm.executeFreezeChange(item, targetHidden = true)
        freezeGate.complete(Unit)

        val event = vm.events.first()
        assertEquals(SystemAppsManagerEvent.FreezeSuccess("Example", true), event)
        assertEquals(1, setCalls)
        assertTrue(vm.uiState.value.inFlightPackages.isEmpty())
        assertTrue(vm.uiState.value.masterAppList.first().isHidden)
        assertEquals(1, vm.uiState.value.frozenCount)
    }
}
