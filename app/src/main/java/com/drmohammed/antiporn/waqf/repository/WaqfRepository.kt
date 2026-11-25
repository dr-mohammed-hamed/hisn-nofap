package com.drmohammed.antiporn.waqf.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.banking.BankingCloakManager
import com.drmohammed.antiporn.waqf.model.WaqfAccentStyle
import com.drmohammed.antiporn.waqf.model.WaqfActionType
import com.drmohammed.antiporn.waqf.model.WaqfChannel
import com.drmohammed.antiporn.waqf.model.WaqfChannelAction
import com.drmohammed.antiporn.waqf.model.WaqfChannelType
import com.drmohammed.antiporn.waqf.model.WaqfConfig
import com.drmohammed.antiporn.waqf.model.WaqfLoadSource
import com.drmohammed.antiporn.waqf.model.WaqfSnapshot
import com.drmohammed.antiporn.waqf.remote.WaqfRemoteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Stale-While-Revalidate (SWR) repository for Waqf Support configuration.
 *
 * Exposes an immediately available [snapshot] initialized synchronously from [SharedPreferences]
 * cache or from [coldOfflineFallbackConfig] (zero-latency offline startup), and refreshes in the
 * background from [WaqfRemoteSource] without ever discarding valid cached channels on failure.
 */
class WaqfRepository @VisibleForTesting internal constructor(
    @Suppress("unused")
    private val appContext: Context,
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    private val remoteFetcher: suspend () -> Result<String> = { WaqfRemoteSource.fetchWaqfConfigJson() },
    private val clockProvider: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val refreshMutex = Mutex()
    private val _snapshot = MutableStateFlow(loadInitialSnapshot())
    val snapshot: StateFlow<WaqfSnapshot> = _snapshot.asStateFlow()

    /**
     * Triggers a non-blocking background refresh only if the cache is absent or older than [maxAgeMs],
     * and no refresh is already in flight.
     */
    fun prefetchIfStaleAsync(maxAgeMs: Long = DEFAULT_PREFETCH_TTL_MS): Job? {
        if (!isCacheStale(maxAgeMs) || refreshMutex.isLocked) {
            return null
        }
        return scope.launch {
            refreshInternal(force = false, maxAgeMs = maxAgeMs)
        }
    }

    /**
     * Refreshes the Waqf configuration from the remote source.
     *
     * When [force] is `false` and a fresh cache exists within [DEFAULT_PREFETCH_TTL_MS],
     * returns the current cached configuration without hitting the network.
     * On failure, preserves the existing cached or cold-fallback configuration.
     */
    suspend fun refresh(force: Boolean = true): Result<WaqfConfig> =
        refreshInternal(force = force, maxAgeMs = DEFAULT_PREFETCH_TTL_MS)

    private suspend fun refreshInternal(force: Boolean, maxAgeMs: Long): Result<WaqfConfig> {
        if (!force && !isCacheStale(maxAgeMs)) {
            return Result.success(_snapshot.value.config)
        }

        return refreshMutex.withLock {
            if (!force && !isCacheStale(maxAgeMs)) {
                return@withLock Result.success(_snapshot.value.config)
            }

            _snapshot.update { it.copy(isRefreshing = true, lastError = null) }

            try {
                val rawJson = remoteFetcher().getOrThrow()
                val parsed = WaqfConfig.fromJson(rawJson)

                persistCache(rawJson, clockProvider())
                _snapshot.value = WaqfSnapshot(
                    config = parsed,
                    source = WaqfLoadSource.Network,
                    isRefreshing = false,
                    lastError = null
                )
                Result.success(parsed)
            } catch (ce: CancellationException) {
                _snapshot.update { it.copy(isRefreshing = false) }
                throw ce
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Waqf config refresh failed; preserving existing snapshot")
                _snapshot.update { current ->
                    current.copy(
                        isRefreshing = false,
                        lastError = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                    )
                }
                Result.failure(t)
            }
        }
    }

    private fun loadInitialSnapshot(): WaqfSnapshot {
        val cachedConfig = readValidCachedConfigOrNull()
        return if (cachedConfig != null) {
            WaqfSnapshot(
                config = cachedConfig,
                source = WaqfLoadSource.Cache
            )
        } else {
            WaqfSnapshot(
                config = coldOfflineFallbackConfig(),
                source = WaqfLoadSource.ColdFallback
            )
        }
    }

    private fun readValidCachedConfigOrNull(): WaqfConfig? {
        return try {
            val rawJson = prefs.getString(KEY_CACHED_WAQF_JSON, null)
            if (rawJson.isNullOrBlank()) return null
            WaqfConfig.fromJson(rawJson)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Cached Waqf JSON invalid; falling back to cold offline config")
            null
        }
    }

    private fun isCacheStale(maxAgeMs: Long): Boolean {
        if (readValidCachedConfigOrNull() == null) return true
        val lastFetchMs = try {
            prefs.getLong(KEY_LAST_FETCH_EPOCH_MS, 0L)
        } catch (_: Exception) {
            0L
        }
        if (lastFetchMs <= 0L) return true
        val now = clockProvider()
        if (now < lastFetchMs) return true
        return (now - lastFetchMs) >= maxAgeMs
    }

    private fun persistCache(rawJson: String, fetchedAtMs: Long) {
        try {
            prefs.edit()
                .putString(KEY_CACHED_WAQF_JSON, rawJson)
                .putLong(KEY_LAST_FETCH_EPOCH_MS, fetchedAtMs)
                .apply()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to persist Waqf cache to SharedPreferences")
        }
    }

    companion object {
        private const val TAG = "AntiPorn_WaqfRepo"
        const val PREFS_NAME = "hisn_waqf_cache_prefs"
        const val KEY_CACHED_WAQF_JSON = "cached_waqf_json"
        const val KEY_LAST_FETCH_EPOCH_MS = "last_fetch_epoch_ms"
        const val DEFAULT_PREFETCH_TTL_MS = 6L * 60L * 60L * 1000L // 6 hours

        // Public InstaPay link already published in README.md — NEVER store personal phone numbers here.
        const val PUBLIC_INSTAPAY_URL = "https://ipn.eg/S/mh22887565/instapay/4zd31u"
        const val PUBLIC_INSTAPAY_HANDLE = "mh22887565"

        @Volatile
        private var instance: WaqfRepository? = null

        fun getInstance(context: Context): WaqfRepository {
            return instance ?: synchronized(this) {
                val appContext = context.applicationContext ?: context
                instance ?: WaqfRepository(appContext).also { instance = it }
            }
        }

        /**
         * Returns the strictly zero-leak offline fallback configuration containing ONLY
         * the public InstaPay link and handle already published in README.md.
         */
        fun coldOfflineFallbackConfig(): WaqfConfig = WaqfConfig(
            schemaVersion = 1,
            channels = listOf(
                WaqfChannel(
                    id = "instapay_primary",
                    enabled = true,
                    sortOrder = 0,
                    type = WaqfChannelType.INSTAPAY,
                    title = "InstaPay",
                    displayValue = PUBLIC_INSTAPAY_HANDLE,
                    accentStyle = WaqfAccentStyle.EMERALD,
                    requiresBankingCloak = true,
                    targetPackage = BankingCloakManager.INSTAPAY_PACKAGE,
                    primaryAction = WaqfChannelAction(
                        label = "InstaPay",
                        type = WaqfActionType.OPEN_URL,
                        target = PUBLIC_INSTAPAY_URL
                    ),
                    secondaryAction = WaqfChannelAction(
                        label = PUBLIC_INSTAPAY_HANDLE,
                        type = WaqfActionType.COPY_VALUE,
                        target = PUBLIC_INSTAPAY_HANDLE
                    )
                )
            )
        )
    }
}
