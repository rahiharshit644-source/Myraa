package com.soltini.app.scheduler

import org.json.JSONObject
import java.util.UUID

/**
 * ScheduledTaskStatus
 */
enum class ScheduledTaskStatus {
    SCHEDULED,
    WAITING,
    EXECUTING,
    COMPLETED,
    FAILED,
    CANCELLED,
    SKIPPED,
    WAITING_FOR_CONFIRMATION
}

/**
 * ScheduledTask
 *
 * Real persistent task model for future, delayed, and recurring actions.
 * Survives application restarts, process termination, and device reboot.
 */
data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val taskType: String, // "CALL", "SEND_MESSAGE", "REMINDER", "PLAY_MEDIA", "GENERIC_ACTION"
    val title: String,
    val command: String,
    val parsedIntent: String = "",
    val parameters: Map<String, String> = emptyMap(),
    var scheduledAt: Long,
    val timezone: String = java.util.TimeZone.getDefault().id,
    val recurrenceRule: String? = null, // "DAILY", "WEEKLY", "WEEKDAYS", or null
    var status: ScheduledTaskStatus = ScheduledTaskStatus.SCHEDULED,
    val requiresConfirmation: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var lastExecutedAt: Long? = null,
    var nextExecutionAt: Long? = null,
    var retryCount: Int = 0,
    var result: String? = null,
    var error: String? = null
) {
    fun parametersToJson(): String = JSONObject(parameters).toString()

    val isPending: Boolean
        get() = status == ScheduledTaskStatus.SCHEDULED || status == ScheduledTaskStatus.WAITING

    companion object {
        fun parametersFromJson(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                val obj = JSONObject(json)
                val map = mutableMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = obj.optString(k, "")
                }
                map
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}
