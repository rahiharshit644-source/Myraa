package com.soltini.app.apis

import org.json.JSONObject

/**
 * PublicApiEntry
 *
 * Represents an individual API entry from the public-apis/public-apis catalog.
 */
data class PublicApiEntry(
    val name: String,
    val link: String,
    val description: String,
    val auth: String,
    val https: String,
    val cors: String,
    val category: String
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("auth", if (auth.isBlank()) "No" else auth)
        put("https", https)
        put("cors", cors)
        put("category", category)
        put("link", link)
    }

    val requiresAuth: Boolean
        get() = auth.isNotBlank() && !auth.equals("No", ignoreCase = true)
}
