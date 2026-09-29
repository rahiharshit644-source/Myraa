package com.soltini.app.orchestrator

import com.soltini.app.plugins.PluginResult
import org.json.JSONObject

enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    FALLBACK_TRIGGERED
}

/**
 * TaskStep
 *
 * An individual planned step in an Orchestrator execution plan.
 */
data class TaskStep(
    val id: String = java.util.UUID.randomUUID().toString(),
    val description: String,
    val requiredCapability: String,
    var selectedPluginId: String? = null,
    var parameters: JSONObject = JSONObject(),
    val dependsOnStepId: String? = null,
    val condition: String? = null,
    var status: StepStatus = StepStatus.PENDING,
    var result: PluginResult? = null,
    var usedFallback: Boolean = false,
    var fallbackPluginId: String? = null,
    var retryAttempts: Int = 0,
    var verified: Boolean = false,
    var verificationNotes: String? = null
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("description", description)
        put("required_capability", requiredCapability)
        selectedPluginId?.let { put("selected_plugin_id", it) }
        put("status", status.name)
        result?.let { put("result", it.toJsonObject()) }
        put("used_fallback", usedFallback)
        put("retry_attempts", retryAttempts)
        put("verified", verified)
        verificationNotes?.let { put("verification_notes", it) }
    }
}
