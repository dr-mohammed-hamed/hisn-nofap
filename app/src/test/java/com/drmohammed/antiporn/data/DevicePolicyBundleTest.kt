package com.drmohammed.antiporn.data

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePolicyBundleTest {

    @Test
    fun testDefaultPolicy_hasExpectedSecurityDefaults() {
        val policy = DevicePolicyBundle.defaultPolicy()
        assertEquals(20, policy.curfewStartHour)
        assertEquals(0, policy.curfewStartMinute)
        assertEquals(7, policy.curfewEndHour)
        assertEquals(0, policy.curfewEndMinute)
        assertFalse(policy.isCurfewEnabled)
        assertFalse(policy.isInstantInternetKillEnabled)
        assertFalse(policy.isWeekendCurfewEnabled)
        assertEquals(20, policy.weekendCurfewStartHour)
        assertEquals(0, policy.weekendCurfewStartMinute)
        assertEquals(7, policy.weekendCurfewEndHour)
        assertEquals(0, policy.weekendCurfewEndMinute)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED, policy.isVpnBlackholeEnabled)
        assertTrue(policy.isVpnBlackholeEnabled)
        assertTrue(policy.isIncognitoBlocked)
        assertFalse(policy.lockNewInstallsUntilApproved)
        assertFalse(policy.isScreenAiEnabledGlobally)
        assertEquals(DevicePolicyBundle.CURRENT_SCREEN_AI_SCHEMA_VERSION, policy.screenAiSchemaVersion)
        assertTrue(policy.customBlacklistPackages.isEmpty())
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.STATIC_WHITELIST, policy.customCurfewWhitelistPackages)
        assertTrue(policy.curfewVpnBypassPackages.isEmpty())
        assertTrue(policy.protectedAppPackages.isEmpty())
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_OFF, policy.browserFilterMode)
        assertFalse(policy.isFullSystemFilteringEnabled)
        assertTrue(policy.customBlockedUrls.isEmpty())
        assertTrue(policy.customAllowedUrls.isEmpty())
        assertTrue(policy.isSelfControlAllowed)
        assertFalse(policy.isDateTimeLocked)
        assertEquals(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST, policy.dnsHost)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.getDefaultFallbackTimeZone(), policy.timeZoneId)
        assertEquals(1L, policy.version)
    }

    @Test
    fun testSerializationAndDeserialization_viaMap() {
        val original = DevicePolicyBundle(
            curfewStartHour = 22,
            curfewStartMinute = 15,
            curfewEndHour = 6,
            curfewEndMinute = 30,
            isCurfewEnabled = true,
            isInstantInternetKillEnabled = true,
            isWeekendCurfewEnabled = true,
            weekendCurfewStartHour = 23,
            weekendCurfewStartMinute = 45,
            weekendCurfewEndHour = 9,
            weekendCurfewEndMinute = 15,
            isVpnBlackholeEnabled = true,
            isIncognitoBlocked = true,
            lockNewInstallsUntilApproved = true,
            isSelfControlAllowed = false,
            isDateTimeLocked = true,
            customBlacklistPackages = setOf("com.bad.app1", "com.bad.app2"),
            customCurfewWhitelistPackages = setOf("com.work.app1"),
            customAiScanPackages = setOf("com.target.ai1"),
            excludedAiScanPackages = setOf("com.excluded.ai1"),
            curfewVpnBypassPackages = setOf("com.bypass.app1"),
            protectedAppPackages = setOf("com.vital.app1", "com.vital.app2"),
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST,
            isFullSystemFilteringEnabled = true,
            customBlockedUrls = setOf("badsite.com", "gambling.org"),
            customAllowedUrls = setOf("safe.org"),
            dnsHost = "custom.family-dns.com",
            timeZoneId = "Africa/Cairo",
            lastHeartbeat = 1790000050000L,
            breakoutStatus = DevicePolicyBundle.BREAKOUT_STATUS_PENDING,
            breakoutRequestedAt = 1790000000000L,
            breakoutUnlockAt = 1790086400000L,
            guardianPolicySnapshot = "{\"version\":40}",
            lastSelfModifiedAt = 1790000010000L,
            version = 42L,
            updatedAt = 1790000000000L,
            remoteSelfControlResetRequestedAt = 1790000020000L
        )

        val map = original.toMap()
        val restored = DevicePolicyBundle.fromMap(map)

        assertEquals(original.curfewStartHour, restored.curfewStartHour)
        assertEquals(original.curfewStartMinute, restored.curfewStartMinute)
        assertEquals(original.curfewEndHour, restored.curfewEndHour)
        assertEquals(original.curfewEndMinute, restored.curfewEndMinute)
        assertEquals(original.isCurfewEnabled, restored.isCurfewEnabled)
        assertEquals(original.isInstantInternetKillEnabled, restored.isInstantInternetKillEnabled)
        assertEquals(original.isWeekendCurfewEnabled, restored.isWeekendCurfewEnabled)
        assertEquals(original.weekendCurfewStartHour, restored.weekendCurfewStartHour)
        assertEquals(original.weekendCurfewStartMinute, restored.weekendCurfewStartMinute)
        assertEquals(original.weekendCurfewEndHour, restored.weekendCurfewEndHour)
        assertEquals(original.weekendCurfewEndMinute, restored.weekendCurfewEndMinute)
        assertEquals(original.isVpnBlackholeEnabled, restored.isVpnBlackholeEnabled)
        assertEquals(original.isIncognitoBlocked, restored.isIncognitoBlocked)
        assertEquals(original.lockNewInstallsUntilApproved, restored.lockNewInstallsUntilApproved)
        assertEquals(original.isSelfControlAllowed, restored.isSelfControlAllowed)
        assertEquals(original.isDateTimeLocked, restored.isDateTimeLocked)
        assertEquals(original.customBlacklistPackages, restored.customBlacklistPackages)
        assertEquals(original.customCurfewWhitelistPackages, restored.customCurfewWhitelistPackages)
        assertEquals(original.customAiScanPackages, restored.customAiScanPackages)
        assertEquals(original.excludedAiScanPackages, restored.excludedAiScanPackages)
        assertEquals(original.curfewVpnBypassPackages, restored.curfewVpnBypassPackages)
        assertEquals(original.protectedAppPackages, restored.protectedAppPackages)
        assertEquals(original.browserFilterMode, restored.browserFilterMode)
        assertEquals(original.isFullSystemFilteringEnabled, restored.isFullSystemFilteringEnabled)
        assertEquals(original.customBlockedUrls, restored.customBlockedUrls)
        assertEquals(original.customAllowedUrls, restored.customAllowedUrls)
        assertEquals(original.dnsHost, restored.dnsHost)
        assertEquals(original.timeZoneId, restored.timeZoneId)
        assertEquals(original.lastHeartbeat, restored.lastHeartbeat)
        assertEquals(original.breakoutStatus, restored.breakoutStatus)
        assertEquals(original.breakoutRequestedAt, restored.breakoutRequestedAt)
        assertEquals(original.breakoutUnlockAt, restored.breakoutUnlockAt)
        assertEquals(original.guardianPolicySnapshot, restored.guardianPolicySnapshot)
        assertEquals(original.lastSelfModifiedAt, restored.lastSelfModifiedAt)
        assertEquals(original.version, restored.version)
        assertEquals(original.updatedAt, restored.updatedAt)
        assertEquals(original.remoteSelfControlResetRequestedAt, restored.remoteSelfControlResetRequestedAt)
    }

    @Test
    fun testSerializationAndDeserialization_viaJson() {
        val original = DevicePolicyBundle(
            curfewStartHour = 21,
            curfewStartMinute = 0,
            curfewEndHour = 8,
            curfewEndMinute = 0,
            isCurfewEnabled = false,
            isInstantInternetKillEnabled = true,
            isWeekendCurfewEnabled = true,
            weekendCurfewStartHour = 22,
            weekendCurfewStartMinute = 30,
            weekendCurfewEndHour = 10,
            weekendCurfewEndMinute = 0,
            isVpnBlackholeEnabled = false,
            isIncognitoBlocked = false,
            lockNewInstallsUntilApproved = true,
            isSelfControlAllowed = false,
            isDateTimeLocked = true,
            customBlacklistPackages = setOf("pkg.a"),
            customCurfewWhitelistPackages = setOf("pkg.b"),
            customAiScanPackages = setOf("pkg.ai.scan"),
            excludedAiScanPackages = setOf("pkg.ai.excluded"),
            curfewVpnBypassPackages = setOf("pkg.vpn.bypass"),
            protectedAppPackages = setOf("pkg.c"),
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY,
            isFullSystemFilteringEnabled = true,
            customBlockedUrls = setOf("blocked1.com", "blocked2.com"),
            customAllowedUrls = setOf("allowed1.com"),
            dnsHost = "custom.family-dns.com",
            timeZoneId = "Asia/Dubai",
            lastHeartbeat = 1234567890L,
            breakoutStatus = DevicePolicyBundle.BREAKOUT_STATUS_COMPLETED,
            breakoutRequestedAt = 1234560000L,
            breakoutUnlockAt = 1234567800L,
            guardianPolicySnapshot = "{\"version\":4}",
            lastSelfModifiedAt = 1234560500L,
            version = 5L,
            updatedAt = 123456789L,
            remoteSelfControlResetRequestedAt = 1234560700L
        )

        val json = original.toJson()
        val restored = DevicePolicyBundle.fromJson(json)

        assertEquals(original.curfewStartHour, restored.curfewStartHour)
        assertEquals(original.curfewStartMinute, restored.curfewStartMinute)
        assertEquals(original.curfewEndHour, restored.curfewEndHour)
        assertEquals(original.curfewEndMinute, restored.curfewEndMinute)
        assertEquals(original.isCurfewEnabled, restored.isCurfewEnabled)
        assertEquals(original.isInstantInternetKillEnabled, restored.isInstantInternetKillEnabled)
        assertEquals(original.isWeekendCurfewEnabled, restored.isWeekendCurfewEnabled)
        assertEquals(original.weekendCurfewStartHour, restored.weekendCurfewStartHour)
        assertEquals(original.weekendCurfewStartMinute, restored.weekendCurfewStartMinute)
        assertEquals(original.weekendCurfewEndHour, restored.weekendCurfewEndHour)
        assertEquals(original.weekendCurfewEndMinute, restored.weekendCurfewEndMinute)
        assertEquals(original.isVpnBlackholeEnabled, restored.isVpnBlackholeEnabled)
        assertEquals(original.isIncognitoBlocked, restored.isIncognitoBlocked)
        assertEquals(original.lockNewInstallsUntilApproved, restored.lockNewInstallsUntilApproved)
        assertEquals(original.isSelfControlAllowed, restored.isSelfControlAllowed)
        assertEquals(original.isDateTimeLocked, restored.isDateTimeLocked)
        assertEquals(original.customBlacklistPackages, restored.customBlacklistPackages)
        assertEquals(original.customCurfewWhitelistPackages, restored.customCurfewWhitelistPackages)
        assertEquals(original.customAiScanPackages, restored.customAiScanPackages)
        assertEquals(original.excludedAiScanPackages, restored.excludedAiScanPackages)
        assertEquals(original.curfewVpnBypassPackages, restored.curfewVpnBypassPackages)
        assertEquals(original.protectedAppPackages, restored.protectedAppPackages)
        assertEquals(original.browserFilterMode, restored.browserFilterMode)
        assertEquals(original.isFullSystemFilteringEnabled, restored.isFullSystemFilteringEnabled)
        assertEquals(original.customBlockedUrls, restored.customBlockedUrls)
        assertEquals(original.customAllowedUrls, restored.customAllowedUrls)
        assertEquals(original.dnsHost, restored.dnsHost)
        assertEquals(original.timeZoneId, restored.timeZoneId)
        assertEquals(original.lastHeartbeat, restored.lastHeartbeat)
        assertEquals(original.breakoutStatus, restored.breakoutStatus)
        assertEquals(original.breakoutRequestedAt, restored.breakoutRequestedAt)
        assertEquals(original.breakoutUnlockAt, restored.breakoutUnlockAt)
        assertEquals(original.guardianPolicySnapshot, restored.guardianPolicySnapshot)
        assertEquals(original.lastSelfModifiedAt, restored.lastSelfModifiedAt)
        assertEquals(original.version, restored.version)
        assertEquals(original.updatedAt, restored.updatedAt)
        assertEquals(original.remoteSelfControlResetRequestedAt, restored.remoteSelfControlResetRequestedAt)
    }

    @Test
    fun testLegacyPolicyWithoutTimeZone_fallsBackToCairo() {
        val legacyJson = """
            {
                "curfewStartHour": 20,
                "curfewStartMinute": 0,
                "version": 1
            }
        """.trimIndent()
        val parsed = DevicePolicyBundle.fromJson(legacyJson)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_TIMEZONE_ID, parsed.timeZoneId)
        assertFalse(parsed.isDateTimeLocked)
        assertTrue(parsed.isSelfControlAllowed)

        val legacyMap = mapOf<String, Any?>(
            "curfewStartHour" to 20,
            "curfewStartMinute" to 0
        )
        val fromMap = DevicePolicyBundle.fromMap(legacyMap)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_TIMEZONE_ID, fromMap.timeZoneId)
        assertFalse(fromMap.isDateTimeLocked)
        assertTrue(fromMap.isSelfControlAllowed)

        val emptyTzPolicy = DevicePolicyBundle(timeZoneId = "")
        val reconciled = DevicePolicyBundle.reconcileLegacyV1(emptyTzPolicy)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_TIMEZONE_ID, reconciled.timeZoneId)
    }

    @Test
    fun testCurfewWhitelist_intentionalEmptyVsMissing() {
        val missingJson = """
            {
                "version": 1
            }
        """.trimIndent()
        val parsedMissing = DevicePolicyBundle.fromJson(missingJson)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.STATIC_WHITELIST, parsedMissing.customCurfewWhitelistPackages)

        val explicitEmptyJson = """
            {
                "version": 1,
                "customCurfewWhitelistPackages": []
            }
        """.trimIndent()
        val parsedEmpty = DevicePolicyBundle.fromJson(explicitEmptyJson)
        assertTrue(parsedEmpty.customCurfewWhitelistPackages.isEmpty())

        val missingMap = emptyMap<String, Any?>()
        val parsedMissingMap = DevicePolicyBundle.fromMap(missingMap)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.STATIC_WHITELIST, parsedMissingMap.customCurfewWhitelistPackages)

        val explicitEmptyMap = mapOf<String, Any?>("customCurfewWhitelistPackages" to emptyList<String>())
        val parsedEmptyMap = DevicePolicyBundle.fromMap(explicitEmptyMap)
        assertTrue(parsedEmptyMap.customCurfewWhitelistPackages.isEmpty())
    }

    @Test
    fun testLegacyPayloadWithoutBrowserFilter_defaultsSafely() {
        val legacyJson = """
            {
                "curfewStartHour": 20,
                "version": 1
            }
        """.trimIndent()
        val parsed = DevicePolicyBundle.fromJson(legacyJson)
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_OFF, parsed.browserFilterMode)
        assertTrue(parsed.customBlockedUrls.isEmpty())
        assertTrue(parsed.customAllowedUrls.isEmpty())

        val legacyMap = emptyMap<String, Any?>()
        val parsedMap = DevicePolicyBundle.fromMap(legacyMap)
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_OFF, parsedMap.browserFilterMode)
        assertTrue(parsedMap.customBlockedUrls.isEmpty())
        assertTrue(parsedMap.customAllowedUrls.isEmpty())
    }

    @Test
    fun testBrowserFilterMode_coercesOutOfBoundsValues() {
        val jsonTooHigh = """{"browserFilterMode": 99}"""
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY, DevicePolicyBundle.fromJson(jsonTooHigh).browserFilterMode)

        val jsonNegative = """{"browserFilterMode": -5}"""
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_OFF, DevicePolicyBundle.fromJson(jsonNegative).browserFilterMode)

        val mapTooHigh = mapOf("browserFilterMode" to 50)
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY, DevicePolicyBundle.fromMap(mapTooHigh).browserFilterMode)

        val mapNegative = mapOf("browserFilterMode" to -10)
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_OFF, DevicePolicyBundle.fromMap(mapNegative).browserFilterMode)

        val mapString = mapOf("browserFilterMode" to "1")
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST, DevicePolicyBundle.fromMap(mapString).browserFilterMode)
    }

    @Test
    fun testBrowserFilterUrls_handlesNullAndMalformedEntriesSafely() {
        val jsonWithNullEntries = """
            {
                "browserFilterMode": 1,
                "customBlockedUrls": ["site1.com", null, "", "   ", "site2.com"],
                "customAllowedUrls": [null, "allowed.com"]
            }
        """.trimIndent()
        val parsed = DevicePolicyBundle.fromJson(jsonWithNullEntries)
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST, parsed.browserFilterMode)
        assertEquals(setOf("site1.com", "site2.com"), parsed.customBlockedUrls)
        assertEquals(setOf("allowed.com"), parsed.customAllowedUrls)
    }

    @Test
    fun testSelfControlAllowed_deserializationEdgeCases() {
        val jsonExplicitFalse = """{"isSelfControlAllowed": false}"""
        assertFalse(DevicePolicyBundle.fromJson(jsonExplicitFalse).isSelfControlAllowed)

        val jsonExplicitTrue = """{"isSelfControlAllowed": true}"""
        assertTrue(DevicePolicyBundle.fromJson(jsonExplicitTrue).isSelfControlAllowed)

        val mapExplicitFalse = mapOf("isSelfControlAllowed" to false)
        assertFalse(DevicePolicyBundle.fromMap(mapExplicitFalse).isSelfControlAllowed)

        val mapExplicitTrue = mapOf("isSelfControlAllowed" to true)
        assertTrue(DevicePolicyBundle.fromMap(mapExplicitTrue).isSelfControlAllowed)
    }

    @Test
    fun testGranularSelfControlPermissions_defaultsAndSerializationEdgeCases() {
        val defaultBundle = DevicePolicyBundle()
        assertTrue(defaultBundle.selfControlCurfewAllowed)
        assertFalse(defaultBundle.selfControlWebAllowed)
        assertFalse(defaultBundle.selfControlAppsAllowed)
        assertTrue(defaultBundle.selfControlWaqfaAllowed)
        assertFalse(defaultBundle.selfControlPageShieldAllowed)
        assertFalse(defaultBundle.selfControlNewAppsAllowed)

        val jsonExplicit = """
            {
                "selfControlCurfewAllowed": false,
                "selfControlWebAllowed": true,
                "selfControlAppsAllowed": true,
                "selfControlWaqfaAllowed": false,
                "selfControlPageShieldAllowed": true,
                "selfControlNewAppsAllowed": true
            }
        """.trimIndent()
        val parsedJson = DevicePolicyBundle.fromJson(jsonExplicit)
        assertFalse(parsedJson.selfControlCurfewAllowed)
        assertTrue(parsedJson.selfControlWebAllowed)
        assertTrue(parsedJson.selfControlAppsAllowed)
        assertFalse(parsedJson.selfControlWaqfaAllowed)
        assertTrue(parsedJson.selfControlPageShieldAllowed)
        assertTrue(parsedJson.selfControlNewAppsAllowed)

        val roundtrip = DevicePolicyBundle.fromJson(parsedJson.toJson())
        assertEquals(parsedJson.selfControlCurfewAllowed, roundtrip.selfControlCurfewAllowed)
        assertEquals(parsedJson.selfControlWebAllowed, roundtrip.selfControlWebAllowed)
        assertEquals(parsedJson.selfControlAppsAllowed, roundtrip.selfControlAppsAllowed)
        assertEquals(parsedJson.selfControlWaqfaAllowed, roundtrip.selfControlWaqfaAllowed)
        assertEquals(parsedJson.selfControlPageShieldAllowed, roundtrip.selfControlPageShieldAllowed)
        assertEquals(parsedJson.selfControlNewAppsAllowed, roundtrip.selfControlNewAppsAllowed)

        val map = parsedJson.toMap()
        val fromMap = DevicePolicyBundle.fromMap(map)
        assertEquals(parsedJson.selfControlCurfewAllowed, fromMap.selfControlCurfewAllowed)
        assertEquals(parsedJson.selfControlWebAllowed, fromMap.selfControlWebAllowed)
        assertEquals(parsedJson.selfControlAppsAllowed, fromMap.selfControlAppsAllowed)
        assertEquals(parsedJson.selfControlWaqfaAllowed, fromMap.selfControlWaqfaAllowed)
        assertEquals(parsedJson.selfControlPageShieldAllowed, fromMap.selfControlPageShieldAllowed)
        assertEquals(parsedJson.selfControlNewAppsAllowed, fromMap.selfControlNewAppsAllowed)
    }

    @Test
    fun testGuardianPolicyConstruction_ensuresSupervisionInvariants() {
        val baseDefault = DevicePolicyBundle.defaultPolicy()
        assertFalse(baseDefault.isSupervised)
        org.junit.Assert.assertNull(baseDefault.guardianUid)

        val myUid = "guardian_uid_123"
        val myName = "Guardian Device"
        val configuredPolicy = baseDefault.copy(
            guardianUid = baseDefault.guardianUid?.takeIf { it.isNotBlank() } ?: myUid,
            guardianName = baseDefault.guardianName?.takeIf { it.isNotBlank() } ?: myName,
            isSupervised = true
        )

        assertTrue(configuredPolicy.isSupervised)
        assertEquals("guardian_uid_123", configuredPolicy.guardianUid)
        assertEquals("Guardian Device", configuredPolicy.guardianName)

        val serializedMap = configuredPolicy.toMap()
        assertEquals(true, serializedMap["isSupervised"])
        assertEquals("guardian_uid_123", serializedMap["guardianUid"])
        assertEquals("Guardian Device", serializedMap["guardianName"])
    }

    @Test
    fun testFastSynchronousPolicySerializationRoundTrip() {
        val original = DevicePolicyBundle(
            curfewStartHour = 21,
            curfewStartMinute = 30,
            curfewEndHour = 8,
            curfewEndMinute = 0,
            isCurfewEnabled = true,
            version = 15L,
            updatedAt = 1750000000000L
        )
        val json = original.toJson()
        val deserialized = DevicePolicyBundle.fromJson(json)
        assertEquals(original.curfewStartHour, deserialized.curfewStartHour)
        assertEquals(original.curfewStartMinute, deserialized.curfewStartMinute)
        assertEquals(original.curfewEndHour, deserialized.curfewEndHour)
        assertEquals(original.curfewEndMinute, deserialized.curfewEndMinute)
        assertEquals(original.version, deserialized.version)
        assertEquals(original.isCurfewEnabled, deserialized.isCurfewEnabled)
    }

    @Test
    fun testDefaultPolicySerializationRoundTrip_preservesCurfewDisabled() {
        val defaults = DevicePolicyBundle.defaultPolicy()
        assertFalse(defaults.isCurfewEnabled)

        val json = defaults.toJson()
        val parsedJsonObj = org.json.JSONObject(json)
        assertTrue(parsedJsonObj.has("isCurfewEnabled"))
        assertFalse(parsedJsonObj.getBoolean("isCurfewEnabled"))
        assertFalse(DevicePolicyBundle.fromJson(json).isCurfewEnabled)

        val map = defaults.toMap()
        assertTrue(map.containsKey("isCurfewEnabled"))
        assertEquals(false, map["isCurfewEnabled"])
        assertFalse(DevicePolicyBundle.fromMap(map).isCurfewEnabled)
    }

    @Test
    fun testLegacyPayloadWithoutCurfewEnabled_preservesCurfewEnabledTrue() {
        val legacyJson = """{"version": 1}"""
        assertTrue(DevicePolicyBundle.fromJson(legacyJson).isCurfewEnabled)

        val legacyMap = mapOf<String, Any?>("version" to 1L)
        assertTrue(DevicePolicyBundle.fromMap(legacyMap).isCurfewEnabled)
    }

    @Test
    fun testMalformedJson_fallsBackToDefaultPolicyWithCurfewDisabled() {
        val fallback = DevicePolicyBundle.fromJson("{not valid json")
        val defaults = DevicePolicyBundle.defaultPolicy()
        assertFalse(fallback.isCurfewEnabled)
        assertTrue(fallback.isVpnBlackholeEnabled)
        assertEquals(defaults.curfewStartHour, fallback.curfewStartHour)
        assertEquals(defaults.curfewEndHour, fallback.curfewEndHour)
        assertEquals(defaults.dnsHost, fallback.dnsHost)
        assertEquals(defaults.customCurfewWhitelistPackages, fallback.customCurfewWhitelistPackages)
    }

    @Test
    fun testUpdatedAtDefaultIsZeroAndFromJsonOrNullDistinguishesCorruption() {
        val constructed = DevicePolicyBundle()
        assertEquals(0L, constructed.updatedAt)

        val partialJson = DevicePolicyBundle.fromJson("""{"version": 1}""")
        assertEquals(0L, partialJson.updatedAt)

        assertNull(DevicePolicyBundle.fromJsonOrNull("{corrupted"))
        assertNull(DevicePolicyBundle.fromJsonOrNull(""))
        assertNotNull(DevicePolicyBundle.fromJsonOrNull(constructed.toJson()))
    }

    @Test
    fun testLegacyScreenAiSchemaV1_migratesTrueToFalseAndUpgradesSchemaVersionTo2() {
        // 1. Missing screenAiSchemaVersion with legacy isScreenAiEnabledGlobally = true
        val legacyMapMissing = mapOf<String, Any?>(
            "isScreenAiEnabledGlobally" to true,
            "version" to 3L
        )
        val migratedFromMapMissing = DevicePolicyBundle.fromMap(legacyMapMissing)
        assertFalse(migratedFromMapMissing.isScreenAiEnabledGlobally)
        assertEquals(DevicePolicyBundle.CURRENT_SCREEN_AI_SCHEMA_VERSION, migratedFromMapMissing.screenAiSchemaVersion)
        assertEquals(2, migratedFromMapMissing.screenAiSchemaVersion)

        val legacyJsonMissing = """{"isScreenAiEnabledGlobally": true, "version": 3}"""
        val migratedFromJsonMissing = DevicePolicyBundle.fromJson(legacyJsonMissing)
        assertFalse(migratedFromJsonMissing.isScreenAiEnabledGlobally)
        assertEquals(2, migratedFromJsonMissing.screenAiSchemaVersion)

        // 2. Explicit screenAiSchemaVersion = 1 with isScreenAiEnabledGlobally = true
        val legacyMapV1 = mapOf<String, Any?>(
            "isScreenAiEnabledGlobally" to true,
            "screenAiSchemaVersion" to 1
        )
        val migratedFromMapV1 = DevicePolicyBundle.fromMap(legacyMapV1)
        assertFalse(migratedFromMapV1.isScreenAiEnabledGlobally)
        assertEquals(2, migratedFromMapV1.screenAiSchemaVersion)

        val legacyJsonV1 = """{"isScreenAiEnabledGlobally": true, "screenAiSchemaVersion": 1}"""
        val migratedFromJsonV1 = DevicePolicyBundle.fromJson(legacyJsonV1)
        assertFalse(migratedFromJsonV1.isScreenAiEnabledGlobally)
        assertEquals(2, migratedFromJsonV1.screenAiSchemaVersion)
    }

    @Test
    fun testScreenAiSchemaV2AndAbove_preservesIsScreenAiEnabledGloballyTrue() {
        val v2Map = mapOf<String, Any?>(
            "isScreenAiEnabledGlobally" to true,
            "screenAiSchemaVersion" to 2
        )
        val fromV2Map = DevicePolicyBundle.fromMap(v2Map)
        assertTrue(fromV2Map.isScreenAiEnabledGlobally)
        assertEquals(2, fromV2Map.screenAiSchemaVersion)

        val v2Json = """{"isScreenAiEnabledGlobally": true, "screenAiSchemaVersion": 2}"""
        val fromV2Json = DevicePolicyBundle.fromJson(v2Json)
        assertTrue(fromV2Json.isScreenAiEnabledGlobally)
        assertEquals(2, fromV2Json.screenAiSchemaVersion)

        // Future schema version (>= 2) also preserved
        val v3Bundle = DevicePolicyBundle(
            isScreenAiEnabledGlobally = true,
            screenAiSchemaVersion = 3
        )
        val v3RoundTripMap = DevicePolicyBundle.fromMap(v3Bundle.toMap())
        assertTrue(v3RoundTripMap.isScreenAiEnabledGlobally)
        assertEquals(3, v3RoundTripMap.screenAiSchemaVersion)

        val v3RoundTripJson = DevicePolicyBundle.fromJson(v3Bundle.toJson())
        assertTrue(v3RoundTripJson.isScreenAiEnabledGlobally)
        assertEquals(3, v3RoundTripJson.screenAiSchemaVersion)
    }

    @Test
    fun testWardCapabilityFields_excludedFromToMap_butRoundTrippedInFromMapAndJson() {
        val populated = DevicePolicyBundle(
            wardHasAccessibilityService = true,
            wardHasUsageStats = false,
            wardCanWriteSecureSettings = true,
            wardCapabilitiesUpdatedAt = 1791234567890L,
            wardA11yTamperAt = 1791234500000L,
            wardSdkInt = 35
        )

        // toMap() MUST NEVER output any of the 6 ward* telemetry keys so Guardian pushes never clobber them
        val map = populated.toMap()
        assertFalse(map.containsKey("wardHasAccessibilityService"))
        assertFalse(map.containsKey("wardHasUsageStats"))
        assertFalse(map.containsKey("wardCanWriteSecureSettings"))
        assertFalse(map.containsKey("wardCapabilitiesUpdatedAt"))
        assertFalse(map.containsKey("wardA11yTamperAt"))
        assertFalse(map.containsKey("wardSdkInt"))

        // fromMap() reads all 6 keys cleanly when present in Firestore document snapshots
        val firestoreDoc = map.toMutableMap().apply {
            put("wardHasAccessibilityService", true)
            put("wardHasUsageStats", false)
            put("wardCanWriteSecureSettings", true)
            put("wardCapabilitiesUpdatedAt", 1791234567890L)
            put("wardA11yTamperAt", 1791234500000L)
            put("wardSdkInt", 35)
        }
        val fromMapRestored = DevicePolicyBundle.fromMap(firestoreDoc)
        assertEquals(true, fromMapRestored.wardHasAccessibilityService)
        assertEquals(false, fromMapRestored.wardHasUsageStats)
        assertEquals(true, fromMapRestored.wardCanWriteSecureSettings)
        assertEquals(1791234567890L, fromMapRestored.wardCapabilitiesUpdatedAt)
        assertEquals(1791234500000L, fromMapRestored.wardA11yTamperAt)
        assertEquals(35, fromMapRestored.wardSdkInt)

        // toJson() + fromJson() round-trip all 6 keys cleanly for local cache and activity Intent extras
        val jsonStr = populated.toJson()
        val fromJsonRestored = DevicePolicyBundle.fromJson(jsonStr)
        assertEquals(true, fromJsonRestored.wardHasAccessibilityService)
        assertEquals(false, fromJsonRestored.wardHasUsageStats)
        assertEquals(true, fromJsonRestored.wardCanWriteSecureSettings)
        assertEquals(1791234567890L, fromJsonRestored.wardCapabilitiesUpdatedAt)
        assertEquals(1791234500000L, fromJsonRestored.wardA11yTamperAt)
        assertEquals(35, fromJsonRestored.wardSdkInt)

        // Default null/0 values round-trip cleanly too
        val defaultJsonRestored = DevicePolicyBundle.fromJson(DevicePolicyBundle.defaultPolicy().toJson())
        assertNull(defaultJsonRestored.wardHasAccessibilityService)
        assertNull(defaultJsonRestored.wardHasUsageStats)
        assertNull(defaultJsonRestored.wardCanWriteSecureSettings)
        assertEquals(0L, defaultJsonRestored.wardCapabilitiesUpdatedAt)
        assertEquals(0L, defaultJsonRestored.wardA11yTamperAt)
        assertNull(defaultJsonRestored.wardSdkInt)
    }

    @Test
    fun testWaqfaUseInstantAccessibilityMode_defaultFalseAndRoundTripsTrueInMapAndJson() {
        val defaultWaqfa = com.drmohammed.antiporn.waqfa.model.WaqfaConfig()
        assertFalse(defaultWaqfa.useInstantAccessibilityMode)

        // Legacy payload missing useInstantAccessibilityMode defaults to false
        assertFalse(
            com.drmohammed.antiporn.waqfa.model.WaqfaConfig.fromMap(mapOf("isEnabled" to true)).useInstantAccessibilityMode
        )
        assertFalse(
            com.drmohammed.antiporn.waqfa.model.WaqfaConfig.fromJson(org.json.JSONObject("""{"isEnabled": true}""")).useInstantAccessibilityMode
        )

        // Round-trip true in Map & JSON via DevicePolicyBundle
        val instantBundle = DevicePolicyBundle.defaultPolicy().copy(
            waqfaConfig = com.drmohammed.antiporn.waqfa.model.WaqfaConfig(
                isEnabled = true,
                targetPackages = setOf("com.instagram.android"),
                useInstantAccessibilityMode = true
            )
        )

        val restoredFromMap = DevicePolicyBundle.fromMap(instantBundle.toMap())
        assertTrue(restoredFromMap.waqfaConfig.useInstantAccessibilityMode)
        assertEquals(setOf("com.instagram.android"), restoredFromMap.waqfaConfig.targetPackages)

        val restoredFromJson = DevicePolicyBundle.fromJson(instantBundle.toJson())
        assertTrue(restoredFromJson.waqfaConfig.useInstantAccessibilityMode)
        assertEquals(setOf("com.instagram.android"), restoredFromJson.waqfaConfig.targetPackages)
    }
}
