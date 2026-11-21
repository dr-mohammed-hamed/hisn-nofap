package com.drmohammed.antiporn.cloud

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudSyncManagerTest {

    private lateinit var fakePrefs: SharedPreferences
    private lateinit var fakeContext: Context
    private val prefsStore = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        CloudSyncManager.resetForTesting()
        prefsStore.clear()
        fakePrefs = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = prefsStore.toMutableMap()
            override fun getString(key: String?, defValue: String?): String? = prefsStore[key] as? String ?: defValue
            @Suppress("UNCHECKED_CAST")
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
                (prefsStore[key] as? Set<String>)?.toMutableSet() ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = prefsStore[key] as? Int ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = prefsStore[key] as? Long ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = prefsStore[key] as? Float ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = prefsStore[key] as? Boolean ?: defValue
            override fun contains(key: String?): Boolean = prefsStore.containsKey(key)
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                private val pending = mutableMapOf<String, Any?>()
                private val removed = mutableSetOf<String>()
                private var cleared = false
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    if (key != null) { removed.remove(key); pending[key] = value }
                    return this
                }
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                    if (key != null) { removed.remove(key); pending[key] = values }
                    return this
                }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                    if (key != null) { removed.remove(key); pending[key] = value }
                    return this
                }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                    if (key != null) { removed.remove(key); pending[key] = value }
                    return this
                }
                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                    if (key != null) { removed.remove(key); pending[key] = value }
                    return this
                }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                    if (key != null) { removed.remove(key); pending[key] = value }
                    return this
                }
                override fun remove(key: String?): SharedPreferences.Editor {
                    if (key != null) { pending.remove(key); removed.add(key) }
                    return this
                }
                override fun clear(): SharedPreferences.Editor {
                    cleared = true; pending.clear(); removed.clear()
                    return this
                }
                override fun commit(): Boolean { apply(); return true }
                override fun apply() {
                    if (cleared) prefsStore.clear()
                    removed.forEach { prefsStore.remove(it) }
                    prefsStore.putAll(pending)
                }
            }
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }

        fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
        }
    }

    @Test
    fun testDefaultState_zeroCloudByDefault() {
        // Fresh install without stored keys
        CloudSyncManager.init(fakeContext)
        assertFalse("Default state must be false (Zero-Cloud by default)", CloudSyncManager.isCloudSyncEnabled(fakeContext))
        assertFalse("Migration check must default to false", CloudSyncManager.isMigrationChecked(fakeContext))
        assertFalse("StateFlow value must be false", CloudSyncManager.isCloudSyncEnabledFlow.value)
    }

    @Test
    fun testSetCloudSyncEnabled_updatesPreferencesAndStateFlow() {
        CloudSyncManager.init(fakeContext)
        CloudSyncManager.setCloudSyncEnabled(fakeContext, true)

        assertTrue("Must be enabled in getter", CloudSyncManager.isCloudSyncEnabled(fakeContext))
        assertTrue("Must be enabled in StateFlow", CloudSyncManager.isCloudSyncEnabledFlow.value)
        assertEquals(true, prefsStore[CloudSyncManager.KEY_IS_CLOUD_SYNC_ENABLED])

        CloudSyncManager.setCloudSyncEnabled(fakeContext, false)
        assertFalse(CloudSyncManager.isCloudSyncEnabled(fakeContext))
        assertFalse(CloudSyncManager.isCloudSyncEnabledFlow.value)
    }

    @Test
    fun testMigrationCheckedFlag_persistsCorrectly() {
        CloudSyncManager.init(fakeContext)
        assertFalse(CloudSyncManager.isMigrationChecked(fakeContext))

        CloudSyncManager.setMigrationChecked(fakeContext, true)
        assertTrue(CloudSyncManager.isMigrationChecked(fakeContext))
        assertEquals(true, prefsStore[CloudSyncManager.KEY_CLOUD_MIGRATION_CHECKED])
    }

    @Test
    fun testTemporaryPairingSession_lifecycleAndTimeout() {
        CloudSyncManager.init(fakeContext)
        val code = "ABC789"
        val now = System.currentTimeMillis()
        val expiresAt = now + 15 * 60 * 1000L // 15 minutes

        // Start temporary pairing session
        CloudSyncManager.startTemporaryPairingSession(fakeContext, code, expiresAt)
        assertTrue("Cloud sync must be enabled during pairing session", CloudSyncManager.isCloudSyncEnabled(fakeContext))

        val active = CloudSyncManager.getActivePairingSession()
        assertNotNull("Active session must be present", active)
        assertEquals(code, active?.code)
        assertEquals(expiresAt, active?.expiresAt)

        // Confirming pairing success locks cloud sync
        CloudSyncManager.confirmPairingSuccess(fakeContext)
        assertTrue("Cloud sync must remain enabled permanently after pairing", CloudSyncManager.isCloudSyncEnabled(fakeContext))
        assertTrue("Migration check must be confirmed", CloudSyncManager.isMigrationChecked(fakeContext))
        assertNull("Active pairing session must be cleared after confirmation", CloudSyncManager.getActivePairingSession())
    }

    @Test
    fun testExpiredPairingSession_returnsNull() {
        CloudSyncManager.init(fakeContext)
        val code = "EXP123"
        val pastExpiresAt = System.currentTimeMillis() - 1000L // Already expired 1 second ago

        CloudSyncManager.startTemporaryPairingSession(fakeContext, code, pastExpiresAt)
        assertNull("Expired session must return null", CloudSyncManager.getActivePairingSession())
    }
}
