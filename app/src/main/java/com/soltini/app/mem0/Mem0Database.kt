package com.soltini.app.mem0

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.soltini.app.memory.MyraUnifiedMemory

/**
 * Mem0Database adapter that persists data cleanly via MyraUnifiedMemory.
 * Keeps compatibility for legacy callers without duplicate databases or external API dependencies.
 */
class Mem0Database(private val context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {
    companion object {
        private const val DB_NAME = "myra_mem0_v2.db"
        private const val DB_VERSION = 1
    }

    private val unifiedMemory by lazy { MyraUnifiedMemory.getInstance(context) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS dummy_meta (k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    fun getActiveMemories(userId: String = "boss"): List<Mem0Memory> {
        return unifiedMemory.getAllMemories().map { m ->
            Mem0Memory(
                id = m.id,
                memory = m.content,
                userId = userId,
                agentId = "myra",
                categories = listOf(m.category.lowercase()),
                createdAt = m.createdAt,
                updatedAt = m.updatedAt,
                lastAccessedAt = m.lastRecalledAt,
                accessCount = m.recallCount,
                importance = m.importance,
                state = Mem0MemoryState.ACTIVE
            )
        }
    }

    fun saveMemory(memory: Mem0Memory): Boolean {
        unifiedMemory.saveMemory(
            content = memory.memory,
            category = memory.categories.firstOrNull()?.uppercase() ?: "FACT",
            importance = memory.importance
        )
        return true
    }

    fun deleteMemory(id: String): Boolean {
        return unifiedMemory.deleteMemory(id)
    }

    fun updateMemory(id: String, newContent: String, category: String? = null): Boolean {
        return unifiedMemory.updateMemory(id, newContent, category)
    }

    fun clearAll(userId: String = "boss") {
        unifiedMemory.clearAllMemories()
    }

    fun getAllMemories(userId: String = "boss"): List<Mem0Memory> {
        return getActiveMemories(userId)
    }

    fun upsertMemory(memory: Mem0Memory): Boolean {
        return saveMemory(memory)
    }

    fun getAllMeta(): Map<String, String> {
        return emptyMap()
    }

    fun setMeta(key: String, value: String): Boolean {
        return true
    }
}
