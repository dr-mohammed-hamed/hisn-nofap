package com.drmohammed.antiporn.watchdog

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityDriftWatcherTest {

    private val dummyContext: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.drmohammed.antiporn"
    }

    private data class RecordedA11yEvent(
        val action: String,
        val isSuccess: Boolean,
        val details: String?
    )

    private fun invokeEvaluate(
        watcher: AccessibilityDriftWatcher,
        teardown: Boolean = false,
        intentionalDisable: Boolean = false,
        cloaked: Boolean = false,
        required: Boolean = true,
        alreadyEnabled: Boolean = false,
        canWriteSecure: Boolean = true,
        enableResult: Boolean = true,
        onEnable: () -> Unit = {},
        recorded: MutableList<RecordedA11yEvent> = mutableListOf()
    ): Boolean {
        return watcher.evaluateAndHeal(
            appContext = dummyContext,
            isTeardownInProgress = { teardown },
            isIntentionalDisableActive = { intentionalDisable },
            isCloaked = { cloaked },
            isAccessibilityRequired = { required },
            isServiceEnabledInSettings = { alreadyEnabled },
            canWriteSecureSettings = { canWriteSecure },
            enableSilently = {
                onEnable()
                enableResult
            },
            recordA11y = { _, action, ok, details ->
                recorded.add(RecordedA11yEvent(action, ok, details))
            }
        )
    }

    @Test
    fun evaluateAndHeal_respectsAllPolicyAndSafetyGuards() {
        var enableCalls = 0
        val watcher = AccessibilityDriftWatcher(isActiveGeneration = { true }, clockMs = { 10_000L })

        assertFalse(invokeEvaluate(watcher, teardown = true, onEnable = { enableCalls++ }))
        assertFalse(invokeEvaluate(watcher, intentionalDisable = true, onEnable = { enableCalls++ }))
        assertFalse(invokeEvaluate(watcher, cloaked = true, onEnable = { enableCalls++ }))
        assertFalse(invokeEvaluate(watcher, required = false, onEnable = { enableCalls++ }))
        assertFalse(invokeEvaluate(watcher, alreadyEnabled = true, onEnable = { enableCalls++ }))
        assertFalse(invokeEvaluate(watcher, canWriteSecure = false, onEnable = { enableCalls++ }))

        assertEquals(0, enableCalls)
    }

    @Test
    fun evaluateAndHeal_successfulHealInvokesEnableSilentlyAndRecordsSuccess() {
        var enableCalls = 0
        val recorded = mutableListOf<RecordedA11yEvent>()
        val watcher = AccessibilityDriftWatcher(isActiveGeneration = { true }, clockMs = { 10_000L })

        val healed = invokeEvaluate(
            watcher = watcher,
            enableResult = true,
            onEnable = { enableCalls++ },
            recorded = recorded
        )

        assertTrue(healed)
        assertEquals(1, enableCalls)
        assertEquals(1, recorded.size)
        assertEquals("A11Y_DRIFT_FAST_HEAL", recorded[0].action)
        assertTrue(recorded[0].isSuccess)
    }

    @Test
    fun evaluateAndHeal_rateLimitsWithin1500msWindow() {
        var now = 10_000L
        var enableCalls = 0
        val watcher = AccessibilityDriftWatcher(isActiveGeneration = { true }, clockMs = { now })

        assertTrue(invokeEvaluate(watcher, enableResult = true, onEnable = { enableCalls++ }))
        assertEquals(1, enableCalls)

        now += 1_499L
        assertFalse(invokeEvaluate(watcher, enableResult = true, onEnable = { enableCalls++ }))
        assertEquals(1, enableCalls)

        now += 1L
        assertTrue(invokeEvaluate(watcher, enableResult = true, onEnable = { enableCalls++ }))
        assertEquals(2, enableCalls)
    }

    @Test
    fun evaluateAndHeal_enters60sBackoffAfterThreeFailuresWithin30s() {
        var now = 10_000L
        var enableCalls = 0
        val recorded = mutableListOf<RecordedA11yEvent>()
        val watcher = AccessibilityDriftWatcher(isActiveGeneration = { true }, clockMs = { now })

        // Failure 1 at t=10_000
        assertFalse(
            invokeEvaluate(
                watcher = watcher,
                enableResult = false,
                onEnable = { enableCalls++ },
                recorded = recorded
            )
        )
        // Failure 2 at t=11_500
        now += 1_500L
        assertFalse(
            invokeEvaluate(
                watcher = watcher,
                enableResult = false,
                onEnable = { enableCalls++ },
                recorded = recorded
            )
        )
        // Failure 3 at t=13_000 -> triggers backoff
        now += 1_500L
        assertFalse(
            invokeEvaluate(
                watcher = watcher,
                enableResult = false,
                onEnable = { enableCalls++ },
                recorded = recorded
            )
        )

        assertEquals(3, enableCalls)
        assertEquals(
            listOf(
                "A11Y_DRIFT_FAST_HEAL",
                "A11Y_DRIFT_FAST_HEAL",
                "A11Y_DRIFT_FAST_HEAL",
                "A11Y_DRIFT_HEAL_BACKOFF"
            ),
            recorded.map { it.action }
        )
        assertFalse(recorded.last().isSuccess)

        // Suppressed at +59_999ms
        now += 59_999L
        assertFalse(
            invokeEvaluate(
                watcher = watcher,
                enableResult = true,
                onEnable = { enableCalls++ },
                recorded = recorded
            )
        )
        assertEquals(3, enableCalls)

        // Allowed and succeeds at +60_000ms
        now += 1L
        assertTrue(
            invokeEvaluate(
                watcher = watcher,
                enableResult = true,
                onEnable = { enableCalls++ },
                recorded = recorded
            )
        )
        assertEquals(4, enableCalls)
        assertEquals("A11Y_DRIFT_FAST_HEAL", recorded.last().action)
        assertTrue(recorded.last().isSuccess)
    }

    @Test
    fun evaluateAndHeal_prunesExpiredFailuresOutside30sWindow() {
        var now = 10_000L
        var enableCalls = 0
        val recorded = mutableListOf<RecordedA11yEvent>()
        val watcher = AccessibilityDriftWatcher(isActiveGeneration = { true }, clockMs = { now })

        // Failure 1 at t=10_000
        assertFalse(invokeEvaluate(watcher, enableResult = false, onEnable = { enableCalls++ }, recorded = recorded))
        // Failure 2 at t=11_500
        now += 1_500L
        assertFalse(invokeEvaluate(watcher, enableResult = false, onEnable = { enableCalls++ }, recorded = recorded))

        // Failure 3 at +31_000ms after failure 2 (both prior failures expired beyond 30_000ms)
        now += 31_000L
        assertFalse(invokeEvaluate(watcher, enableResult = false, onEnable = { enableCalls++ }, recorded = recorded))

        assertEquals(3, enableCalls)
        assertFalse(recorded.any { it.action == "A11Y_DRIFT_HEAL_BACKOFF" })

        // Next call after rate limit (1500ms) must still execute (not in backoff)
        now += 1_500L
        assertTrue(invokeEvaluate(watcher, enableResult = true, onEnable = { enableCalls++ }, recorded = recorded))
        assertEquals(4, enableCalls)
    }

    @Test
    fun evaluateAndHeal_rejectsStaleGenerationAndResetsRateLimitAfterUnregister() {
        var now = 10_000L
        var enableCalls = 0
        val watcher = AccessibilityDriftWatcher(isActiveGeneration = { true }, clockMs = { now })

        // First heal succeeds at gen 0
        assertTrue(invokeEvaluate(watcher, enableResult = true, onEnable = { enableCalls++ }))
        assertEquals(1, enableCalls)

        // Stale gen value is rejected
        assertFalse(
            watcher.evaluateAndHeal(
                appContext = dummyContext,
                gen = -99L,
                isTeardownInProgress = { false },
                isIntentionalDisableActive = { false },
                isCloaked = { false },
                isAccessibilityRequired = { true },
                isServiceEnabledInSettings = { false },
                canWriteSecureSettings = { true },
                enableSilently = {
                    enableCalls++
                    true
                },
                recordA11y = { _, _, _, _ -> }
            )
        )
        assertEquals(1, enableCalls)

        // Unregister advances internal generation lazily without clearing inside healLock;
        // next evaluateAndHeal at same timestamp (within 1500ms) must reset rate-limit and run.
        watcher.unregister(dummyContext)
        assertTrue(invokeEvaluate(watcher, enableResult = true, onEnable = { enableCalls++ }))
        assertEquals(2, enableCalls)
    }
}
