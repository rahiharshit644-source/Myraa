package com.soltini.app.mem0

import java.util.UUID

/**
 * State of a memory in Mem0 lifecycle.
 */
enum class Mem0MemoryState {
    ACTIVE,       // Currently active and considered for context retrieval
    SUPERSEDED,   // Outdated memory replaced by a newer fact (e.g. location moved)
    ARCHIVED,     // Stored for historical record but excluded from active prompts
    DELETED       // Contradicted or explicitly cleared
}

/**
 * The 4 core resolution actions defining the Mem0 algorithm.
 */
enum class Mem0Action {
    ADD,     // Brand new fact discovered; add to active memory
    UPDATE,  // Existing memory is outdated or modified; update target memory
    DELETE,  // Contradicted or invalidated; remove or mark deleted
    NOOP     // Fact already exists in identical or semantic form; avoid duplicates
}

/**
 * Structured Mem0 memory entity.
 * Directly corresponds to Mem0 data contract (https://github.com/mem0ai/mem0).
 */
data class Mem0Memory(
    val id: String = UUID.randomUUID().toString(),
    var memory: String,
    val userId: String = "boss",
    val agentId: String = "myra",
    val runId: String? = null,
    val categories: List<String> = listOf("general"),
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var lastAccessedAt: Long = System.currentTimeMillis(),
    var accessCount: Int = 0,
    var importance: Float = 0.7f,
    var state: Mem0MemoryState = Mem0MemoryState.ACTIVE,
    var supersededBy: String? = null
) {
    /**
     * Composite retrieval score:
     * 50% Query Relevance + 30% Importance + 20% Recency
     */
    fun computeScore(queryRelevance: Float): Float {
        val ageHours = ((System.currentTimeMillis() - updatedAt) / 3600000.0).toFloat()
        val recencyFactor = (1.0f / (1.0f + 0.04f * ageHours)).coerceIn(0.1f, 1.0f)
        return (queryRelevance * 0.50f) + (importance * 0.30f) + (recencyFactor * 0.20f)
    }
}

/**
 * Decision output produced by Mem0 resolver when evaluating new statements or messages.
 */
data class Mem0Resolution(
    val action: Mem0Action,
    val memory: String,
    val targetId: String? = null,
    val category: String = "general",
    val reason: String = "",
    val confidence: Float = 0.9f
)

/**
 * Item returned when searching Mem0 memories with query relevance.
 */
data class Mem0SearchResult(
    val memory: Mem0Memory,
    val relevanceScore: Float,
    val compositeScore: Float
)

/**
 * Aggregated statistics for the Mem0 Engine dashboard.
 */
data class Mem0Stats(
    val totalMemories: Int,
    val activeMemories: Int,
    val supersededMemories: Int,
    val deduplicationCount: Int,
    val categoryCounts: Map<String, Int>,
    val isCloudConnected: Boolean
) {
    val supersededCount: Int get() = supersededMemories
    val duplicatesPrevented: Int get() = deduplicationCount
}
