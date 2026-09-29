package com.soltini.app.mem0

import android.content.Context
import com.soltini.app.memory.MyraUnifiedMemory
import com.soltini.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mem0MemoryEngine adapter backed entirely by MyraUnifiedMemory.
 * Provides 100% offline, local memory operations without requiring external API keys.
 */
class Mem0MemoryEngine private constructor(
    private val context: Context,
    private val appSettings: AppSettings
) {
    companion object {
        @Volatile
        private var instance: Mem0MemoryEngine? = null

        fun getInstance(context: Context, appSettings: AppSettings): Mem0MemoryEngine =
            instance ?: synchronized(this) {
                instance ?: Mem0MemoryEngine(context.applicationContext, appSettings).also { instance = it }
            }
    }

    private val unifiedMemory by lazy { MyraUnifiedMemory.getInstance(context) }
    val db = Mem0Database(context)

    suspend fun addOrUpdateMemory(
        fact: String,
        userId: String = appSettings.mem0UserId,
        agentId: String = appSettings.mem0AgentId,
        category: String? = null,
        runId: String? = null
    ): Mem0Resolution = withContext(Dispatchers.IO) {
        val clean = fact.trim()
        if (clean.isBlank()) {
            return@withContext Mem0Resolution(Mem0Action.NOOP, "", reason = "Blank text")
        }

        val mem = unifiedMemory.saveMemory(
            content = clean,
            category = category?.uppercase() ?: "FACT"
        )
        Mem0Resolution(
            action = Mem0Action.ADD,
            memory = mem.content,
            targetId = mem.id,
            category = mem.category.lowercase(),
            reason = "Saved to Myra unified memory"
        )
    }

    suspend fun searchRelevantMemories(
        query: String,
        userId: String = appSettings.mem0UserId,
        maxItems: Int = 8,
        minRelevanceThreshold: Float = 0.2f
    ): List<Mem0SearchResult> = withContext(Dispatchers.IO) {
        val memories = unifiedMemory.searchMemories(query, limit = maxItems)
        memories.map { m ->
            val mem0Obj = Mem0Memory(
                id = m.id,
                memory = m.content,
                userId = userId,
                agentId = "myra",
                categories = listOf(m.category.lowercase()),
                importance = m.importance,
                createdAt = m.createdAt,
                updatedAt = m.updatedAt
            )
            Mem0SearchResult(
                memory = mem0Obj,
                relevanceScore = 0.9f,
                compositeScore = m.importance
            )
        }
    }

    suspend fun processConversationTurn(
        userMessage: String,
        assistantMessage: String
    ): List<Mem0Resolution> = withContext(Dispatchers.IO) {
        val extracted = unifiedMemory.extractFactsFromText(userMessage, isUser = true)
        extracted.map { mem ->
            Mem0Resolution(
                action = Mem0Action.ADD,
                memory = mem.content,
                targetId = mem.id,
                category = mem.category.lowercase(),
                reason = "Extracted from conversation"
            )
        }
    }

    fun getStats(): Mem0Stats {
        val all = unifiedMemory.getAllMemories()
        return Mem0Stats(
            totalMemories = all.size,
            activeMemories = all.size,
            supersededMemories = 0,
            deduplicationCount = 0,
            categoryCounts = emptyMap(),
            isCloudConnected = false
        )
    }

    fun clearAllMemories() {
        unifiedMemory.clearAllMemories()
    }
}
