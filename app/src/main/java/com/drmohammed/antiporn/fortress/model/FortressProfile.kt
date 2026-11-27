package com.drmohammed.antiporn.fortress.model

import android.content.Context
import com.drmohammed.antiporn.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable domain model representing a single Focus/Protection Fortress profile.
 * Enforces hard security invariants for the mandatory Emergency Fortress profile.
 */
data class FortressProfile(
    val id: String,
    val name: String = "",
    val durationMinutes: Int = DEFAULT_EMERGENCY_DURATION_MINUTES,
    val isEmergency: Boolean = false,
    val isInternetCutEnabled: Boolean = true,
    val allowedPackages: Set<String> = emptySet(),
    val vpnBypassPackages: Set<String> = emptySet(),
    val sortOrder: Int = 0
) {
    /**
     * Returns a normalized copy enforcing all Fortress security invariants:
     * - Emergency fortress always forces id=[EMERGENCY_FORTRESS_ID], [isInternetCutEnabled]=true,
     *   [vpnBypassPackages]=emptySet(), and [sortOrder]=0.
     * - Custom fortresses drop any [vpnBypassPackages] when internet cut is disabled, and otherwise
     *   restrict bypass packages to a subset of [allowedPackages].
     */
    fun normalized(): FortressProfile {
        val trimmedId = id.trim()
        val effectiveEmergency = isEmergency || trimmedId == EMERGENCY_FORTRESS_ID
        val trimmedName = name.trim()
        val coercedDuration = durationMinutes.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
        val cleanAllowed = allowedPackages.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        if (effectiveEmergency) {
            return copy(
                id = EMERGENCY_FORTRESS_ID,
                name = trimmedName,
                durationMinutes = coercedDuration,
                isEmergency = true,
                isInternetCutEnabled = true,
                allowedPackages = cleanAllowed,
                vpnBypassPackages = emptySet(),
                sortOrder = 0
            )
        }

        val cleanBypass = if (!isInternetCutEnabled) {
            emptySet()
        } else {
            vpnBypassPackages.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && cleanAllowed.contains(it) }
                .toSet()
        }

        return copy(
            id = trimmedId,
            name = trimmedName,
            durationMinutes = coercedDuration,
            isEmergency = false,
            isInternetCutEnabled = isInternetCutEnabled,
            allowedPackages = cleanAllowed,
            vpnBypassPackages = cleanBypass,
            sortOrder = sortOrder
        )
    }

    /**
     * Resolves a localized display name suitable for UI, notifications, and telemetry.
     */
    fun resolveDisplayName(context: Context): String {
        val trimmed = name.trim()
        if (trimmed.isNotBlank()) return trimmed
        return if (isEmergency || id == EMERGENCY_FORTRESS_ID) {
            context.getString(R.string.fortress_emergency_default_name)
        } else {
            id
        }
    }

    fun toMap(): Map<String, Any?> {
        val norm = normalized()
        return mapOf(
            "id" to norm.id,
            "name" to norm.name,
            "durationMinutes" to norm.durationMinutes,
            "isEmergency" to norm.isEmergency,
            "isInternetCutEnabled" to norm.isInternetCutEnabled,
            "allowedPackages" to norm.allowedPackages.toList(),
            "vpnBypassPackages" to norm.vpnBypassPackages.toList(),
            "sortOrder" to norm.sortOrder
        )
    }

    fun toJson(): JSONObject {
        val norm = normalized()
        return JSONObject().apply {
            put("id", norm.id)
            put("name", norm.name)
            put("durationMinutes", norm.durationMinutes)
            put("isEmergency", norm.isEmergency)
            put("isInternetCutEnabled", norm.isInternetCutEnabled)
            put("allowedPackages", JSONArray().apply { norm.allowedPackages.forEach { put(it) } })
            put("vpnBypassPackages", JSONArray().apply { norm.vpnBypassPackages.forEach { put(it) } })
            put("sortOrder", norm.sortOrder)
        }
    }

    companion object {
        const val EMERGENCY_FORTRESS_ID = "fortress_emergency"
        const val DEFAULT_EMERGENCY_DURATION_MINUTES = 60
        const val MIN_DURATION_MINUTES = 1
        const val MAX_DURATION_MINUTES = 720
        val PRESET_DURATIONS_MINUTES: List<Int> = listOf(30, 60, 120)

        fun defaultEmergency(): FortressProfile = FortressProfile(
            id = EMERGENCY_FORTRESS_ID,
            name = "",
            durationMinutes = DEFAULT_EMERGENCY_DURATION_MINUTES,
            isEmergency = true,
            isInternetCutEnabled = true,
            allowedPackages = emptySet(),
            vpnBypassPackages = emptySet(),
            sortOrder = 0
        )

        fun fromMap(map: Any?): FortressProfile? {
            val typedMap = map as? Map<*, *> ?: return null
            val isEmergency = (typedMap["isEmergency"] as? Boolean) ?: false
            val rawId = (typedMap["id"] as? String)?.trim().orEmpty()
            val id = if (isEmergency) EMERGENCY_FORTRESS_ID else rawId
            if (id.isBlank()) return null

            val name = (typedMap["name"] as? String)?.trim().orEmpty()
            val durationMinutes = ((typedMap["durationMinutes"] as? Number)?.toInt()
                ?: DEFAULT_EMERGENCY_DURATION_MINUTES).coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
            val isInternetCut = (typedMap["isInternetCutEnabled"] as? Boolean) ?: true
            val allowed = (typedMap["allowedPackages"] as? List<*>)
                ?.filterIsInstance<String>()
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.toSet() ?: emptySet()
            val bypass = (typedMap["vpnBypassPackages"] as? List<*>)
                ?.filterIsInstance<String>()
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.toSet() ?: emptySet()
            val sortOrder = (typedMap["sortOrder"] as? Number)?.toInt() ?: 0

            return FortressProfile(
                id = id,
                name = name,
                durationMinutes = durationMinutes,
                isEmergency = isEmergency,
                isInternetCutEnabled = isInternetCut,
                allowedPackages = allowed,
                vpnBypassPackages = bypass,
                sortOrder = sortOrder
            ).normalized()
        }

        fun fromJson(json: JSONObject?): FortressProfile? {
            if (json == null) return null
            val isEmergency = json.optBoolean("isEmergency", false)
            val rawId = json.optCleanString("id")
            val id = if (isEmergency) EMERGENCY_FORTRESS_ID else rawId
            if (id.isBlank()) return null

            val name = json.optCleanString("name")
            val durationMinutes = json.optInt("durationMinutes", DEFAULT_EMERGENCY_DURATION_MINUTES)
                .coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
            val isInternetCut = json.optBoolean("isInternetCutEnabled", true)
            val allowed = json.optJSONArray("allowedPackages").toTrimmedStringSet()
            val bypass = json.optJSONArray("vpnBypassPackages").toTrimmedStringSet()
            val sortOrder = json.optInt("sortOrder", 0)

            return FortressProfile(
                id = id,
                name = name,
                durationMinutes = durationMinutes,
                isEmergency = isEmergency,
                isInternetCutEnabled = isInternetCut,
                allowedPackages = allowed,
                vpnBypassPackages = bypass,
                sortOrder = sortOrder
            ).normalized()
        }

        private fun JSONObject.optCleanString(key: String): String =
            if (has(key) && !isNull(key)) optString(key, "").trim().takeUnless { it == "null" }.orEmpty() else ""

        private fun JSONArray?.toTrimmedStringSet(): Set<String> {
            if (this == null) return emptySet()
            val result = mutableSetOf<String>()
            for (i in 0 until length()) {
                val value = optString(i, "").trim()
                if (value.isNotEmpty() && value != "null") {
                    result.add(value)
                }
            }
            return result
        }
    }
}
