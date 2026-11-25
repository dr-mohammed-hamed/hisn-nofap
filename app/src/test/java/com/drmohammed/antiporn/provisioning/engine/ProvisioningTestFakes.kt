package com.drmohammed.antiporn.provisioning.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import com.drmohammed.antiporn.R

internal class FakeProvisioningContext : ContextWrapper(null) {
    private val prefsByName = mutableMapOf<String, FakeSharedPreferences>()

    private val fakeResources: Resources by lazy {
        @Suppress("DEPRECATION")
        object : Resources(null, null, null) {
            override fun getString(id: Int): String = resolveString(id)
            override fun getString(id: Int, vararg formatArgs: Any): String = resolveString(id, *formatArgs)
        }
    }

    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "com.drmohammed.antiporn"
    override fun getPackageManager(): PackageManager? = null
    override fun getResources(): Resources = fakeResources
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        prefsByName.getOrPut(name ?: "default") { FakeSharedPreferences() }

    private fun resolveString(resId: Int, vararg formatArgs: Any): String {
        val template = when (resId) {
            R.string.provisioning_error_xiaomi_security_settings -> "xiaomi_security_settings"
            R.string.provisioning_error_realme_permission_monitoring -> "realme_permission_monitoring"
            R.string.provisioning_error_already_device_owner -> "already_device_owner"
            R.string.provisioning_error_realme_precondition -> "realme_precondition"
            R.string.provisioning_error_samsung_secure_folder -> "samsung_secure_folder"
            R.string.provisioning_error_post_verify_failed -> "post_verify_failed"
            R.string.provisioning_error_heal_failed -> "heal_failed"
            else -> "res_$resId"
        }
        return if (formatArgs.isEmpty()) template else String.format(template, *formatArgs)
    }
}

internal class FakeSharedPreferences : SharedPreferences {
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
        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearRequested) {
                storage.clear()
                clearRequested = false
            }
            for ((k, v) in changes) {
                if (v == null) storage.remove(k) else storage[k] = v
            }
            changes.clear()
        }
    }
}
