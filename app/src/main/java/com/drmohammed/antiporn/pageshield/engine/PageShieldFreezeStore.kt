package com.drmohammed.antiporn.pageshield.engine

import android.content.Context
import android.content.SharedPreferences
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import org.json.JSONObject
import timber.log.Timber

/**
 * مخزن حالة تجميد التطبيقات بواسطة «درع الصفحات».
 * يعتمد على توقيت Epoch الموثوق من [TrustedTimeProvider] لضمان صمود التجميد بعد إعادة تشغيل الهاتف.
 */
object PageShieldFreezeStore {

    private const val TAG = "AntiPorn_PageShieldStore"
    private const val PREFS_NAME = "pageshield_freeze_prefs"
    private const val KEY_PREFIX_FROZEN = "frozen_"

    data class FrozenAppRecord(
        val packageName: String,
        val appName: String,
        val freezeStartEpochMs: Long,
        val freezeEndEpochMs: Long,
        val durationMinutes: Int
    ) {
        fun isExpired(nowEpochMs: Long): Boolean = nowEpochMs >= freezeEndEpochMs

        fun remainingMs(nowEpochMs: Long): Long = maxOf(0L, freezeEndEpochMs - nowEpochMs)
    }

    private fun getPrefs(context: Context): SharedPreferences {
        val appCtx = context.applicationContext ?: context
        return appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun recordFreeze(
        context: Context,
        packageName: String,
        appName: String,
        durationMinutes: Int
    ): FrozenAppRecord {
        val nowEpoch = TrustedTimeProvider.currentTimeMillis(context)
        val endEpoch = nowEpoch + (durationMinutes * 60_000L)
        val record = FrozenAppRecord(
            packageName = packageName,
            appName = appName,
            freezeStartEpochMs = nowEpoch,
            freezeEndEpochMs = endEpoch,
            durationMinutes = durationMinutes
        )

        val json = JSONObject().apply {
            put("packageName", record.packageName)
            put("appName", record.appName)
            put("freezeStartEpochMs", record.freezeStartEpochMs)
            put("freezeEndEpochMs", record.freezeEndEpochMs)
            put("durationMinutes", record.durationMinutes)
        }

        getPrefs(context).edit()
            .putString(KEY_PREFIX_FROZEN + packageName, json.toString())
            .apply()

        Timber.tag(TAG).i("Recorded PageShield freeze for $packageName until epoch=$endEpoch ($durationMinutes min)")
        return record
    }

    @Synchronized
    fun clearFreeze(context: Context, packageName: String) {
        getPrefs(context).edit()
            .remove(KEY_PREFIX_FROZEN + packageName)
            .apply()
        Timber.tag(TAG).i("Cleared PageShield freeze record for $packageName")
    }

    @Synchronized
    fun getFrozenRecord(context: Context, packageName: String): FrozenAppRecord? {
        val jsonStr = getPrefs(context).getString(KEY_PREFIX_FROZEN + packageName, null) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            FrozenAppRecord(
                packageName = json.getString("packageName"),
                appName = json.optString("appName", json.getString("packageName")),
                freezeStartEpochMs = json.getLong("freezeStartEpochMs"),
                freezeEndEpochMs = json.getLong("freezeEndEpochMs"),
                durationMinutes = json.getInt("durationMinutes")
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error parsing frozen record for $packageName")
            null
        }
    }

    @Synchronized
    fun getAllFrozenRecords(context: Context): List<FrozenAppRecord> {
        val prefs = getPrefs(context)
        val list = mutableListOf<FrozenAppRecord>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_PREFIX_FROZEN) && value is String) {
                try {
                    val json = JSONObject(value)
                    list.add(
                        FrozenAppRecord(
                            packageName = json.getString("packageName"),
                            appName = json.optString("appName", json.getString("packageName")),
                            freezeStartEpochMs = json.getLong("freezeStartEpochMs"),
                            freezeEndEpochMs = json.getLong("freezeEndEpochMs"),
                            durationMinutes = json.getInt("durationMinutes")
                        )
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error reading frozen record for key $key")
                }
            }
        }
        return list
    }

    /**
     * التحقق مما إذا كانت الحزمة مجمدة حالياً بواسطة درع الصفحات ولم ينته وقتها بعد.
     */
    @Synchronized
    fun isFrozen(context: Context, packageName: String): Boolean {
        val record = getFrozenRecord(context, packageName) ?: return false
        val nowEpoch = TrustedTimeProvider.currentTimeMillis(context)
        return !record.isExpired(nowEpoch)
    }
}
