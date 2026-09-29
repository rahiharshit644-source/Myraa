package com.soltini.app.memory2

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SessionMemory
 *
 * Tier B: Current Session Intelligence.
 * Preserves context across the active app run:
 *   - Topics discussed
 *   - Tasks completed
 *   - Preferences noted during session
 *   - Recent tool results
 *   - Current workflow state
 */
class SessionMemory {

    data class CompletedTaskRecord(
        val goal: String,
        val timestamp: Long = System.currentTimeMillis(),
        val summary: String,
        val success: Boolean,
        val toolIds: List<String>
    )

    private val topicsDiscussed = CopyOnWriteArrayList<String>()
    private val completedTasks = CopyOnWriteArrayList<CompletedTaskRecord>()
    private val sessionPreferences = ConcurrentHashMap<String, String>()
    private val recentToolResults = CopyOnWriteArrayList<Pair<String, String>>()
    var currentWorkflowState: String = "IDLE"

    fun recordTopic(topic: String) {
        val trimmed = topic.trim()
        if (trimmed.isNotBlank() && !topicsDiscussed.contains(trimmed)) {
            topicsDiscussed.add(trimmed)
            if (topicsDiscussed.size > 20) {
                topicsDiscussed.removeAt(0)
            }
        }
    }

    fun recordCompletedTask(
        goal: String,
        summary: String,
        success: Boolean,
        toolIds: List<String>
    ) {
        completedTasks.add(
            CompletedTaskRecord(
                goal = goal,
                summary = summary,
                success = success,
                toolIds = toolIds
            )
        )
        if (completedTasks.size > 30) {
            completedTasks.removeAt(0)
        }
    }

    fun setSessionPreference(key: String, value: String) {
        sessionPreferences[key] = value
    }

    fun getSessionPreference(key: String): String? = sessionPreferences[key]

    fun recordToolResult(toolId: String, summary: String) {
        recentToolResults.add(Pair(toolId, summary))
        if (recentToolResults.size > 15) {
            recentToolResults.removeAt(0)
        }
    }

    fun getRecentToolResults(): List<Pair<String, String>> = recentToolResults.toList()

    fun getRecentTasks(): List<CompletedTaskRecord> = completedTasks.toList()

    fun getTopics(): List<String> = topicsDiscussed.toList()

    fun getSessionSummary(): String {
        val sb = StringBuilder()
        if (topicsDiscussed.isNotEmpty()) {
            sb.append("Recent session topics: ${topicsDiscussed.takeLast(5).joinToString(", ")}\n")
        }
        if (sessionPreferences.isNotEmpty()) {
            sb.append("Session preferences: ${sessionPreferences.entries.joinToString { "${it.key}=${it.value}" }}\n")
        }
        if (completedTasks.isNotEmpty()) {
            val last = completedTasks.last()
            sb.append("Last completed task: \"${last.goal}\" (status=${if (last.success) "success" else "failed"})\n")
        }
        return sb.toString().trim()
    }

    fun clear() {
        topicsDiscussed.clear()
        completedTasks.clear()
        sessionPreferences.clear()
        recentToolResults.clear()
        currentWorkflowState = "IDLE"
    }
}
