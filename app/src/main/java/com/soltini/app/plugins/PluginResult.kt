package com.soltini.app.plugins

import android.content.Context
import org.json.JSONObject

/**
 * PluginContext
 *
 * Provides execution context to a plugin including parameters, Android application context,
 * and working session metadata.
 */
data class PluginContext(
    val context: Context,
    val parameters: JSONObject = JSONObject(),
    val callerId: String = "orchestrator",
    val isDryRun: Boolean = false
) {
    fun optString(key: String, default: String = ""): String =
        parameters.optString(key, default)

    fun optInt(key: String, default: Int = 0): Int =
        parameters.optInt(key, default)

    fun optBoolean(key: String, default: Boolean = false): Boolean =
        parameters.optBoolean(key, default)

    fun optDouble(key: String, default: Double = 0.0): Double =
        parameters.optDouble(key, default)
}

/**
 * PluginResult
 *
 * Encapsulates the execution output, status, and verification metrics of a plugin execution.
 */
data class PluginResult(
    val status: Status,
    val data: JSONObject = JSONObject(),
    val summary: String = "",
    val error: String? = null,
    val latencyMs: Long = 0L,
    val pluginId: String = ""
) {
    enum class Status {
        SUCCESS,
        FAILURE,
        PARTIAL,
        DELEGATED
    }

    val isSuccess: Boolean
        get() = status == Status.SUCCESS || status == Status.DELEGATED

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("status", status.name)
        put("plugin_id", pluginId)
        put("summary", summary)
        put("latency_ms", latencyMs)
        error?.let { put("error", it) }
        put("data", data)
    }

    companion object {
        fun success(pluginId: String, summary: String, data: JSONObject = JSONObject(), latencyMs: Long = 0L): PluginResult =
            PluginResult(
                status = Status.SUCCESS,
                pluginId = pluginId,
                summary = summary,
                data = data,
                latencyMs = latencyMs
            )

        fun failure(pluginId: String, error: String, latencyMs: Long = 0L): PluginResult =
            PluginResult(
                status = Status.FAILURE,
                pluginId = pluginId,
                summary = "Failed: $error",
                error = error,
                latencyMs = latencyMs
            )

        fun delegated(pluginId: String, summary: String, data: JSONObject = JSONObject()): PluginResult =
            PluginResult(
                status = Status.DELEGATED,
                pluginId = pluginId,
                summary = summary,
                data = data
            )
    }
}
