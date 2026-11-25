package com.drmohammed.antiporn.provisioning

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.drmohammed.antiporn.provisioning.engine.ProvisioningAckStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProvisioningAckStoreTest {

    private lateinit var context: FakeContext

    @Before
    fun setUp() {
        context = FakeContext()
        ProvisioningAckStore.nowMsProvider = null
        ProvisioningAckStore.bootCountProvider = null
    }

    @After
    fun tearDown() {
        ProvisioningAckStore.nowMsProvider = null
        ProvisioningAckStore.bootCountProvider = null
    }

    @Test
    fun testFreshAckIsValidImmediately() {
        val baseTime = 1_000_000L
        ProvisioningAckStore.recordAck(context, nowMs = baseTime)
        assertTrue(
            "Fresh acknowledgment must be valid immediately",
            ProvisioningAckStore.hasValidAck(context, nowMs = baseTime)
        )
    }

    @Test
    fun testValidAckWithin15Minutes() {
        val baseTime = 1_000_000L
        val fourteenMinutesMs = 14 * 60 * 1000L
        ProvisioningAckStore.recordAck(context, nowMs = baseTime)
        assertTrue(
            "Acknowledgment within 14 minutes must be valid",
            ProvisioningAckStore.hasValidAck(context, nowMs = baseTime + fourteenMinutesMs)
        )
    }

    @Test
    fun testAckAtExactBoundaryIsValid() {
        val baseTime = 1_000_000L
        ProvisioningAckStore.recordAck(context, nowMs = baseTime)
        assertTrue(
            "Acknowledgment at exactly 15 minutes boundary must still be valid",
            ProvisioningAckStore.hasValidAck(context, nowMs = baseTime + ProvisioningAckStore.ACK_TTL_MS)
        )
    }

    @Test
    fun testAckExpiredAt15MinutesPlus1MsReturnsFalseAndClears() {
        val baseTime = 1_000_000L
        ProvisioningAckStore.recordAck(context, nowMs = baseTime)
        val expiredTime = baseTime + ProvisioningAckStore.ACK_TTL_MS + 1L

        val valid = ProvisioningAckStore.hasValidAck(context, nowMs = expiredTime)
        assertFalse("Acknowledgment at 15m + 1ms must be invalid", valid)

        // Verify that expired entry was automatically cleared from prefs
        val prefs = context.getSharedPreferences(ProvisioningAckStore.PREFS_NAME, Context.MODE_PRIVATE)
        assertFalse("Key must be cleared from preferences after expiration", prefs.contains(ProvisioningAckStore.KEY_ACK_TIMESTAMP))

        // Subsequent check even at valid time returns false because it was cleared
        assertFalse(
            "Subsequent check must return false because acknowledgment was evicted",
            ProvisioningAckStore.hasValidAck(context, nowMs = baseTime)
        )
    }

    @Test
    fun testNegativeClockJumpReturnsFalseAndClears() {
        val baseTime = 1_000_000L
        ProvisioningAckStore.recordAck(context, nowMs = baseTime)
        val timeInPast = baseTime - 5000L // Clock jumped backward

        val valid = ProvisioningAckStore.hasValidAck(context, nowMs = timeInPast)
        assertFalse("Negative clock jump must be invalid", valid)

        // Verify that entry was cleared
        val prefs = context.getSharedPreferences(ProvisioningAckStore.PREFS_NAME, Context.MODE_PRIVATE)
        assertFalse("Key must be cleared from preferences after negative clock jump", prefs.contains(ProvisioningAckStore.KEY_ACK_TIMESTAMP))
    }

    @Test
    fun testExplicitClearAckInvalidatesAck() {
        val baseTime = 1_000_000L
        ProvisioningAckStore.recordAck(context, nowMs = baseTime)
        assertTrue(ProvisioningAckStore.hasValidAck(context, nowMs = baseTime))

        ProvisioningAckStore.clearAck(context)
        assertFalse("Acknowledgment must be invalid after clearAck", ProvisioningAckStore.hasValidAck(context, nowMs = baseTime))
    }

    @Test
    fun testNoAckReturnsFalse() {
        assertFalse("Initial state without ack must return false", ProvisioningAckStore.hasValidAck(context, nowMs = 1_000_000L))
    }

    @Test
    fun testInjectableNowMsProvider() {
        var currentTime = 10_000L
        ProvisioningAckStore.nowMsProvider = { currentTime }

        ProvisioningAckStore.recordAck(context)
        assertTrue("Ack should be valid at recording time via provider", ProvisioningAckStore.hasValidAck(context))

        // Advance beyond TTL
        currentTime += ProvisioningAckStore.ACK_TTL_MS + 10L
        assertFalse("Ack should be expired after advancing time via provider", ProvisioningAckStore.hasValidAck(context))
    }

    @Test
    fun testRebootInvalidatesAckEvenWhenElapsedRealtimeMatches() {
        var bootCount = 12
        ProvisioningAckStore.bootCountProvider = { bootCount }
        ProvisioningAckStore.recordAck(context, nowMs = 30_000L)
        assertTrue(ProvisioningAckStore.hasValidAck(context, nowMs = 40_000L))

        bootCount = 13
        assertFalse(
            "Ack from previous boot must be invalidated when boot count increments",
            ProvisioningAckStore.hasValidAck(context, nowMs = 60_000L)
        )
    }

    private class FakeContext : ContextWrapper(null) {
        private val prefs = FakeSharedPreferences()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val storage = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = storage.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = storage[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (storage[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = storage[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = storage[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = storage[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = storage[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = storage.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) changes[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) changes[key] = values?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) changes[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) changes[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) changes[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) changes[key] = value }
            override fun remove(key: String?) = apply { if (key != null) changes[key] = null }
            override fun clear() = apply { clearRequested = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearRequested) {
                    storage.clear()
                    clearRequested = false
                }
                for ((k, v) in changes) {
                    if (v == null) {
                        storage.remove(k)
                    } else {
                        storage[k] = v
                    }
                }
                changes.clear()
            }
        }
    }
}
