package com.soltini.app.memory2

/**
 * MemoryCategory
 *
 * Defines the 5 distinct tiers in Memory 2.0.
 */
enum class MemoryCategory {
    WORKING,     // Short-lived active task variables, intermediate tool outputs, TTL expired
    SESSION,     // Current session topics, recent actions, active workflow states
    LONG_TERM,   // Durable user habits, preferences, persistent personal facts
    KNOWLEDGE,   // Project docs, user notes, saved research
    EXPERIENCE   // Learned tool combinations and successful workflow patterns
}

/**
 * MemoryType
 *
 * Explicit semantic taxonomy for persistent memory.
 */
enum class MemoryType {
    USER_PROFILE,
    PREFERENCE,
    FACT,
    PROJECT,
    CONVERSATION,
    TASK,
    CORRECTION,
    ROUTINE,
    GENERAL;

    companion object {
        fun fromString(value: String?): MemoryType {
            if (value.isNullOrBlank()) return FACT
            return try {
                valueOf(value.uppercase().trim())
            } catch (_: Exception) {
                FACT
            }
        }
    }
}

/**
 * MemoryItem
 *
 * Unified entity representing a single memory across all memory categories and types.
 */
data class MemoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: MemoryType = MemoryType.FACT,
    val category: MemoryCategory = MemoryCategory.LONG_TERM,
    val key: String,
    val content: String,
    val confidence: Float = 0.85f,    // 0.0 to 1.0
    val importance: Float = 0.5f,     // 0.0 to 1.0
    val relevance: Float = 1.0f,      // dynamically computed
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var lastAccessedAt: Long = System.currentTimeMillis(),
    var accessCount: Int = 0,
    val userConfirmed: Boolean = false,
    val sensitivityLevel: String = "NORMAL", // "NORMAL" | "SENSITIVE"
    val ttlMillis: Long? = null,      // Optional TTL for working/temporary memories
    val metadata: Map<String, String> = emptyMap()
) {
    val text: String get() = content

    val isExpired: Boolean
        get() {
            val ttl = ttlMillis ?: return false
            return (System.currentTimeMillis() - createdAt) > ttl
        }

    /**
     * Combined composite score for retrieval ranking:
     * 40% Semantic/Query Relevance + 30% Importance + 15% Recency + Confidence Bonus + User Confirmed Bonus
     */
    fun computeScore(queryRelevance: Float): Float {
        val ageHours = ((System.currentTimeMillis() - lastAccessedAt) / 3600000.0).toFloat()
        val recencyScore = (1.0f / (1.0f + 0.05f * ageHours)).coerceIn(0.1f, 1.0f)
        val confBonus = (confidence - 0.5f).coerceAtLeast(0f) * 0.1f
        val confirmedBonus = if (userConfirmed) 0.15f else 0.0f
        return (queryRelevance * 0.40f) + (importance * 0.30f) + (recencyScore * 0.15f) + confBonus + confirmedBonus
    }
}
