package com.drmohammed.antiporn.waqf.model

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Top-level configuration model for the Waqf Support module (`waqf_config.json`).
 *
 * Designed for resilient forward-compatible parsing so older app versions safely ignore
 * unknown fields, channel types, or action types without crashing.
 */
data class WaqfConfig(
    val schemaVersion: Int = 1,
    val updatedAt: String? = null,
    val hero: WaqfHeroContent? = null,
    val channels: List<WaqfChannel> = emptyList()
) {
    /**
     * Returns enabled channels sorted by [WaqfChannel.sortOrder] ascending.
     */
    fun activeChannels(): List<WaqfChannel> =
        channels.filter { it.enabled }.sortedBy { it.sortOrder }

    companion object {
        /**
         * Parses a [WaqfConfig] from raw JSON string.
         *
         * Gracefully handles nulls, missing optional keys, unknown enum wire names,
         * and malformed channel entries (skipping entries whose title is blank or non-object).
         *
         * @throws org.json.JSONException if [jsonStr] is not valid JSON.
         * @throws IllegalArgumentException if [jsonStr] is blank or missing expected root keys.
         */
        fun fromJson(jsonStr: String): WaqfConfig {
            require(jsonStr.isNotBlank()) { "Waqf config JSON must not be blank" }
            val json = JSONObject(jsonStr)
            require(json.has("channels") || json.has("hero")) {
                "Waqf config JSON missing required 'channels' or 'hero' section"
            }

            val schemaVersion = if (json.isNull("schemaVersion")) {
                1
            } else {
                json.optInt("schemaVersion", 1).takeIf { it > 0 } ?: 1
            }
            val updatedAt = json.optNullableString("updatedAt")
            val hero = json.optJSONObject("hero")?.let { parseHero(it) }
            val channels = parseChannels(json.optJSONArray("channels"))

            return WaqfConfig(
                schemaVersion = schemaVersion,
                updatedAt = updatedAt,
                hero = hero,
                channels = channels
            )
        }

        private fun parseHero(json: JSONObject): WaqfHeroContent = WaqfHeroContent(
            badge = json.optNullableString("badge"),
            hadith = json.optNullableString("hadith"),
            title = json.optNullableString("title"),
            body = json.optNullableString("body"),
            notice = json.optNullableString("notice")
        )

        private fun parseChannels(array: JSONArray?): List<WaqfChannel> {
            if (array == null || array.length() == 0) return emptyList()
            val result = ArrayList<WaqfChannel>(array.length())
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val title = obj.optNullableString("title") ?: continue
                val id = obj.optNullableString("id") ?: "channel_$index"
                val enabled = if (obj.isNull("enabled")) true else obj.optBoolean("enabled", true)
                val sortOrder = if (obj.isNull("sortOrder")) index else obj.optInt("sortOrder", index)
                val type = WaqfChannelType.fromWire(obj.optNullableString("type"))
                val accentStyle = WaqfAccentStyle.fromWire(obj.optNullableString("accentStyle"))
                val requiresBankingCloak = if (obj.isNull("requiresBankingCloak")) {
                    false
                } else {
                    obj.optBoolean("requiresBankingCloak", false)
                }

                result.add(
                    WaqfChannel(
                        id = id,
                        enabled = enabled,
                        sortOrder = sortOrder,
                        type = type,
                        badge = obj.optNullableString("badge"),
                        title = title,
                        subtitle = obj.optNullableString("subtitle"),
                        displayValue = obj.optNullableString("displayValue"),
                        instructions = obj.optNullableString("instructions"),
                        accentStyle = accentStyle,
                        requiresBankingCloak = requiresBankingCloak,
                        targetPackage = obj.optNullableString("targetPackage"),
                        primaryAction = parseAction(obj.optJSONObject("primaryAction")),
                        secondaryAction = parseAction(obj.optJSONObject("secondaryAction"))
                    )
                )
            }
            return result
        }

        private fun parseAction(json: JSONObject?): WaqfChannelAction? {
            if (json == null) return null
            val label = json.optNullableString("label") ?: return null
            val target = json.optNullableString("target") ?: return null
            val type = WaqfActionType.fromWire(json.optNullableString("type"))
            return WaqfChannelAction(
                label = label,
                type = type,
                target = target
            )
        }

        private fun JSONObject.optNullableString(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val value = optString(key)
            return if (value.isBlank() || value.equals("null", ignoreCase = true)) {
                null
            } else {
                value.trim()
            }
        }
    }
}

/**
 * Editorial/motivational header content displayed at the top of the Waqf Support screen.
 */
data class WaqfHeroContent(
    val badge: String? = null,
    val hadith: String? = null,
    val title: String? = null,
    val body: String? = null,
    val notice: String? = null
)

/**
 * A single Waqf support/donation channel configured remotely or via offline fallback.
 */
data class WaqfChannel(
    val id: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val type: WaqfChannelType = WaqfChannelType.OTHER,
    val badge: String? = null,
    val title: String,
    val subtitle: String? = null,
    val displayValue: String? = null,
    val instructions: String? = null,
    val accentStyle: WaqfAccentStyle = WaqfAccentStyle.EMERALD,
    val requiresBankingCloak: Boolean = false,
    val targetPackage: String? = null,
    val primaryAction: WaqfChannelAction? = null,
    val secondaryAction: WaqfChannelAction? = null
) {
    /**
     * Returns a copy whose [primaryAction] and [secondaryAction] have been validated through
     * [WaqfChannelAction.effectiveAction] so unsafe URLs, malformed USSD strings, or unknown
     * action types fail safely to `null`.
     */
    fun sanitizeChannelActions(): WaqfChannel = copy(
        primaryAction = primaryAction?.effectiveAction(),
        secondaryAction = secondaryAction?.effectiveAction()
    )

    fun effectivePrimaryAction(): WaqfChannelAction? = primaryAction?.effectiveAction()

    fun effectiveSecondaryAction(): WaqfChannelAction? = secondaryAction?.effectiveAction()
}

/**
 * Action button descriptor associated with a [WaqfChannel].
 */
data class WaqfChannelAction(
    val label: String,
    val type: WaqfActionType,
    val target: String
) {
    /**
     * Returns a validated, trimmed copy of this action if it is safe to execute,
     * or `null` if the action type is [WaqfActionType.UNKNOWN] or its target fails validation.
     */
    fun effectiveAction(): WaqfChannelAction? = WaqfActionValidator.effectiveAction(this)

    fun isValid(): Boolean = effectiveAction() != null
}

/**
 * Security validator for remote Waqf channel actions.
 */
object WaqfActionValidator {

    private val USSD_REGEX = Regex("^[0-9*#]+$")

    /**
     * Trims [url] and verifies it is a well-formed `https://` URI with a non-blank host.
     * Rejects `http://`, `javascript:`, `file:`, `intent:`, and malformed URIs.
     */
    fun isSafeHttpsUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val uri = URI(trimmed)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Trims [ussd] and verifies it contains only `[0-9*#]` and includes `*` or `#`.
     */
    fun isValidUssd(ussd: String): Boolean {
        val trimmed = ussd.trim()
        if (trimmed.isEmpty()) return false
        if (!USSD_REGEX.matches(trimmed)) return false
        return trimmed.contains('*') || trimmed.contains('#')
    }

    /**
     * Validates and normalizes [action], returning `null` if it is unsafe or unknown.
     */
    fun effectiveAction(action: WaqfChannelAction?): WaqfChannelAction? {
        if (action == null) return null
        val trimmedLabel = action.label.trim()
        val trimmedTarget = action.target.trim()
        if (trimmedLabel.isEmpty() || trimmedTarget.isEmpty()) return null

        return when (action.type) {
            WaqfActionType.OPEN_URL -> {
                if (isSafeHttpsUrl(trimmedTarget)) {
                    action.copy(label = trimmedLabel, target = trimmedTarget)
                } else {
                    null
                }
            }
            WaqfActionType.DIAL_USSD -> {
                if (isValidUssd(trimmedTarget)) {
                    action.copy(label = trimmedLabel, target = trimmedTarget)
                } else {
                    null
                }
            }
            WaqfActionType.COPY_VALUE -> {
                action.copy(label = trimmedLabel, target = trimmedTarget)
            }
            WaqfActionType.UNKNOWN -> null
        }
    }
}

enum class WaqfChannelType {
    INSTAPAY,
    MOBILE_WALLET,
    WEB_LINK,
    BANK_ACCOUNT,
    COPY_ONLY,
    OTHER;

    companion object {
        fun fromWire(value: String?): WaqfChannelType {
            val normalized = value?.trim()?.uppercase() ?: return OTHER
            return entries.firstOrNull { it.name == normalized } ?: OTHER
        }
    }
}

enum class WaqfActionType {
    OPEN_URL,
    DIAL_USSD,
    COPY_VALUE,
    UNKNOWN;

    companion object {
        fun fromWire(value: String?): WaqfActionType {
            val normalized = value?.trim()?.uppercase() ?: return UNKNOWN
            return entries.firstOrNull { it.name == normalized } ?: UNKNOWN
        }
    }
}

enum class WaqfAccentStyle {
    EMERALD,
    TEAL,
    CRIMSON,
    BLUE,
    AMBER;

    companion object {
        fun fromWire(value: String?): WaqfAccentStyle {
            val normalized = value?.trim()?.uppercase() ?: return EMERALD
            return entries.firstOrNull { it.name == normalized } ?: EMERALD
        }
    }
}

sealed interface WaqfLoadSource {
    data object Cache : WaqfLoadSource
    data object Network : WaqfLoadSource
    data object ColdFallback : WaqfLoadSource
}

data class WaqfSnapshot(
    val config: WaqfConfig,
    val source: WaqfLoadSource,
    val isRefreshing: Boolean = false,
    val lastError: String? = null
)
