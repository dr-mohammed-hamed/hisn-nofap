package com.drmohammed.antiporn.waqf

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.drmohammed.antiporn.banking.BankingCloakManager
import com.drmohammed.antiporn.waqf.model.WaqfAccentStyle
import com.drmohammed.antiporn.waqf.model.WaqfActionType
import com.drmohammed.antiporn.waqf.model.WaqfActionValidator
import com.drmohammed.antiporn.waqf.model.WaqfChannelAction
import com.drmohammed.antiporn.waqf.model.WaqfChannelType
import com.drmohammed.antiporn.waqf.model.WaqfConfig
import com.drmohammed.antiporn.waqf.model.WaqfLoadSource
import com.drmohammed.antiporn.waqf.remote.WaqfRemoteSource
import com.drmohammed.antiporn.waqf.repository.WaqfRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class WaqfConfigAndRepositoryTest {

    private lateinit var dummyContext: Context
    private lateinit var prefs: InMemorySharedPreferences

    @Before
    fun setUp() {
        dummyContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        prefs = InMemorySharedPreferences()
        WaqfRemoteSource.fetchJsonOverride = null
    }

    @After
    fun tearDown() {
        WaqfRemoteSource.fetchJsonOverride = null
    }

    // =========================================================================
    // WaqfConfig.fromJson Tests
    // =========================================================================

    @Test
    fun fromJson_parsesValidEgyptianChannelsAndHeroAndSortsBySortOrder() {
        val json = """
            {
              "schemaVersion": 2,
              "updatedAt": "2026-11-25T10:00:00Z",
              "hero": {
                "badge": "مشروع فردي مجاني 100%",
                "hadith": "من صنع إليكم معروفاً فكافئوه",
                "title": "ادعم استمرار تطوير حصن",
                "body": "مشروع مجاني مفتوح المصدر",
                "notice": null
              },
              "channels": [
                {
                  "id": "vodafone_cash",
                  "enabled": true,
                  "sortOrder": 20,
                  "type": "MOBILE_WALLET",
                  "badge": "فودافون كاش",
                  "title": "محفظة فودافون كاش",
                  "subtitle": "تحويل عبر الكود المختصر",
                  "displayValue": "01012345678",
                  "instructions": "اطلب الكود وأكد المبلغ",
                  "accentStyle": "CRIMSON",
                  "requiresBankingCloak": false,
                  "primaryAction": {
                    "label": "تحويل الآن",
                    "type": "DIAL_USSD",
                    "target": "*9*7*01012345678#"
                  },
                  "secondaryAction": {
                    "label": "نسخ الرقم",
                    "type": "COPY_VALUE",
                    "target": "01012345678"
                  }
                },
                {
                  "id": "instapay_eg",
                  "enabled": true,
                  "sortOrder": 10,
                  "type": "INSTAPAY",
                  "title": "InstaPay",
                  "displayValue": "mh22887565",
                  "accentStyle": "EMERALD",
                  "requiresBankingCloak": true,
                  "targetPackage": "com.egyptianbanks.instapay",
                  "primaryAction": {
                    "label": "فتح إنستاباي",
                    "type": "OPEN_URL",
                    "target": "https://ipn.eg/S/mh22887565/instapay/4zd31u"
                  }
                }
              ]
            }
        """.trimIndent()

        val config = WaqfConfig.fromJson(json)

        assertEquals(2, config.schemaVersion)
        assertEquals("2026-11-25T10:00:00Z", config.updatedAt)
        assertNotNull(config.hero)
        assertEquals("مشروع فردي مجاني 100%", config.hero?.badge)
        assertNull(config.hero?.notice)

        val active = config.activeChannels()
        assertEquals(2, active.size)
        // Sorted ascending by sortOrder: instapay_eg (10) before vodafone_cash (20)
        assertEquals("instapay_eg", active[0].id)
        assertEquals(WaqfChannelType.INSTAPAY, active[0].type)
        assertTrue(active[0].requiresBankingCloak)
        assertEquals(BankingCloakManager.INSTAPAY_PACKAGE, active[0].targetPackage)
        assertEquals("vodafone_cash", active[1].id)
        assertEquals(WaqfChannelType.MOBILE_WALLET, active[1].type)
        assertEquals(WaqfAccentStyle.CRIMSON, active[1].accentStyle)
        assertEquals(WaqfActionType.DIAL_USSD, active[1].primaryAction?.type)
        assertEquals(WaqfActionType.COPY_VALUE, active[1].secondaryAction?.type)
    }

    @Test
    fun fromJson_handlesUnknownFutureEnumsDisabledAndMalformedChannelsGracefully() {
        val json = """
            {
              "schemaVersion": null,
              "channels": [
                "not-an-object",
                {
                  "id": "missing_title",
                  "enabled": true,
                  "title": "   "
                },
                {
                  "id": "disabled_channel",
                  "enabled": false,
                  "sortOrder": 1,
                  "type": "WEB_LINK",
                  "title": "Disabled Channel"
                },
                {
                  "id": "future_paypal",
                  "enabled": true,
                  "sortOrder": 5,
                  "type": "PAYPAL",
                  "title": "PayPal Future",
                  "accentStyle": "GOLD_GLOW",
                  "primaryAction": {
                    "label": "Pay",
                    "type": "OPEN_PAYPAL_SDK",
                    "target": "paypal://donate"
                  }
                },
                {
                  "id": "future_iban",
                  "enabled": true,
                  "sortOrder": 3,
                  "type": "BANK_IBAN",
                  "title": "Bank IBAN",
                  "subtitle": null,
                  "primaryAction": {
                    "label": "Copy IBAN",
                    "type": "COPY_VALUE",
                    "target": "EG380019000500000000263180002"
                  }
                }
              ]
            }
        """.trimIndent()

        val config = WaqfConfig.fromJson(json)

        assertEquals(1, config.schemaVersion)
        // 3 valid parsed channels (disabled_channel + future_paypal + future_iban); blank title & string entry skipped
        assertEquals(3, config.channels.size)

        val active = config.activeChannels()
        assertEquals(2, active.size)
        assertEquals("future_iban", active[0].id)
        assertEquals(WaqfChannelType.OTHER, active[0].type)
        assertNull(active[0].subtitle)
        assertEquals("future_paypal", active[1].id)
        assertEquals(WaqfChannelType.OTHER, active[1].type)
        assertEquals(WaqfAccentStyle.EMERALD, active[1].accentStyle)
        assertEquals(WaqfActionType.UNKNOWN, active[1].primaryAction?.type)
        // Unknown future action must fail safely to null when validated/sanitized
        assertNull(active[1].effectivePrimaryAction())
        assertNull(active[1].sanitizeChannelActions().primaryAction)
    }

    // =========================================================================
    // WaqfActionValidator Security Tests
    // =========================================================================

    @Test
    fun actionValidator_acceptsOnlySafeHttpsUrls() {
        assertTrue(WaqfActionValidator.isSafeHttpsUrl("https://ipn.eg/S/mh22887565/instapay/4zd31u"))
        assertTrue(WaqfActionValidator.isSafeHttpsUrl("  HTTPS://example.com/path?a=1  "))

        assertFalse(WaqfActionValidator.isSafeHttpsUrl("http://ipn.eg/S/test"))
        assertFalse(WaqfActionValidator.isSafeHttpsUrl("javascript:alert(1)"))
        assertFalse(WaqfActionValidator.isSafeHttpsUrl("file:///etc/passwd"))
        assertFalse(WaqfActionValidator.isSafeHttpsUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(WaqfActionValidator.isSafeHttpsUrl("https:///missing-host"))
        assertFalse(WaqfActionValidator.isSafeHttpsUrl("   "))
    }

    @Test
    fun actionValidator_validatesUssdAndEffectiveActionSafely() {
        assertTrue(WaqfActionValidator.isValidUssd("*9*7*01012345678#"))
        assertTrue(WaqfActionValidator.isValidUssd(" *858*1# "))
        assertFalse(WaqfActionValidator.isValidUssd("01012345678")) // missing * or #
        assertFalse(WaqfActionValidator.isValidUssd("tel:*9#"))
        assertFalse(WaqfActionValidator.isValidUssd("*9#;rm -rf"))
        assertFalse(WaqfActionValidator.isValidUssd(""))

        val badUrlAction = WaqfChannelAction("Open", WaqfActionType.OPEN_URL, "http://insecure.example.com")
        val badUssdAction = WaqfChannelAction("Dial", WaqfActionType.DIAL_USSD, "tel:*9#")
        val unknownAction = WaqfChannelAction("Future", WaqfActionType.UNKNOWN, "foo")
        val validCopyAction = WaqfChannelAction(" Copy ", WaqfActionType.COPY_VALUE, " mh22887565 ")

        assertNull(badUrlAction.effectiveAction())
        assertNull(badUssdAction.effectiveAction())
        assertNull(unknownAction.effectiveAction())
        assertEquals(
            WaqfChannelAction("Copy", WaqfActionType.COPY_VALUE, "mh22887565"),
            validCopyAction.effectiveAction()
        )
    }

    // =========================================================================
    // WaqfRemoteSource Tests
    // =========================================================================

    @Test
    fun remoteSource_usesInjectableOverrideAndRejectsInsecureUrls() = runBlocking {
        WaqfRemoteSource.fetchJsonOverride = { url ->
            assertTrue(url.endsWith("/waqf_config.json"))
            Result.success("""{"channels":[]}""")
        }
        val overrideResult = WaqfRemoteSource.fetchWaqfConfigJson()
        assertTrue(overrideResult.isSuccess)
        assertEquals("""{"channels":[]}""", overrideResult.getOrNull())

        WaqfRemoteSource.fetchJsonOverride = null
        val rejected = WaqfRemoteSource.fetchWaqfConfigJson("http://insecure.example.com/waqf_config.json")
        assertTrue(rejected.isFailure)
    }

    // =========================================================================
    // WaqfRepository SWR, Zero-Leak, Caching, and Prefetch Tests
    // =========================================================================

    @Test
    fun repository_coldFallbackIsImmediateAndContainsZeroPersonalPhoneNumbers() {
        val repo = WaqfRepository(
            appContext = dummyContext,
            prefs = prefs,
            remoteFetcher = { Result.failure(IOException("offline")) },
            clockProvider = { 1_000_000L }
        )

        val snap = repo.snapshot.value
        assertEquals(WaqfLoadSource.ColdFallback, snap.source)
        assertFalse(snap.isRefreshing)
        assertNull(snap.lastError)

        val channels = snap.config.activeChannels()
        assertEquals(1, channels.size)
        val instapay = channels[0]
        assertEquals(WaqfChannelType.INSTAPAY, instapay.type)
        assertEquals(WaqfRepository.PUBLIC_INSTAPAY_HANDLE, instapay.displayValue)
        assertEquals(WaqfRepository.PUBLIC_INSTAPAY_URL, instapay.primaryAction?.target)
        assertTrue(instapay.requiresBankingCloak)
        assertEquals(BankingCloakManager.INSTAPAY_PACKAGE, instapay.targetPackage)

        // Strict zero-leak check: NO Egyptian mobile numbers or USSD dial actions in cold fallback
        val serializedFallback = listOfNotNull(
            instapay.title,
            instapay.subtitle,
            instapay.displayValue,
            instapay.instructions,
            instapay.primaryAction?.label,
            instapay.primaryAction?.target,
            instapay.secondaryAction?.label,
            instapay.secondaryAction?.target
        ).joinToString(" ")
        assertFalse(
            "Cold fallback must NEVER contain any Egyptian mobile phone number",
            Regex("01[0125][0-9]{8}").containsMatchIn(serializedFallback)
        )
        assertTrue(channels.none { it.primaryAction?.type == WaqfActionType.DIAL_USSD })
    }

    @Test
    fun repository_initializesFromCacheOverColdFallback() {
        val cachedJson = """
            {
              "schemaVersion": 1,
              "channels": [
                {
                  "id": "cached_channel",
                  "enabled": true,
                  "sortOrder": 1,
                  "type": "WEB_LINK",
                  "title": "Cached Web Waqf",
                  "primaryAction": {
                    "label": "Open",
                    "type": "OPEN_URL",
                    "target": "https://example.org/waqf"
                  }
                }
              ]
            }
        """.trimIndent()
        prefs.edit()
            .putString(WaqfRepository.KEY_CACHED_WAQF_JSON, cachedJson)
            .putLong(WaqfRepository.KEY_LAST_FETCH_EPOCH_MS, 5_000_000L)
            .apply()

        val repo = WaqfRepository(
            appContext = dummyContext,
            prefs = prefs,
            remoteFetcher = { Result.failure(IOException("unused")) },
            clockProvider = { 5_001_000L }
        )

        val snap = repo.snapshot.value
        assertEquals(WaqfLoadSource.Cache, snap.source)
        assertEquals(1, snap.config.activeChannels().size)
        assertEquals("cached_channel", snap.config.activeChannels()[0].id)
    }

    @Test
    fun repository_refreshUpdatesCacheAndEmitsRefreshingThenNetworkState() = runBlocking {
        val remoteJson = """
            {
              "schemaVersion": 3,
              "channels": [
                {
                  "id": "network_instapay",
                  "enabled": true,
                  "sortOrder": 1,
                  "type": "INSTAPAY",
                  "title": "InstaPay Live",
                  "displayValue": "mh22887565",
                  "requiresBankingCloak": true,
                  "targetPackage": "com.egyptianbanks.instapay",
                  "primaryAction": {
                    "label": "Open",
                    "type": "OPEN_URL",
                    "target": "https://ipn.eg/S/mh22887565/instapay/4zd31u"
                  }
                }
              ]
            }
        """.trimIndent()
        var nowMs = 10_000_000L

        val repo = WaqfRepository(
            appContext = dummyContext,
            prefs = prefs,
            remoteFetcher = {
                delay(20)
                Result.success(remoteJson)
            },
            clockProvider = { nowMs }
        )

        val observedStates = mutableListOf<Pair<WaqfLoadSource, Boolean>>()
        val collector = launch(Dispatchers.Unconfined) {
            repo.snapshot.collect { snap ->
                observedStates.add(snap.source to snap.isRefreshing)
            }
        }

        val result = repo.refresh(force = true)
        collector.cancel()

        assertTrue(result.isSuccess)
        val snap = repo.snapshot.value
        assertEquals(WaqfLoadSource.Network, snap.source)
        assertFalse(snap.isRefreshing)
        assertNull(snap.lastError)
        assertEquals(3, snap.config.schemaVersion)
        assertEquals("network_instapay", snap.config.activeChannels()[0].id)
        assertEquals(remoteJson, prefs.getString(WaqfRepository.KEY_CACHED_WAQF_JSON, null))
        assertEquals(nowMs, prefs.getLong(WaqfRepository.KEY_LAST_FETCH_EPOCH_MS, 0L))
        assertTrue(observedStates.any { it.second })
        assertEquals(WaqfLoadSource.Network to false, observedStates.last())
    }

    @Test
    fun repository_failedNetworkPreservesExistingCacheAndRecordsLastError() = runBlocking {
        val cachedJson = """
            {
              "schemaVersion": 1,
              "channels": [
                {
                  "id": "existing_cached",
                  "enabled": true,
                  "sortOrder": 1,
                  "type": "COPY_ONLY",
                  "title": "Existing Cached Channel",
                  "displayValue": "mh22887565"
                }
              ]
            }
        """.trimIndent()
        prefs.edit()
            .putString(WaqfRepository.KEY_CACHED_WAQF_JSON, cachedJson)
            .putLong(WaqfRepository.KEY_LAST_FETCH_EPOCH_MS, 1_000L)
            .apply()

        val repo = WaqfRepository(
            appContext = dummyContext,
            prefs = prefs,
            remoteFetcher = { Result.failure(IOException("timeout")) },
            clockProvider = { 99_999_999L }
        )

        val result = repo.refresh(force = true)

        assertTrue(result.isFailure)
        val snap = repo.snapshot.value
        assertEquals(WaqfLoadSource.Cache, snap.source)
        assertFalse(snap.isRefreshing)
        assertEquals("timeout", snap.lastError)
        assertEquals("existing_cached", snap.config.activeChannels()[0].id)
        assertEquals(cachedJson, prefs.getString(WaqfRepository.KEY_CACHED_WAQF_JSON, null))
        assertEquals(1_000L, prefs.getLong(WaqfRepository.KEY_LAST_FETCH_EPOCH_MS, 0L))
    }

    @Test
    fun repository_prefetchIfStaleSkipsWhenFreshAndFetchesWhenStale() = runBlocking {
        val validJson = """
            {
              "schemaVersion": 1,
              "channels": [
                {
                  "id": "cached_ok",
                  "enabled": true,
                  "sortOrder": 1,
                  "type": "COPY_ONLY",
                  "title": "Cached OK"
                }
              ]
            }
        """.trimIndent()
        val updatedJson = """
            {
              "schemaVersion": 2,
              "channels": [
                {
                  "id": "refreshed_ok",
                  "enabled": true,
                  "sortOrder": 1,
                  "type": "COPY_ONLY",
                  "title": "Refreshed OK"
                }
              ]
            }
        """.trimIndent()

        var nowMs = 1_000_000L
        prefs.edit()
            .putString(WaqfRepository.KEY_CACHED_WAQF_JSON, validJson)
            .putLong(WaqfRepository.KEY_LAST_FETCH_EPOCH_MS, nowMs)
            .apply()

        val fetchCalls = AtomicInteger(0)
        val repo = WaqfRepository(
            appContext = dummyContext,
            prefs = prefs,
            remoteFetcher = {
                fetchCalls.incrementAndGet()
                Result.success(updatedJson)
            },
            clockProvider = { nowMs },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )

        // Fresh: 1 hour later (< 6 hours TTL) -> skipped
        nowMs += 60L * 60L * 1000L
        val skippedJob = repo.prefetchIfStaleAsync()
        assertNull(skippedJob)
        assertEquals(0, fetchCalls.get())

        // Stale: 6 hours after last fetch -> fetches in background
        nowMs = 1_000_000L + WaqfRepository.DEFAULT_PREFETCH_TTL_MS
        val fetchJob = repo.prefetchIfStaleAsync()
        assertNotNull(fetchJob)
        fetchJob!!.join()

        assertEquals(1, fetchCalls.get())
        assertEquals(WaqfLoadSource.Network, repo.snapshot.value.source)
        assertEquals("refreshed_ok", repo.snapshot.value.config.activeChannels()[0].id)
    }

    // =========================================================================
    // Test Doubles
    // =========================================================================

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) removals.add(key) }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                data.putAll(pending)
            }
        }
    }
}
