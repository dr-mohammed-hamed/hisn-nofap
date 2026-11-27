package com.drmohammed.antiporn.fortress.model

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FortressesConfigTest {

    @Test
    fun emergencyProfileNormalized_forcesInvariantsAndBounds() {
        val raw = FortressProfile(
            id = "tampered_id",
            name = "  Emergency  ",
            durationMinutes = -15,
            isEmergency = true,
            isInternetCutEnabled = false,
            allowedPackages = setOf(" com.whatsapp ", "", "  "),
            vpnBypassPackages = setOf("com.whatsapp"),
            sortOrder = 99
        )

        val normalized = raw.normalized()

        assertEquals(FortressProfile.EMERGENCY_FORTRESS_ID, normalized.id)
        assertEquals("Emergency", normalized.name)
        assertEquals(1, normalized.durationMinutes)
        assertTrue(normalized.isEmergency)
        assertTrue(normalized.isInternetCutEnabled)
        assertEquals(setOf("com.whatsapp"), normalized.allowedPackages)
        assertTrue(normalized.vpnBypassPackages.isEmpty())
        assertEquals(0, normalized.sortOrder)

        val promotedById = FortressProfile(
            id = " ${FortressProfile.EMERGENCY_FORTRESS_ID} ",
            isEmergency = false,
            isInternetCutEnabled = false,
            vpnBypassPackages = setOf("com.whatsapp")
        ).normalized()
        assertTrue(promotedById.isEmergency)
        assertTrue(promotedById.isInternetCutEnabled)
        assertTrue(promotedById.vpnBypassPackages.isEmpty())
    }

    @Test
    fun customProfileNormalized_clearsBypassWhenInternetCutOffAndFiltersToAllowed() {
        val noCut = FortressProfile(
            id = "study",
            name = "Study",
            durationMinutes = 45,
            isEmergency = false,
            isInternetCutEnabled = false,
            allowedPackages = setOf("com.xodo.pdf.reader"),
            vpnBypassPackages = setOf("com.xodo.pdf.reader"),
            sortOrder = 2
        ).normalized()

        assertFalse(noCut.isInternetCutEnabled)
        assertTrue(noCut.vpnBypassPackages.isEmpty())

        val withCut = FortressProfile(
            id = "work",
            name = "Work",
            durationMinutes = 0,
            isEmergency = false,
            isInternetCutEnabled = true,
            allowedPackages = setOf("com.whatsapp"),
            vpnBypassPackages = setOf("com.whatsapp", "com.unallowed.app"),
            sortOrder = 1
        ).normalized()

        assertEquals(1, withCut.durationMinutes)
        assertTrue(withCut.isInternetCutEnabled)
        assertEquals(setOf("com.whatsapp"), withCut.vpnBypassPackages)
    }

    @Test
    fun fortressesConfig_refusesEmergencyDeleteSortsCustomsAndPreservesOnUpsert() {
        val second = FortressProfile(id = "f2", name = "Beta", sortOrder = 20, durationMinutes = 30)
        val first = FortressProfile(id = "f1", name = "Alpha", sortOrder = 5, durationMinutes = 90)
        val initial = FortressesConfig.default()
            .upsert(second)
            .upsert(first)

        val afterEmergencyDelete = initial.deleteCustom(FortressProfile.EMERGENCY_FORTRESS_ID)
        assertEquals(FortressProfile.EMERGENCY_FORTRESS_ID, afterEmergencyDelete.getEmergencyFortress().id)
        assertEquals(listOf("f1", "f2"), afterEmergencyDelete.getCustomFortresses().map { it.id })

        val updatedFirst = first.copy(durationMinutes = 120, isInternetCutEnabled = false)
        val afterUpsert = afterEmergencyDelete.upsert(updatedFirst)
        assertEquals(listOf("f1", "f2"), afterUpsert.getCustomFortresses().map { it.id })
        assertEquals(120, afterUpsert.findById("f1")?.durationMinutes)
        assertFalse(afterUpsert.findById("f1")?.isInternetCutEnabled ?: true)
        assertEquals(30, afterUpsert.findById("f2")?.durationMinutes)

        val withGeneratedId = afterUpsert.upsert(FortressProfile(id = "", name = "Gamma", sortOrder = 10))
        assertEquals(3, withGeneratedId.getCustomFortresses().size)
        assertEquals(listOf("Alpha", "Gamma", "Beta"), withGeneratedId.getCustomFortresses().map { it.name })
    }

    @Test
    fun devicePolicyBundle_roundtripsFortressFieldsAndExcludesWardActiveTelemetryFromToMap() {
        val config = FortressesConfig.default()
            .upsert(
                FortressProfile(
                    id = FortressProfile.EMERGENCY_FORTRESS_ID,
                    name = "Emergency",
                    durationMinutes = 90,
                    isEmergency = true,
                    allowedPackages = setOf("com.whatsapp")
                )
            )
            .upsert(
                FortressProfile(
                    id = "deep_work",
                    name = "Deep Work",
                    durationMinutes = 50,
                    isEmergency = false,
                    isInternetCutEnabled = true,
                    allowedPackages = setOf("com.whatsapp", "com.xodo.pdf.reader"),
                    vpnBypassPackages = setOf("com.whatsapp"),
                    sortOrder = 1
                )
            )

        val original = DevicePolicyBundle.defaultPolicy().copy(
            fortressesConfig = config,
            selfControlFortressesAllowed = false,
            selfControlFortressUnlockAllowed = true,
            remoteModeUnlockTarget = DevicePolicyBundle.REMOTE_MODE_UNLOCK_FORTRESS,
            remoteModeUnlockRequestedAt = 1_790_100_000_000L,
            wardActiveFortressId = "deep_work",
            wardActiveFortressName = "Deep Work",
            wardActiveFortressIsEmergency = false,
            wardActiveFortressEndsAtWall = 1_790_103_000_000L,
            wardIsCurfewActiveNow = true,
            wardActiveModesUpdatedAt = 1_790_100_500_000L
        )

        val map = original.toMap()
        assertFalse(map.containsKey("wardActiveFortressId"))
        assertFalse(map.containsKey("wardActiveFortressName"))
        assertFalse(map.containsKey("wardActiveFortressIsEmergency"))
        assertFalse(map.containsKey("wardActiveFortressEndsAtWall"))
        assertFalse(map.containsKey("wardIsCurfewActiveNow"))
        assertFalse(map.containsKey("wardActiveModesUpdatedAt"))

        val fromMap = DevicePolicyBundle.fromMap(map)
        assertEquals(original.fortressesConfig.getAllOrdered(), fromMap.fortressesConfig.getAllOrdered())
        assertEquals(original.selfControlFortressesAllowed, fromMap.selfControlFortressesAllowed)
        assertEquals(original.selfControlFortressUnlockAllowed, fromMap.selfControlFortressUnlockAllowed)
        assertEquals(original.remoteModeUnlockTarget, fromMap.remoteModeUnlockTarget)
        assertEquals(original.remoteModeUnlockRequestedAt, fromMap.remoteModeUnlockRequestedAt)

        val snapshotFromMap = DevicePolicyBundle.fromMap(
            map + mapOf(
                "wardActiveFortressId" to "deep_work",
                "wardActiveFortressName" to "Deep Work",
                "wardActiveFortressIsEmergency" to false,
                "wardActiveFortressEndsAtWall" to 1_790_103_000_000L,
                "wardIsCurfewActiveNow" to true,
                "wardActiveModesUpdatedAt" to 1_790_100_500_000L
            )
        )
        assertEquals("deep_work", snapshotFromMap.wardActiveFortressId)
        assertTrue(snapshotFromMap.wardIsCurfewActiveNow)

        val fromJson = DevicePolicyBundle.fromJsonOrNull(original.toJson())
        assertNotNull(fromJson)
        assertEquals(original.fortressesConfig.getAllOrdered(), fromJson?.fortressesConfig?.getAllOrdered())
        assertEquals(original.selfControlFortressesAllowed, fromJson?.selfControlFortressesAllowed)
        assertEquals(original.selfControlFortressUnlockAllowed, fromJson?.selfControlFortressUnlockAllowed)
        assertEquals(original.remoteModeUnlockTarget, fromJson?.remoteModeUnlockTarget)
        assertEquals(original.remoteModeUnlockRequestedAt, fromJson?.remoteModeUnlockRequestedAt)
        assertEquals("deep_work", fromJson?.wardActiveFortressId)
        assertTrue(fromJson?.wardIsCurfewActiveNow ?: false)
    }

    @Test
    fun fortressProfile_clampsExcessiveDurationAcrossNormalizedFromMapAndFromJson() {
        val excessive = FortressProfile(id = "overflow", name = "Overflow", durationMinutes = Int.MAX_VALUE)
        assertEquals(FortressProfile.MAX_DURATION_MINUTES, excessive.normalized().durationMinutes)

        val fromMap = FortressProfile.fromMap(
            mapOf("id" to "overflow_map", "name" to "Map", "durationMinutes" to 999_999)
        )
        assertEquals(FortressProfile.MAX_DURATION_MINUTES, fromMap?.durationMinutes)

        val fromJson = FortressProfile.fromJson(
            org.json.JSONObject().apply {
                put("id", "overflow_json")
                put("name", "Json")
                put("durationMinutes", 999_999)
            }
        )
        assertEquals(FortressProfile.MAX_DURATION_MINUTES, fromJson?.durationMinutes)
    }

    @Test
    fun fortressProfileFromJson_ignoresJsonNullInsteadOfLiteralNullString() {
        val nullIdJson = org.json.JSONObject().apply {
            put("id", org.json.JSONObject.NULL)
            put("isEmergency", false)
            put("name", "Phantom")
        }
        assertEquals(null, FortressProfile.fromJson(nullIdJson))

        val nullNameEmergency = org.json.JSONObject().apply {
            put("id", FortressProfile.EMERGENCY_FORTRESS_ID)
            put("isEmergency", true)
            put("name", org.json.JSONObject.NULL)
        }
        val parsed = FortressProfile.fromJson(nullNameEmergency)
        assertNotNull(parsed)
        assertEquals("", parsed?.name)
    }
}
