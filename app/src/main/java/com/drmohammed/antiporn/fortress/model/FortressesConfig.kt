package com.drmohammed.antiporn.fortress.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Container holding all configured Fortress profiles (mandatory Emergency Fortress + custom fortresses).
 * Guarantees that an up-to-date normalized Emergency Fortress is always present.
 */
data class FortressesConfig(
    val profiles: List<FortressProfile> = listOf(FortressProfile.defaultEmergency())
) {
    /**
     * Returns the mandatory Emergency Fortress profile, falling back to a default instance if absent.
     */
    fun getEmergencyFortress(): FortressProfile {
        return profiles.firstOrNull {
            it.isEmergency || it.id.trim() == FortressProfile.EMERGENCY_FORTRESS_ID
        }?.normalized() ?: FortressProfile.defaultEmergency()
    }

    /**
     * Returns all non-emergency custom fortresses sorted deterministically by [FortressProfile.sortOrder] and [FortressProfile.id].
     */
    fun getCustomFortresses(): List<FortressProfile> {
        return profiles
            .asSequence()
            .filterNot { it.isEmergency || it.id.trim() == FortressProfile.EMERGENCY_FORTRESS_ID }
            .map { it.normalized() }
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .sortedWith(compareBy<FortressProfile> { it.sortOrder }.thenBy { it.id })
            .toList()
    }

    /**
     * Returns all profiles ordered with the Emergency Fortress first, followed by sorted custom fortresses.
     */
    fun getAllOrdered(): List<FortressProfile> {
        return listOf(getEmergencyFortress()) + getCustomFortresses()
    }

    /**
     * Finds a normalized profile by its [id], or returns null if not found.
     */
    fun findById(id: String): FortressProfile? {
        val cleanId = id.trim()
        if (cleanId.isBlank()) return null
        return getAllOrdered().firstOrNull { it.id == cleanId }
    }

    /**
     * Returns a normalized copy guaranteeing a single Emergency Fortress and deduplicated custom profiles.
     */
    fun normalized(): FortressesConfig = FortressesConfig(profiles = getAllOrdered())

    /**
     * Inserts or updates [profile], enforcing emergency invariants, assigning a UUID if custom [profile.id]
     * is blank, and preventing duplicate emergency entries.
     */
    fun upsert(profile: FortressProfile): FortressesConfig {
        val isEmergencyTarget = profile.isEmergency || profile.id.trim() == FortressProfile.EMERGENCY_FORTRESS_ID
        if (isEmergencyTarget) {
            val normalizedEmergency = profile.copy(
                id = FortressProfile.EMERGENCY_FORTRESS_ID,
                isEmergency = true
            ).normalized()
            return FortressesConfig(profiles = listOf(normalizedEmergency) + getCustomFortresses())
        }

        val resolvedId = profile.id.trim().ifBlank { UUID.randomUUID().toString() }
        val normalizedCustom = profile.copy(
            id = resolvedId,
            isEmergency = false
        ).normalized()

        val updatedCustoms = getCustomFortresses()
            .filterNot { it.id == resolvedId }
            .plus(normalizedCustom)

        return FortressesConfig(profiles = listOf(getEmergencyFortress()) + updatedCustoms).normalized()
    }

    /**
     * Deletes a custom fortress by [id]. Refuses to delete the mandatory Emergency Fortress.
     */
    fun deleteCustom(id: String): FortressesConfig {
        val cleanId = id.trim()
        if (cleanId.isBlank() || cleanId == FortressProfile.EMERGENCY_FORTRESS_ID) {
            return normalized()
        }
        val remainingCustoms = getCustomFortresses().filterNot { it.id == cleanId }
        return FortressesConfig(profiles = listOf(getEmergencyFortress()) + remainingCustoms)
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "profiles" to getAllOrdered().map { it.toMap() }
    )

    fun toJson(): JSONObject = JSONObject().apply {
        val arr = JSONArray()
        getAllOrdered().forEach { arr.put(it.toJson()) }
        put("profiles", arr)
    }

    companion object {
        fun default(): FortressesConfig = FortressesConfig()

        fun fromMap(map: Any?): FortressesConfig {
            val typedMap = map as? Map<*, *> ?: return default()
            val rawList = typedMap["profiles"] as? List<*> ?: return default()
            val parsed = rawList.mapNotNull { FortressProfile.fromMap(it) }
            if (parsed.isEmpty()) return default()
            return FortressesConfig(profiles = parsed).normalized()
        }

        fun fromJson(json: JSONObject?): FortressesConfig {
            if (json == null) return default()
            val arr = json.optJSONArray("profiles") ?: return default()
            val parsed = mutableListOf<FortressProfile>()
            for (i in 0 until arr.length()) {
                FortressProfile.fromJson(arr.optJSONObject(i))?.let { parsed.add(it) }
            }
            if (parsed.isEmpty()) return default()
            return FortressesConfig(profiles = parsed).normalized()
        }
    }
}
