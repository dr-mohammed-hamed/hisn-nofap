package com.drmohammed.antiporn.pageshield.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * ردود الفعل المتاحة عند دخول صفحة محظورة بواسطة درع الصفحات.
 */
enum class PageShieldReaction {
    EXIT_ONLY,
    EXIT_AND_FREEZE;

    companion object {
        fun fromString(name: String?): PageShieldReaction {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: EXIT_ONLY
        }
    }
}

/**
 * سياسة درع الصفحات لتطبيق معين.
 *
 * @param packageName اسم حزمة التطبيق المستهدف.
 * @param enabledPageIds مجموعة المعرفات الخاصة بالصفحات المحجوبة داخل هذا التطبيق.
 * @param reaction رد الفعل المطبق عند دخول أي صفحة محجوبة.
 * @param freezeDurationMinutes مدة تجميد التطبيق بالدقائق في حال كان رد الفعل EXIT_AND_FREEZE (بين 1 و 1440).
 */
data class AppPageShieldPolicy(
    val packageName: String,
    val enabledPageIds: Set<String> = emptySet(),
    val reaction: PageShieldReaction = PageShieldReaction.EXIT_ONLY,
    val freezeDurationMinutes: Int = DEFAULT_FREEZE_MINUTES
) {
    init {
        require(freezeDurationMinutes in MIN_FREEZE_MINUTES..MAX_FREEZE_MINUTES) {
            "freezeDurationMinutes must be between $MIN_FREEZE_MINUTES and $MAX_FREEZE_MINUTES"
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "packageName" to packageName,
        "enabledPageIds" to enabledPageIds.toList(),
        "reaction" to reaction.name,
        "freezeDurationMinutes" to freezeDurationMinutes
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("enabledPageIds", JSONArray(enabledPageIds))
        put("reaction", reaction.name)
        put("freezeDurationMinutes", freezeDurationMinutes)
    }

    companion object {
        const val DEFAULT_FREEZE_MINUTES = 15
        const val MIN_FREEZE_MINUTES = 1
        const val MAX_FREEZE_MINUTES = 1440

        fun fromMap(map: Any?): AppPageShieldPolicy? {
            val typedMap = map as? Map<*, *> ?: return null
            val pkg = typedMap["packageName"] as? String ?: return null
            if (pkg.isBlank()) return null

            val pages = (typedMap["enabledPageIds"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.filter { it.isNotBlank() }
                ?.toSet() ?: emptySet()

            val reactionStr = typedMap["reaction"] as? String
            val reaction = PageShieldReaction.fromString(reactionStr)

            val rawMins = (typedMap["freezeDurationMinutes"] as? Number)?.toInt() ?: DEFAULT_FREEZE_MINUTES
            val mins = rawMins.coerceIn(MIN_FREEZE_MINUTES, MAX_FREEZE_MINUTES)

            return AppPageShieldPolicy(
                packageName = pkg,
                enabledPageIds = pages,
                reaction = reaction,
                freezeDurationMinutes = mins
            )
        }

        fun fromJson(json: JSONObject?): AppPageShieldPolicy? {
            if (json == null) return null
            val pkg = json.optString("packageName")
            if (pkg.isNullOrBlank()) return null

            val pages = mutableSetOf<String>()
            json.optJSONArray("enabledPageIds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i)
                    if (!p.isNullOrBlank()) pages.add(p)
                }
            }

            val reactionStr = json.optString("reaction", PageShieldReaction.EXIT_ONLY.name)
            val reaction = PageShieldReaction.fromString(reactionStr)

            val rawMins = json.optInt("freezeDurationMinutes", DEFAULT_FREEZE_MINUTES)
            val mins = rawMins.coerceIn(MIN_FREEZE_MINUTES, MAX_FREEZE_MINUTES)

            return AppPageShieldPolicy(
                packageName = pkg,
                enabledPageIds = pages,
                reaction = reaction,
                freezeDurationMinutes = mins
            )
        }
    }
}

/**
 * حزمة إعدادات «درع الصفحات» الشاملة المدمجة ضمن [com.drmohammed.antiporn.core.model.DevicePolicyBundle].
 */
data class PageShieldConfig(
    val isEnabled: Boolean = false,
    val appPolicies: Map<String, AppPageShieldPolicy> = emptyMap()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "isEnabled" to isEnabled,
        "appPolicies" to appPolicies.mapValues { it.value.toMap() }
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("isEnabled", isEnabled)
        val policiesObj = JSONObject()
        for ((pkg, policy) in appPolicies) {
            policiesObj.put(pkg, policy.toJson())
        }
        put("appPolicies", policiesObj)
    }

    /**
     * هل الدرع مفعل ولديه صفحات محجوبة للتطبيق المحدد.
     */
    fun hasActiveRulesForPackage(packageName: String): Boolean {
        if (!isEnabled) return false
        val policy = appPolicies[packageName] ?: return false
        return policy.enabledPageIds.isNotEmpty()
    }

    /**
     * إرجاع قائمة كافة الحزم التي تملك صفحات محجوبة حالياً.
     */
    fun getTargetPackages(): Set<String> {
        if (!isEnabled) return emptySet()
        return appPolicies.filter { it.value.enabledPageIds.isNotEmpty() }.keys
    }

    companion object {
        fun fromMap(map: Any?): PageShieldConfig {
            val typedMap = map as? Map<*, *> ?: return PageShieldConfig()
            val isEnabled = typedMap["isEnabled"] as? Boolean ?: false

            val policiesMap = mutableMapOf<String, AppPageShieldPolicy>()
            val rawPolicies = typedMap["appPolicies"] as? Map<*, *>
            if (rawPolicies != null) {
                for ((k, v) in rawPolicies) {
                    val pkg = k as? String ?: continue
                    val policy = AppPageShieldPolicy.fromMap(v)
                    if (policy != null) {
                        policiesMap[pkg] = policy
                    }
                }
            }

            return PageShieldConfig(
                isEnabled = isEnabled,
                appPolicies = policiesMap
            )
        }

        fun fromJson(json: JSONObject?): PageShieldConfig {
            if (json == null) return PageShieldConfig()
            val isEnabled = json.optBoolean("isEnabled", false)

            val policiesMap = mutableMapOf<String, AppPageShieldPolicy>()
            val rawPolicies = json.optJSONObject("appPolicies")
            if (rawPolicies != null) {
                val keys = rawPolicies.keys()
                while (keys.hasNext()) {
                    val pkg = keys.next()
                    val pJson = rawPolicies.optJSONObject(pkg)
                    val policy = AppPageShieldPolicy.fromJson(pJson)
                    if (policy != null) {
                        policiesMap[pkg] = policy
                    }
                }
            }

            return PageShieldConfig(
                isEnabled = isEnabled,
                appPolicies = policiesMap
            )
        }
    }
}
