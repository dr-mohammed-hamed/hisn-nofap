package com.drmohammed.antiporn.provisioning.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPairingPromptStateTest {

    @Test
    fun testDiscoverPairingAndConnectPorts_promptsOncePerPairingPort() {
        val state = AdbPairingPromptState()

        val connectOnly = state.onConnectPortDiscovered(38111)
        assertFalse(connectOnly.shouldPrompt)
        assertEquals(0, connectOnly.pairingPort)
        assertEquals(38111, connectOnly.connectPort)

        val firstPairing = state.onPairingPortDiscovered(37123)
        assertTrue(firstPairing.shouldPrompt)
        assertEquals(37123, firstPairing.pairingPort)
        assertEquals(38111, firstPairing.connectPort)

        val duplicatePairing = state.onPairingPortDiscovered(37123)
        assertFalse(duplicatePairing.shouldPrompt)
        assertEquals(37123, state.capturedPairingPort)

        val connectUpdate = state.onConnectPortDiscovered(38222)
        assertFalse(connectUpdate.shouldPrompt)
        assertEquals(38222, state.capturedConnectPort)
    }

    @Test
    fun testInvalidPorts_doNotOverwriteStateOrTriggerPrompt() {
        val state = AdbPairingPromptState()
        assertTrue(state.onPairingPortDiscovered(37123).shouldPrompt)
        state.onConnectPortDiscovered(38111)

        val invalidPair = state.onPairingPortDiscovered(0)
        assertFalse(invalidPair.shouldPrompt)
        assertEquals(37123, state.capturedPairingPort)

        val invalidConnect = state.onConnectPortDiscovered(-5)
        assertFalse(invalidConnect.shouldPrompt)
        assertEquals(38111, state.capturedConnectPort)
    }

    @Test
    fun testStickyOutcome_blocksSamePortEvenAfterPairingServiceLostFlicker() {
        val state = AdbPairingPromptState()
        assertTrue(state.onPairingPortDiscovered(37123).shouldPrompt)
        assertTrue(state.tryBeginPromotion())

        state.markStickyOutcome(37123)
        assertFalse(state.isPromoting)
        assertTrue(state.isShowingStickyOutcome)
        assertEquals(37123, state.stickyOutcomePairingPort)

        val lostSnapshot = state.onPairingServiceLost()
        assertEquals(0, lostSnapshot.pairingPort)
        assertEquals(0, state.capturedPairingPort)
        assertEquals(0, state.lastPromptedPairingPort)
        assertFalse("Sticky outcome notification must remain visible when pairing service drops", lostSnapshot.shouldCancelPrompt)
        assertTrue(lostSnapshot.shouldBroadcast)

        val samePortRediscovered = state.onPairingPortDiscovered(37123)
        assertFalse("Same pairing port must not re-prompt over active sticky outcome", samePortRediscovered.shouldPrompt)
        assertTrue(state.isShowingStickyOutcome)
        assertEquals(37123, state.stickyOutcomePairingPort)
    }

    @Test
    fun testDiscoveringNewPairingPort_resetsStickyOutcomeAndTriggersFreshPrompt() {
        val state = AdbPairingPromptState()
        assertTrue(state.onPairingPortDiscovered(37123).shouldPrompt)
        assertTrue(state.tryBeginPromotion())
        state.markStickyOutcome(37123)

        val lostSnapshot = state.onPairingServiceLost()
        assertFalse(lostSnapshot.shouldCancelPrompt)

        val newPortPrompt = state.onPairingPortDiscovered(41892)
        assertTrue("New pairing dialog port must clear sticky outcome and trigger fresh prompt", newPortPrompt.shouldPrompt)
        assertFalse(state.isShowingStickyOutcome)
        assertNull(state.stickyOutcomePairingPort)
        assertEquals(41892, state.capturedPairingPort)
    }

    @Test
    fun testDiscoveringNewPairingPortWithoutServiceLost_alsoResetsStickyOutcome() {
        val state = AdbPairingPromptState()
        assertTrue(state.onPairingPortDiscovered(37123).shouldPrompt)
        assertTrue(state.tryBeginPromotion())
        state.markStickyOutcome(37123)

        val directNewPort = state.onPairingPortDiscovered(42001)
        assertTrue(directNewPort.shouldPrompt)
        assertFalse(state.isShowingStickyOutcome)
        assertNull(state.stickyOutcomePairingPort)
    }

    @Test
    fun testClearStickyOutcome_resetsWhenIdleAndIgnoredWhilePromoting() {
        val state = AdbPairingPromptState()
        state.onPairingPortDiscovered(37123)
        state.onConnectPortDiscovered(38111)
        assertTrue(state.tryBeginPromotion())
        state.markStickyOutcome(37123)

        val blockedByExternal = state.clearStickyOutcome(isExternalPromotionActive = true)
        assertFalse(blockedByExternal.cleared)
        assertTrue(state.isShowingStickyOutcome)

        val clearedWhenIdle = state.clearStickyOutcome(isExternalPromotionActive = false)
        assertTrue(clearedWhenIdle.cleared)
        assertTrue(clearedWhenIdle.shouldRefreshPrompt)
        assertEquals(37123, clearedWhenIdle.pairingPort)
        assertEquals(38111, clearedWhenIdle.connectPort)
        assertFalse(state.isShowingStickyOutcome)
        assertNull(state.stickyOutcomePairingPort)

        assertTrue(state.tryBeginPromotion())
        val ignoredWhilePromoting = state.clearStickyOutcome()
        assertFalse(ignoredWhilePromoting.cleared)
        assertFalse(ignoredWhilePromoting.shouldRefreshPrompt)
        assertTrue(state.isPromoting)
    }

    @Test
    fun testOnPairingServiceLost_cancelsPromptOnlyWhenNotStickyAndNotPromoting() {
        val state = AdbPairingPromptState()
        assertTrue(state.onPairingPortDiscovered(37123).shouldPrompt)

        val normalLost = state.onPairingServiceLost()
        assertTrue(normalLost.shouldCancelPrompt)
        assertTrue(normalLost.shouldBroadcast)
        assertEquals(0, state.capturedPairingPort)

        assertTrue(state.onPairingPortDiscovered(37124).shouldPrompt)
        assertTrue(state.tryBeginPromotion())
        val lostWhilePromoting = state.onPairingServiceLost()
        assertFalse(lostWhilePromoting.shouldCancelPrompt)
        assertFalse(lostWhilePromoting.shouldBroadcast)
        assertEquals(37124, state.capturedPairingPort)
    }

    @Test
    fun testOnConnectServiceLost_resetsWhenIdleAndPreservesWhilePromoting() {
        val state = AdbPairingPromptState()
        state.onPairingPortDiscovered(37123)
        state.onConnectPortDiscovered(38111)

        val lostWhenIdle = state.onConnectServiceLost()
        assertEquals(0, lostWhenIdle.connectPort)
        assertEquals(37123, lostWhenIdle.pairingPort)
        assertEquals(0, state.capturedConnectPort)
        assertTrue(lostWhenIdle.shouldBroadcast)

        state.onConnectPortDiscovered(38222)
        assertTrue(state.tryBeginPromotion())
        val lostWhilePromoting = state.onConnectServiceLost()
        assertEquals(38222, lostWhilePromoting.connectPort)
        assertEquals(38222, state.capturedConnectPort)
        assertFalse(lostWhilePromoting.shouldBroadcast)
    }
}
