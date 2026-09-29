package com.soltini.app.memory2

import java.util.concurrent.ConcurrentHashMap

/**
 * WorkingMemory
 *
 * Tier A: Active Task & Immediate Working State.
 * Contains:
 *   - Active user request
 *   - Current task plan & active step
 *   - Intermediate results from tools/plugins
 *   - Temporary variables
 *
 * Automatically expires when no longer useful or after TTL.
 */
class WorkingMemory {

    private val variables = ConcurrentHashMap<String, MemoryItem>()
    private val intermediateResults = ConcurrentHashMap<String, Any>()
    var activeGoal: String? = null
        private set
    var activeStepIndex: Int = 0
        private set

    fun startTask(goal: String) {
        cleanExpired()
        activeGoal = goal
        activeStepIndex = 0
        intermediateResults.clear()
        setVariable("active_goal", goal, ttlMillis = 10 * 60 * 1000L) // 10 min TTL
    }

    fun completeTask() {
        activeGoal = null
        activeStepIndex = 0
        intermediateResults.clear()
        // Expire task-scoped variables
        cleanExpired()
    }

    fun setStepIndex(index: Int) {
        activeStepIndex = index
    }

    fun storeIntermediateResult(stepId: String, result: Any) {
        intermediateResults[stepId] = result
        setVariable("intermediate::$stepId", result.toString(), ttlMillis = 5 * 60 * 1000L)
    }

    fun getIntermediateResult(stepId: String): Any? = intermediateResults[stepId]

    fun getAllIntermediateResults(): Map<String, Any> = intermediateResults.toMap()

    fun setVariable(key: String, value: String, ttlMillis: Long = 5 * 60 * 1000L) {
        variables[key] = MemoryItem(
            category = MemoryCategory.WORKING,
            key = key,
            content = value,
            importance = 0.4f,
            ttlMillis = ttlMillis
        )
    }

    fun getVariable(key: String): String? {
        val item = variables[key] ?: return null
        if (item.isExpired) {
            variables.remove(key)
            return null
        }
        item.lastAccessedAt = System.currentTimeMillis()
        return item.content
    }

    fun getActiveWorkingContext(): List<MemoryItem> {
        cleanExpired()
        return variables.values.toList()
    }

    fun cleanExpired() {
        val it = variables.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.isExpired) {
                it.remove()
            }
        }
    }

    fun clear() {
        variables.clear()
        intermediateResults.clear()
        activeGoal = null
        activeStepIndex = 0
    }
}
