package com.soltini.app.plugins

import org.json.JSONObject

/**
 * RiskLevel
 *
 * Categorizes the operation sensitivity of a plugin.
 */
enum class RiskLevel {
    LOW,       // Read-only, weather, search, information
    MEDIUM,    // Device state change (torch, volume, open app, media)
    HIGH       // Messaging (WhatsApp, Instagram), phone calls, screen clicks, form submission
}

/**
 * PluginCategory
 *
 * Semantic categories for dynamic discovery and capability matching.
 */
enum class PluginCategory {
    INFORMATION,
    ANDROID_DEVICE,
    AUTOMATION,
    MESSAGING,
    SOCIAL,
    MEDIA,
    VISION,
    WEB,
    DEVELOPER,
    EXTERNAL_API,
    AGENT,
    CUSTOM
}

/**
 * PluginStatus
 */
enum class PluginStatus {
    AVAILABLE,
    BUSY,
    DISABLED,
    ERROR
}

/**
 * PluginMetadata
 *
 * Rich structured metadata describing a capability in the Plugin & Tool Registry.
 */
data class PluginMetadata(
    val id: String,
    val name: String,
    val description: String,
    val category: PluginCategory,
    val tags: List<String> = emptyList(),
    val inputParameters: JSONObject = JSONObject(),
    val outputSchema: JSONObject = JSONObject(),
    val permissions: List<String> = emptyList(),
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val fallbackPluginId: String? = null,
    val requiresAccessibility: Boolean = false,
    val requiresInternet: Boolean = true
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("category", category.name)
        put("tags", org.json.JSONArray(tags))
        put("risk_level", riskLevel.name)
        fallbackPluginId?.let { put("fallback_id", it) }
        put("requires_accessibility", requiresAccessibility)
        put("requires_internet", requiresInternet)
    }
}
