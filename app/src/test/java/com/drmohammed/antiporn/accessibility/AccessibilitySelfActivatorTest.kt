package com.drmohammed.antiporn.accessibility

import android.content.ComponentName
import android.os.SystemClock
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccessibilitySelfActivatorTest {

    private var fakeElapsedMs = 10_000L

    @Before
    fun setUp() {
        fakeElapsedMs = 10_000L
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { fakeElapsedMs }
        AccessibilitySelfActivator.clearIntentionalDisable()
    }

    @After
    fun tearDown() {
        AccessibilitySelfActivator.clearIntentionalDisable()
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { SystemClock.elapsedRealtime() }
    }

    @Test
    fun testIntentionalDisableLifecycleAndNegativeTimestampGuard() {
        assertFalse(AccessibilitySelfActivator.isIntentionalDisableActive())

        AccessibilitySelfActivator.markIntentionalDisable()
        assertTrue(AccessibilitySelfActivator.isIntentionalDisableInProgress)
        assertTrue(AccessibilitySelfActivator.isIntentionalDisableActive())

        // Within 5000ms grace window
        fakeElapsedMs += 4_999L
        assertTrue(AccessibilitySelfActivator.isIntentionalDisableActive())

        // Past 5000ms grace window -> expires and clears
        fakeElapsedMs += 2L
        assertFalse(AccessibilitySelfActivator.isIntentionalDisableActive())
        assertFalse(AccessibilitySelfActivator.isIntentionalDisableInProgress)

        // Corrupt/negative markedAt timestamp must be treated as inactive and cleared
        AccessibilitySelfActivator.markIntentionalDisable()
        val markedAtField = AccessibilitySelfActivator::class.java.getDeclaredField("intentionalDisableMarkedAtMs")
        markedAtField.isAccessible = true
        markedAtField.setLong(AccessibilitySelfActivator, -1L)

        assertFalse(AccessibilitySelfActivator.isIntentionalDisableActive())
        assertFalse(AccessibilitySelfActivator.isIntentionalDisableInProgress)
    }

    @Test
    fun testTemporarilyCloakedFlagTransitions() {
        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getPackageName(): String = "com.drmohammed.antiporn"
        }

        // Test cloaking sets the flag to true
        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        // enableSilently without forceUncloak should be skipped while cloaked
        val resultWhileCloaked = AccessibilitySelfActivator.enableSilently(dummyContext, forceUncloak = false)
        assertFalse(resultWhileCloaked)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        // enableSilently with forceUncloak resets the flag
        AccessibilitySelfActivator.enableSilently(dummyContext, forceUncloak = true)
        assertFalse(AccessibilitySelfActivator.isTemporarilyCloaked)
    }

    @Test
    fun testColonSeparatedServiceParsingLogic() {
        val longComponent = "com.drmohammed.antiporn/com.drmohammed.antiporn.accessibility.HisnAccessibilityService"
        val shortComponent = "com.drmohammed.antiporn/.accessibility.HisnAccessibilityService"
        val otherService = "com.google.android.marvin.talkback/.TalkBackService"

        val rawServicesWithShort = "$otherService:$shortComponent"
        val tokensWithShort = rawServicesWithShort.split(":").map { it.trim() }

        assertTrue(tokensWithShort.contains(shortComponent))
        assertFalse(tokensWithShort.contains(longComponent))

        // Ensure filtering removes our service without removing talkback
        val filtered = tokensWithShort.filter { it != longComponent && it != shortComponent }
        assertEquals(listOf(otherService), filtered)

        // Ensure adding appends cleanly
        val updated = (filtered + longComponent).joinToString(":")
        assertEquals("$otherService:$longComponent", updated)
    }

    @Test
    fun testComponentMatchesCanonicalClassName() {
        assertEquals(
            "com.drmohammed.antiporn.accessibility.HisnAccessibilityService",
            HisnAccessibilityService::class.java.name
        )
    }
}
