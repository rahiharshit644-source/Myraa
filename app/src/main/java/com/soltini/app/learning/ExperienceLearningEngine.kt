package com.soltini.app.learning

import android.content.Context
import android.util.Log
import com.soltini.app.memory2.Memory2Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ExperienceLearningEngine
 *
 * Implements autonomous human-like experience learning for MYRA.
 *
 * Learning Cycle:
 * TRY -> OBSERVE REAL RESULT -> EVALUATE -> LEARN -> SAVE EXPERIENCE -> USE NEXT TIME -> VERIFY AGAIN
 *
 * Core Capabilities:
 * - Learns from successful tasks and proven tool combinations
 * - Learns from failed tasks and prevents repeating failed methods
 * - Learns from explicit user corrections ("Nahi, aise nahi. Pehle ye karo.")
 * - Adapts execution plans based on real accumulated experience
 * - Strictly enforces safety invariants (never bypasses permissions, financial confirmations, or safety boundaries)
 */
class ExperienceLearningEngine private constructor(private val context: Context) {

    private val db: Memory2Database = Memory2Database.getInstance(context)

    companion object {
        private const val TAG = "ExperienceLearning"

        @Volatile
        private var instance: ExperienceLearningEngine? = null

        fun getInstance(context: Context): ExperienceLearningEngine =
            instance ?: synchronized(this) {
                instance ?: ExperienceLearningEngine(context.applicationContext).also { instance = it }
            }
    }

    /**
     * Finds the best learned experience matching the requested task.
     */
    suspend fun findLearnedExperience(taskType: String, query: String): ExperienceMemory? =
        withContext(Dispatchers.IO) {
            val exp = db.findBestLearnedExperience(taskType, query)
            if (exp != null) {
                Log.i(TAG, "Found learned experience [${exp.id}] for task='$taskType' with confidence=${exp.confidence}, successCount=${exp.successCount}")
            }
            exp
        }

    /**
     * Records an execution attempt, evaluating the outcome and updating confidence.
     */
    suspend fun recordTryAndResult(
        goal: String,
        taskType: String,
        methodAttempted: String,
        toolsUsed: List<String>,
        steps: List<String>,
        isSuccess: Boolean,
        outcomeSummary: String,
        isUserCorrection: Boolean = false,
        userConfirmed: Boolean = false,
        parametersPattern: Map<String, String> = emptyMap()
    ): ExperienceMemory = withContext(Dispatchers.IO) {
        // Enforce safety invariant
        if (!isSafeLearning(toolsUsed, methodAttempted)) {
            Log.w(TAG, "Security check blocked learning dangerous pattern: tools=$toolsUsed, method=$methodAttempted")
            return@withContext ExperienceMemory(
                taskType = taskType,
                goal = goal,
                successfulMethod = "BLOCKED_BY_SAFETY"
            )
        }

        val existing = db.findBestLearnedExperience(taskType, goal)

        val exp = if (existing != null) {
            if (isSuccess) {
                existing.successfulMethod = methodAttempted
                existing.toolsUsed = toolsUsed
                existing.steps = steps
                existing.successfulOutcome = outcomeSummary
                existing.recordSuccess(verified = true, confirmedByUser = userConfirmed)
            } else {
                existing.recordFailure(methodAttempted)
            }
            existing
        } else {
            val newExp = ExperienceMemory(
                taskType = taskType,
                triggerPattern = extractTriggerPattern(goal),
                goal = goal,
                successfulMethod = if (isSuccess) methodAttempted else "",
                steps = if (isSuccess) steps else emptyList(),
                toolsUsed = if (isSuccess) toolsUsed else emptyList(),
                parametersPattern = parametersPattern,
                failedMethods = if (!isSuccess) mutableListOf(methodAttempted) else mutableListOf(),
                successfulOutcome = if (isSuccess) outcomeSummary else "",
                confidence = if (userConfirmed) 0.95f else if (isSuccess) 0.70f else 0.40f,
                successCount = if (isSuccess) 1 else 0,
                failureCount = if (isSuccess) 0 else 1,
                userConfirmed = userConfirmed
            )
            newExp
        }

        db.upsertLearnedExperience(exp)
        Log.i(TAG, "Saved experience for task '$taskType': success=$isSuccess, confidence=${exp.confidence}, successes=${exp.successCount}, failures=${exp.failureCount}")
        exp
    }

    /**
     * Learns directly from a user correction ("Nahi, aise nahi. Pehle ye karo.").
     * 1. Stops the incorrect approach.
     * 2. Flags previous method in failedMethods.
     * 3. Records the user-taught workflow with high confidence & userConfirmed = true.
     */
    suspend fun recordUserCorrection(
        taskType: String,
        originalGoal: String,
        wrongMethodAttempted: String,
        correctedMethod: String,
        newSteps: List<String>,
        newTools: List<String>
    ): ExperienceMemory = withContext(Dispatchers.IO) {
        val existing = db.findBestLearnedExperience(taskType, originalGoal)
        val exp = existing ?: ExperienceMemory(
            taskType = taskType,
            triggerPattern = extractTriggerPattern(originalGoal),
            goal = originalGoal
        )

        exp.applyUserCorrection(
            correctedMethod = correctedMethod,
            newSteps = newSteps,
            newTools = newTools
        )
        if (wrongMethodAttempted.isNotBlank() && !exp.failedMethods.contains(wrongMethodAttempted)) {
            exp.failedMethods.add(wrongMethodAttempted)
        }

        db.upsertLearnedExperience(exp)
        Log.i(TAG, "Recorded user correction for task '$taskType': newMethod='$correctedMethod', confirmed=true")
        exp
    }

    /**
     * Formats learned experience context to guide the planning phase.
     */
    fun formatExperiencePromptBlock(exp: ExperienceMemory): String {
        val sb = StringBuilder()
        sb.append("### LEARNED REAL EXPERIENCE FOR THIS TASK:\n")
        sb.append("- Task Type: ${exp.taskType}\n")
        sb.append("- Confidence: ${(exp.confidence * 100).toInt()}% (Successes: ${exp.successCount}, Failures: ${exp.failureCount})\n")
        if (exp.userConfirmed) {
            sb.append("- User Confirmed: YES (Prioritize this exact method)\n")
        }
        if (exp.successfulMethod.isNotBlank()) {
            sb.append("- Proven Winning Method: ${exp.successfulMethod}\n")
        }
        if (exp.toolsUsed.isNotEmpty()) {
            sb.append("- Proven Tool Sequence: ${exp.toolsUsed.joinToString(" -> ")}\n")
        }
        if (exp.failedMethods.isNotEmpty()) {
            sb.append("- FAILED METHODS (DO NOT REPEAT): ${exp.failedMethods.joinToString(" | ")}\n")
        }
        return sb.toString().trim()
    }

    /**
     * Strict safety validator: MYRA must never learn dangerous or unauthorized behaviors.
     */
    fun isSafeLearning(toolsUsed: List<String>, method: String): Boolean {
        val lowerMethod = method.lowercase()
        // Never learn to bypass permissions, do financial transactions without confirmation, or alter prompt
        if (lowerMethod.contains("bypass_permission") ||
            lowerMethod.contains("auto_pay") ||
            lowerMethod.contains("grant_permission") ||
            lowerMethod.contains("disable_safety") ||
            lowerMethod.contains("modify_system_prompt")
        ) {
            return false
        }
        return true
    }

    fun getAllLearnedExperiences(): List<ExperienceMemory> = db.getAllLearnedExperiences()

    fun deleteExperience(id: String): Boolean = db.deleteLearnedExperience(id)

    private fun extractTriggerPattern(goal: String): String {
        return goal.lowercase()
            .replace(Regex("""(?i)^(hey\s+myra|myra|soltini|please|kripya)\s*,?\s*"""), "")
            .take(50)
            .trim()
    }
}
