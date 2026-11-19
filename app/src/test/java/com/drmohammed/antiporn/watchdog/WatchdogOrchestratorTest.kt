package com.drmohammed.antiporn.watchdog

import android.content.Context
import android.content.ContextWrapper
import com.drmohammed.antiporn.HisnDeviceAdminService
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Unit tests for [WatchdogOrchestrator] verifying non-blocking execution,
 * teardown safety invariants, and bound-state fallback avoidance.
 */
class WatchdogOrchestratorTest {

    private val fakeCtx = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.drmohammed.antiporn"
        override fun getSystemService(name: String): Any? = null
    }

    @Before
    fun setUp() {
        ProtectionTransitionGuard.isTeardownInProgress = false
        WatchdogOrchestrator.stopAll(fakeCtx)
    }

    @After
    fun tearDown() {
        ProtectionTransitionGuard.isTeardownInProgress = false
        WatchdogOrchestrator.stopAll(fakeCtx)
    }

    @Test
    fun testTeardownInProgress_ensureActiveBypassesExecutionImmediately() {
        ProtectionTransitionGuard.isTeardownInProgress = true

        val elapsedMs = measureTimeMillis {
            WatchdogOrchestrator.ensureActive(fakeCtx)
        }

        // Must return immediately (< 50ms) without any blocking sleep or fallback launch
        assertTrue("ensureActive must be non-blocking during teardown", elapsedMs < 100L)
        assertFalse(ProtectionWatchdogRuntime.isRunning)
    }

    @Test
    fun testNonBlockingExecutionInvariant() {
        // ensureActive MUST return immediately even when fallback check coroutine is spawned
        val elapsedMs = measureTimeMillis {
            WatchdogOrchestrator.ensureActive(fakeCtx)
        }

        assertTrue("ensureActive must return immediately (< 100ms) without blocking the calling thread", elapsedMs < 100L)
    }

    @Test
    fun testStopAll_forcesRuntimeStop() {
        // Simulate running state
        val isRunningField = ProtectionWatchdogRuntime::class.java.getDeclaredField("isRunning")
        isRunningField.isAccessible = true
        isRunningField.set(ProtectionWatchdogRuntime, true)

        assertTrue(ProtectionWatchdogRuntime.isRunning)

        WatchdogOrchestrator.stopAll(fakeCtx)

        assertFalse("stopAll must unconditionally halt watchdog runtime", ProtectionWatchdogRuntime.isRunning)
        assertEquals(ProtectionWatchdogRuntime.WatchdogHost.NONE, ProtectionWatchdogRuntime.activeHost)
    }
}
