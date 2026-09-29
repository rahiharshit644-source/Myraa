package com.soltini.app.learning

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * ExperienceMemory
 *
 * Represents an autonomously learned skill or task execution pattern.
 * MYRA improves its behavior over time by recording successes, failures,
 * user corrections, and tool sequences.
 */
data class ExperienceMemory(
    val id: String = UUID.randomUUID().toString(),
    val taskType: String,
    val triggerPattern: String = "",
    val goal: String,
    var successfulMethod: String = "",
    var steps: List<String> = emptyList(),
    var toolsUsed: List<String> = emptyList(),
    var parametersPattern: Map<String, String> = emptyMap(),
    var failedMethods: MutableList<String> = mutableListOf(),
    var successfulOutcome: String = "",
    var confidence: Float = 0.65f,
    var successCount: Int = 1,
    var failureCount: Int = 0,
    var lastUsedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var userConfirmed: Boolean = false
) {
    val successRate: Float
        get() {
            val total = successCount + failureCount
            return if (total == 0) 1.0f else successCount.toFloat() / total.toFloat()
        }

    /**
     * Boosts confidence after verified success.
     */
    fun recordSuccess(verified: Boolean = true, confirmedByUser: Boolean = false) {
        successCount++
        lastUsedAt = System.currentTimeMillis()
        updatedAt = System.currentTimeMillis()

        if (confirmedByUser) {
            userConfirmed = true
            confidence = (confidence + 0.3f).coerceAtMost(1.0f).coerceAtLeast(0.95f)
        } else if (verified) {
            confidence = (confidence + 0.08f).coerceAtMost(1.0f)
        }
    }

    /**
     * Lowers confidence and records failed approach so it is not repeated.
     */
    fun recordFailure(failedApproachDescription: String) {
        failureCount++
        lastUsedAt = System.currentTimeMillis()
        updatedAt = System.currentTimeMillis()
        confidence = (confidence - 0.20f).coerceAtLeast(0.1f)

        val clean = failedApproachDescription.trim()
        if (clean.isNotBlank() && !failedMethods.contains(clean)) {
            failedMethods.add(clean)
        }
    }

    /**
     * Incorporates explicit user correction ("Nahi, aise nahi. Pehle ye karo.").
     */
    fun applyUserCorrection(
        correctedMethod: String,
        newSteps: List<String>,
        newTools: List<String>
    ) {
        // Record previous method as failed for this task
        if (successfulMethod.isNotBlank() && successfulMethod != correctedMethod) {
            if (!failedMethods.contains(successfulMethod)) {
                failedMethods.add(successfulMethod)
            }
        }
        successfulMethod = correctedMethod
        steps = newSteps
        toolsUsed = newTools
        userConfirmed = true
        confidence = 0.95f
        successCount++
        updatedAt = System.currentTimeMillis()
        lastUsedAt = System.currentTimeMillis()
    }

    fun stepsToJson(): String = JSONArray(steps).toString()
    fun toolsToJson(): String = JSONArray(toolsUsed).toString()
    fun failedMethodsToJson(): String = JSONArray(failedMethods).toString()
    fun parametersPatternToJson(): String = JSONObject(parametersPattern).toString()

    companion object {
        fun stepsFromJson(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                list
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun toolsFromJson(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                list
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun failedMethodsFromJson(json: String?): MutableList<String> {
            if (json.isNullOrBlank()) return mutableListOf()
            return try {
                val arr = JSONArray(json)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                list
            } catch (_: Exception) {
                mutableListOf()
            }
        }

        fun parametersPatternFromJson(json: String?): Map<String, String> {
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
