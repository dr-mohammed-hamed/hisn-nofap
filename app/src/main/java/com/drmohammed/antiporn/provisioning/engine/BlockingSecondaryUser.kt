package com.drmohammed.antiporn.provisioning.engine

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

enum class BlockingUserCategory {
    SECURE_FOLDER,
    DUAL_APP_CLONE,
    PRIVATE_SPACE,
    WORK_PROFILE,
    GUEST_USER,
    SECONDARY_USER
}

data class BlockingSecondaryUser(
    val userId: Int,
    val rawName: String,
    val flagsHex: String,
    val category: BlockingUserCategory,
    val installedAppNames: List<String>,
    val installedPackageNames: List<String>,
    val isAppsInspectionBlocked: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("userId", userId)
        put("rawName", rawName)
        put("flagsHex", flagsHex)
        put("category", category.name)
        put("installedAppNames", JSONArray(installedAppNames))
        put("installedPackageNames", JSONArray(installedPackageNames))
        put("isAppsInspectionBlocked", isAppsInspectionBlocked)
    }

    companion object {
        private const val TAG = "AntiPorn_BlkUserModel"

        private fun optNonNullString(obj: JSONObject, key: String): String {
            if (!obj.has(key) || obj.isNull(key)) return ""
            return obj.optString(key, "").takeUnless { it == "null" } ?: ""
        }

        private fun optStringList(obj: JSONObject, key: String): List<String> {
            val arr = obj.optJSONArray(key) ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    if (arr.isNull(i)) continue
                    val value = arr.optString(i, "")
                    if (value.isNotBlank() && value != "null") {
                        add(value)
                    }
                }
            }
        }

        fun fromJson(obj: JSONObject): BlockingSecondaryUser? {
            val userId = obj.optInt("userId", -1)
            if (userId <= 0) return null
            val categoryName = optNonNullString(obj, "category")
            val category = runCatching { BlockingUserCategory.valueOf(categoryName) }.getOrNull() ?: return null
            return BlockingSecondaryUser(
                userId = userId,
                rawName = optNonNullString(obj, "rawName"),
                flagsHex = optNonNullString(obj, "flagsHex"),
                category = category,
                installedAppNames = optStringList(obj, "installedAppNames"),
                installedPackageNames = optStringList(obj, "installedPackageNames"),
                isAppsInspectionBlocked = obj.optBoolean("isAppsInspectionBlocked", false)
            )
        }

        fun listToJson(users: List<BlockingSecondaryUser>): String {
            val arr = JSONArray()
            users.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(jsonStr: String?): List<BlockingSecondaryUser> {
            if (jsonStr.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(jsonStr)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        fromJson(obj)?.let { add(it) }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed deserializing BlockingSecondaryUser list JSON")
                emptyList()
            }
        }
    }
}

data class ParsedAdbUser(
    val userId: Int,
    val rawName: String,
    val flagsHex: String
)

data class SecondaryUserRemovalOutcome(
    val removedIds: List<Int>,
    val failedIds: List<Int>,
    val remainingUsers: List<BlockingSecondaryUser>,
    val failureMessages: Map<Int, String>
)
