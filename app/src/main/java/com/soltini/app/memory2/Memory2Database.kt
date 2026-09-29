package com.soltini.app.memory2

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.soltini.app.learning.ExperienceMemory
import com.soltini.app.scheduler.ScheduledTask
import com.soltini.app.scheduler.ScheduledTaskStatus
import org.json.JSONArray

/**
 * Memory2Database
 *
 * Persistent SQLite storage for Long-Term, Knowledge, and Experience Memories in Memory 2.0.
 */
class Memory2Database(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {
    companion object {
        private const val TAG = "Memory2Database"
        private const val DB_NAME = "myra_memory2.db"
        private const val DB_VERSION = 1

        // Table names
        private const val TABLE_LONG_TERM = "long_term_memory"
        private const val TABLE_KNOWLEDGE = "knowledge_memory"
        private const val TABLE_EXPERIENCE = "experience_memory"
        private const val TABLE_CONVERSATIONS = "conversation_history"
        private const val TABLE_LEARNED_EXPERIENCE = "learned_experience_v2"
        private const val TABLE_SCHEDULED_TASKS = "scheduled_tasks"
        private const val TABLE_SESSION_LOG = "session_log"

        const val MAX_LONG_TERM_RECORDS = 500
        const val MAX_SESSION_LOG_RECORDS = 200

        @Volatile
        private var instance: Memory2Database? = null

        fun getInstance(context: Context): Memory2Database =
            instance ?: synchronized(this) {
                instance ?: Memory2Database(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 1. Long-Term Memory (User preferences, personal facts, communication style, corrections, projects)
        db.execSQL("""
            CREATE TABLE $TABLE_LONG_TERM (
                id TEXT PRIMARY KEY,
                type TEXT DEFAULT 'FACT',
                key_name TEXT,
                content TEXT NOT NULL,
                confidence REAL DEFAULT 0.85,
                importance REAL DEFAULT 0.5,
                created_at INTEGER,
                updated_at INTEGER,
                last_accessed_at INTEGER,
                access_count INTEGER DEFAULT 0,
                user_confirmed INTEGER DEFAULT 0,
                sensitivity_level TEXT DEFAULT 'NORMAL',
                tags TEXT
            )
        """.trimIndent())

        // 2. Knowledge Memory (Notes, docs, project architecture, research)
        db.execSQL("""
            CREATE TABLE $TABLE_KNOWLEDGE (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                source TEXT,
                tags TEXT,
                created_at INTEGER
            )
        """.trimIndent())

        // 3. Experience Memory (Successful task patterns, tool sequences, reliability)
        db.execSQL("""
            CREATE TABLE $TABLE_EXPERIENCE (
                pattern_key TEXT PRIMARY KEY,
                task_type TEXT NOT NULL,
                tool_sequence TEXT NOT NULL,
                success_count INTEGER DEFAULT 1,
                fail_count INTEGER DEFAULT 0,
                last_used_at INTEGER
            )
        """.trimIndent())

        // 4. Conversation History (Persistent turn-by-turn chat history)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_CONVERSATIONS (
                id TEXT PRIMARY KEY,
                sender TEXT NOT NULL,
                text TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())

        // 5. Learned Experience V2 (Human-like experience learning)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_LEARNED_EXPERIENCE (
                id TEXT PRIMARY KEY,
                task_type TEXT NOT NULL,
                trigger_pattern TEXT,
                goal TEXT NOT NULL,
                successful_method TEXT,
                steps TEXT,
                tools_used TEXT,
                parameters_pattern TEXT,
                failed_methods TEXT,
                successful_outcome TEXT,
                confidence REAL DEFAULT 0.65,
                success_count INTEGER DEFAULT 1,
                failure_count INTEGER DEFAULT 0,
                last_used_at INTEGER,
                created_at INTEGER,
                updated_at INTEGER,
                user_confirmed INTEGER DEFAULT 0
            )
        """.trimIndent())

        // 6. Persistent Scheduled Tasks (Survives app kills and reboots)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SCHEDULED_TASKS (
                id TEXT PRIMARY KEY,
                task_type TEXT NOT NULL,
                title TEXT NOT NULL,
                command TEXT NOT NULL,
                parsed_intent TEXT,
                parameters_json TEXT,
                scheduled_at INTEGER NOT NULL,
                timezone TEXT,
                recurrence_rule TEXT,
                status TEXT NOT NULL,
                requires_confirmation INTEGER DEFAULT 0,
                created_at INTEGER,
                updated_at INTEGER,
                last_executed_at INTEGER,
                next_execution_at INTEGER,
                retry_count INTEGER DEFAULT 0,
                result TEXT,
                error TEXT
            )
        """.trimIndent())

        // 7. Session Log — persistent, timestamped record of what was discussed.
        // Survives process death / service restarts, unlike in-memory SessionMemory.
        // This is what lets MYRA answer "we talked about X last time" reliably.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SESSION_LOG (
                id TEXT PRIMARY KEY,
                topic TEXT NOT NULL,
                summary TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        try { db.execSQL("ALTER TABLE $TABLE_LONG_TERM ADD COLUMN type TEXT DEFAULT 'FACT'") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE $TABLE_LONG_TERM ADD COLUMN confidence REAL DEFAULT 0.85") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE $TABLE_LONG_TERM ADD COLUMN updated_at INTEGER DEFAULT 0") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE $TABLE_LONG_TERM ADD COLUMN user_confirmed INTEGER DEFAULT 0") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE $TABLE_LONG_TERM ADD COLUMN sensitivity_level TEXT DEFAULT 'NORMAL'") } catch (_: Exception) {}
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SESSION_LOG (
                id TEXT PRIMARY KEY,
                topic TEXT NOT NULL,
                summary TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_CONVERSATIONS (
                id TEXT PRIMARY KEY,
                sender TEXT NOT NULL,
                text TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_LEARNED_EXPERIENCE (
                id TEXT PRIMARY KEY,
                task_type TEXT NOT NULL,
                trigger_pattern TEXT,
                goal TEXT NOT NULL,
                successful_method TEXT,
                steps TEXT,
                tools_used TEXT,
                parameters_pattern TEXT,
                failed_methods TEXT,
                successful_outcome TEXT,
                confidence REAL DEFAULT 0.65,
                success_count INTEGER DEFAULT 1,
                failure_count INTEGER DEFAULT 0,
                last_used_at INTEGER,
                created_at INTEGER,
                updated_at INTEGER,
                user_confirmed INTEGER DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SCHEDULED_TASKS (
                id TEXT PRIMARY KEY,
                task_type TEXT NOT NULL,
                title TEXT NOT NULL,
                command TEXT NOT NULL,
                parsed_intent TEXT,
                parameters_json TEXT,
                scheduled_at INTEGER NOT NULL,
                timezone TEXT,
                recurrence_rule TEXT,
                status TEXT NOT NULL,
                requires_confirmation INTEGER DEFAULT 0,
                created_at INTEGER,
                updated_at INTEGER,
                last_executed_at INTEGER,
                next_execution_at INTEGER,
                retry_count INTEGER DEFAULT 0,
                result TEXT,
                error TEXT
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LONG_TERM")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_KNOWLEDGE")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_EXPERIENCE")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CONVERSATIONS")
        onCreate(db)
    }

    // ─── Long Term CRUD ──────────────────────────────────────────────────────

    fun saveLongTermMemory(item: MemoryItem): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", item.id)
            put("type", item.type.name)
            put("key_name", item.key)
            put("content", item.content)
            put("confidence", item.confidence.coerceIn(0f, 1f))
            put("importance", item.importance.coerceIn(0f, 1f))
            put("created_at", item.createdAt)
            put("updated_at", item.updatedAt)
            put("last_accessed_at", item.lastAccessedAt)
            put("access_count", item.accessCount)
            put("user_confirmed", if (item.userConfirmed) 1 else 0)
            put("sensitivity_level", item.sensitivityLevel)
            put("tags", item.metadata["tags"] ?: "")
        }
        val res = db.insertWithOnConflict(TABLE_LONG_TERM, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        pruneLongTermIfNeeded()
        return res != -1L
    }

    fun updateLongTermMemory(
        id: String,
        newContent: String,
        newImportance: Float? = null,
        newType: MemoryType? = null
    ): Boolean {
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("content", newContent)
                put("updated_at", System.currentTimeMillis())
                if (newImportance != null) {
                    put("importance", newImportance.coerceIn(0f, 1f))
                }
                if (newType != null) {
                    put("type", newType.name)
                }
            }
            db.update(TABLE_LONG_TERM, cv, "id = ?", arrayOf(id)) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error updating memory $id: ${e.message}")
            false
        }
    }

    fun deleteLongTermMemory(id: String): Boolean {
        return try {
            val db = writableDatabase
            db.delete(TABLE_LONG_TERM, "id = ?", arrayOf(id)) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting memory $id: ${e.message}")
            false
        }
    }

    fun searchLongTermMemories(query: String): List<MemoryItem> {
        val list = mutableListOf<MemoryItem>()
        if (query.isBlank()) return getAllLongTermMemories()
        val db = readableDatabase
        val pattern = "%${query.trim()}%"
        val cursor = db.query(
            TABLE_LONG_TERM,
            null,
            "content LIKE ? OR key_name LIKE ? OR tags LIKE ? OR type LIKE ?",
            arrayOf(pattern, pattern, pattern, pattern),
            null, null,
            "importance DESC, updated_at DESC",
            "50"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToMemoryItem(c))
            }
        }
        return list
    }

    fun findMatchingMemories(text: String): List<MemoryItem> {
        val keywords = text.lowercase().split(" ", "_", "-").filter { it.length > 2 }
        if (keywords.isEmpty()) return emptyList()
        val all = getAllLongTermMemories()
        return all.filter { item ->
            val cLower = item.content.lowercase()
            val kLower = item.key.lowercase()
            keywords.any { cLower.contains(it) || kLower.contains(it) }
        }
    }

    fun getMemoriesByType(type: MemoryType): List<MemoryItem> {
        val list = mutableListOf<MemoryItem>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_LONG_TERM,
            null,
            "type = ?",
            arrayOf(type.name),
            null, null,
            "importance DESC, updated_at DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToMemoryItem(c))
            }
        }
        return list
    }

    fun getAllLongTermMemories(): List<MemoryItem> {
        val list = mutableListOf<MemoryItem>()
        val db = readableDatabase
        val cursor = db.query(TABLE_LONG_TERM, null, null, null, null, null, "importance DESC, last_accessed_at DESC")
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToMemoryItem(c))
            }
        }
        return list
    }

    private fun cursorToMemoryItem(c: android.database.Cursor): MemoryItem {
        val tags = c.getString(c.getColumnIndexOrThrow("tags")) ?: ""
        val typeIdx = c.getColumnIndex("type")
        val typeStr = if (typeIdx >= 0) c.getString(typeIdx) else null
        val type = MemoryType.fromString(typeStr)

        val confIdx = c.getColumnIndex("confidence")
        val confidence = if (confIdx >= 0) c.getFloat(confIdx) else 0.85f

        val updatedIdx = c.getColumnIndex("updated_at")
        val updatedAt = if (updatedIdx >= 0) {
            val u = c.getLong(updatedIdx)
            if (u > 0) u else c.getLong(c.getColumnIndexOrThrow("created_at"))
        } else {
            c.getLong(c.getColumnIndexOrThrow("created_at"))
        }

        val confUserIdx = c.getColumnIndex("user_confirmed")
        val userConfirmed = if (confUserIdx >= 0) c.getInt(confUserIdx) == 1 else false

        val sensIdx = c.getColumnIndex("sensitivity_level")
        val sensitivityLevel = if (sensIdx >= 0) c.getString(sensIdx) ?: "NORMAL" else "NORMAL"

        return MemoryItem(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            type = type,
            category = MemoryCategory.LONG_TERM,
            key = c.getString(c.getColumnIndexOrThrow("key_name")) ?: "",
            content = c.getString(c.getColumnIndexOrThrow("content")),
            confidence = confidence,
            importance = c.getFloat(c.getColumnIndexOrThrow("importance")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            updatedAt = updatedAt,
            lastAccessedAt = c.getLong(c.getColumnIndexOrThrow("last_accessed_at")),
            accessCount = c.getInt(c.getColumnIndexOrThrow("access_count")),
            userConfirmed = userConfirmed,
            sensitivityLevel = sensitivityLevel,
            metadata = mapOf("tags" to tags)
        )
    }

    fun recordLongTermAccess(id: String) {
        try {
            val db = writableDatabase
            db.execSQL(
                "UPDATE $TABLE_LONG_TERM SET access_count = access_count + 1, last_accessed_at = ? WHERE id = ?",
                arrayOf(System.currentTimeMillis().toString(), id)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record access: ${e.message}")
        }
    }

    private fun pruneLongTermIfNeeded() {
        val db = writableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_LONG_TERM", null)
        var count = 0
        cursor.use { if (it.moveToFirst()) count = it.getInt(0) }
        if (count > MAX_LONG_TERM_RECORDS) {
            val removeCount = count - MAX_LONG_TERM_RECORDS + 20
            db.execSQL("""
                DELETE FROM $TABLE_LONG_TERM WHERE id IN (
                    SELECT id FROM $TABLE_LONG_TERM
                    ORDER BY (importance * 0.7 + (access_count * 0.1)) ASC, last_accessed_at ASC
                    LIMIT $removeCount
                )
            """.trimIndent())
        }
    }

    // ─── Knowledge CRUD ──────────────────────────────────────────────────────

    data class KnowledgeEntry(
        val id: String,
        val title: String,
        val content: String,
        val source: String,
        val tags: List<String>,
        val createdAt: Long
    )

    fun saveKnowledge(entry: KnowledgeEntry) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", entry.id)
            put("title", entry.title)
            put("content", entry.content)
            put("source", entry.source)
            put("tags", entry.tags.joinToString(","))
            put("created_at", entry.createdAt)
        }
        db.insertWithOnConflict(TABLE_KNOWLEDGE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllKnowledge(): List<KnowledgeEntry> {
        val list = mutableListOf<KnowledgeEntry>()
        val db = readableDatabase
        val cursor = db.query(TABLE_KNOWLEDGE, null, null, null, null, null, "created_at DESC")
        cursor.use { c ->
            while (c.moveToNext()) {
                val tagStr = c.getString(c.getColumnIndexOrThrow("tags")) ?: ""
                list.add(
                    KnowledgeEntry(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        content = c.getString(c.getColumnIndexOrThrow("content")),
                        source = c.getString(c.getColumnIndexOrThrow("source")) ?: "",
                        tags = tagStr.split(",").filter { it.isNotBlank() },
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return list
    }

    // ─── Experience CRUD ─────────────────────────────────────────────────────

    data class ExperienceRecord(
        val patternKey: String,
        val taskType: String,
        val toolSequence: List<String>,
        val successCount: Int,
        val failCount: Int,
        val lastUsedAt: Long
    ) {
        val successRate: Float
            get() {
                val total = successCount + failCount
                return if (total == 0) 1.0f else successCount.toFloat() / total.toFloat()
            }
        val totalCount: Int
            get() = successCount + failCount
    }

    fun recordExperience(taskType: String, toolSequence: List<String>, isSuccess: Boolean) {
        val patternKey = "${taskType.lowercase().trim()}::${toolSequence.joinToString("->")}"
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val seqJson = JSONArray(toolSequence).toString()

        val cursor = db.query(TABLE_EXPERIENCE, null, "pattern_key = ?", arrayOf(patternKey), null, null, null)
        var exists = false
        var sCount = 0
        var fCount = 0
        cursor.use {
            if (it.moveToFirst()) {
                exists = true
                sCount = it.getInt(it.getColumnIndexOrThrow("success_count"))
                fCount = it.getInt(it.getColumnIndexOrThrow("fail_count"))
            }
        }

        if (exists) {
            val cv = ContentValues().apply {
                if (isSuccess) put("success_count", sCount + 1) else put("fail_count", fCount + 1)
                put("last_used_at", now)
            }
            db.update(TABLE_EXPERIENCE, cv, "pattern_key = ?", arrayOf(patternKey))
        } else {
            val cv = ContentValues().apply {
                put("pattern_key", patternKey)
                put("task_type", taskType)
                put("tool_sequence", seqJson)
                put("success_count", if (isSuccess) 1 else 0)
                put("fail_count", if (isSuccess) 0 else 1)
                put("last_used_at", now)
            }
            db.insert(TABLE_EXPERIENCE, null, cv)
        }
    }

    fun upsertExperience(rec: ExperienceRecord): Boolean {
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("pattern_key", rec.patternKey)
                put("task_type", rec.taskType)
                put("tool_sequence", JSONArray(rec.toolSequence).toString())
                put("success_count", rec.successCount)
                put("fail_count", rec.failCount)
                put("last_used_at", rec.lastUsedAt)
            }
            db.insertWithOnConflict(TABLE_EXPERIENCE, null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Failed upserting experience: ${e.message}")
            false
        }
    }

    fun getBestExperienceForTask(taskType: String): ExperienceRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_EXPERIENCE, null,
            "task_type LIKE ?",
            arrayOf("%$taskType%"),
            null, null,
            "success_count DESC, last_used_at DESC",
            "1"
        )
        cursor.use { c ->
            if (c.moveToFirst()) {
                val rawSeq = c.getString(c.getColumnIndexOrThrow("tool_sequence"))
                val seqList = mutableListOf<String>()
                try {
                    val arr = JSONArray(rawSeq)
                    for (i in 0 until arr.length()) seqList.add(arr.getString(i))
                } catch (_: Exception) {}
                return ExperienceRecord(
                    patternKey = c.getString(c.getColumnIndexOrThrow("pattern_key")),
                    taskType = c.getString(c.getColumnIndexOrThrow("task_type")),
                    toolSequence = seqList,
                    successCount = c.getInt(c.getColumnIndexOrThrow("success_count")),
                    failCount = c.getInt(c.getColumnIndexOrThrow("fail_count")),
                    lastUsedAt = c.getLong(c.getColumnIndexOrThrow("last_used_at"))
                )
            }
        }
        return null
    }

    fun getAllExperiences(): List<ExperienceRecord> {
        val list = mutableListOf<ExperienceRecord>()
        val db = readableDatabase
        val cursor = db.query(TABLE_EXPERIENCE, null, null, null, null, null, "last_used_at DESC", "50")
        cursor.use { c ->
            while (c.moveToNext()) {
                val rawSeq = c.getString(c.getColumnIndexOrThrow("tool_sequence"))
                val seqList = mutableListOf<String>()
                try {
                    val arr = JSONArray(rawSeq)
                    for (i in 0 until arr.length()) seqList.add(arr.getString(i))
                } catch (_: Exception) {}
                list.add(
                    ExperienceRecord(
                        patternKey = c.getString(c.getColumnIndexOrThrow("pattern_key")),
                        taskType = c.getString(c.getColumnIndexOrThrow("task_type")),
                        toolSequence = seqList,
                        successCount = c.getInt(c.getColumnIndexOrThrow("success_count")),
                        failCount = c.getInt(c.getColumnIndexOrThrow("fail_count")),
                        lastUsedAt = c.getLong(c.getColumnIndexOrThrow("last_used_at"))
                    )
                )
            }
        }
        return list
    }

    fun clearAllMemories() {
        val db = writableDatabase
        db.delete(TABLE_LONG_TERM, null, null)
        db.delete(TABLE_KNOWLEDGE, null, null)
        db.delete(TABLE_EXPERIENCE, null, null)
        db.delete(TABLE_SESSION_LOG, null, null)
    }

    // ─── Session Log CRUD (persistent "what did we talk about" history) ─────

    data class SessionLogEntry(
        val id: String = java.util.UUID.randomUUID().toString(),
        val topic: String,
        val summary: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    fun saveSessionLogEntry(topic: String, summary: String): Boolean {
        if (topic.isBlank()) return false
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("id", java.util.UUID.randomUUID().toString())
                put("topic", topic.take(120))
                put("summary", summary.take(400))
                put("created_at", System.currentTimeMillis())
            }
            val res = db.insert(TABLE_SESSION_LOG, null, cv)
            pruneSessionLogIfNeeded()
            res != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error saving session log entry: ${e.message}", e)
            false
        }
    }

    /** Most recent [limit] session-log entries, newest first. Always safe to include in a prompt — no relevance filtering needed. */
    fun getRecentSessionLogEntries(limit: Int = 5): List<SessionLogEntry> {
        val list = mutableListOf<SessionLogEntry>()
        return try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, topic, summary, created_at FROM $TABLE_SESSION_LOG ORDER BY created_at DESC LIMIT ?",
                arrayOf(limit.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(
                        SessionLogEntry(
                            id = it.getString(0),
                            topic = it.getString(1),
                            summary = it.getString(2) ?: "",
                            createdAt = it.getLong(3)
                        )
                    )
                }
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching session log: ${e.message}", e)
            emptyList()
        }
    }

    private fun pruneSessionLogIfNeeded() {
        val db = writableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_SESSION_LOG", null)
        var count = 0
        cursor.use { if (it.moveToFirst()) count = it.getInt(0) }
        if (count > MAX_SESSION_LOG_RECORDS) {
            val removeCount = count - MAX_SESSION_LOG_RECORDS
            db.execSQL("""
                DELETE FROM $TABLE_SESSION_LOG WHERE id IN (
                    SELECT id FROM $TABLE_SESSION_LOG ORDER BY created_at ASC LIMIT $removeCount
                )
            """.trimIndent())
        }
    }

    // ─── One-time migration: fold the old standalone mem0 SQLite store into
    // this database's long_term table, then the caller deletes the old file. ───

    fun migrateLegacyMem0Facts(context: Context): Int {
        val dbFile = context.getDatabasePath("myra_mem0.db")
        if (!dbFile.exists()) return 0
        var migrated = 0
        try {
            val legacyDb = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            legacyDb.use { src ->
                val cursor = src.rawQuery(
                    "SELECT id, memory, categories, importance, created_at, updated_at FROM mem0_memories WHERE state = 'ACTIVE'",
                    null
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val text = c.getString(1) ?: continue
                        if (text.isBlank()) continue
                        val category = c.getString(2)?.split(",")?.firstOrNull()?.trim().orEmpty()
                        val item = MemoryItem(
                            id = c.getString(0) ?: java.util.UUID.randomUUID().toString(),
                            type = MemoryType.fromString(category),
                            category = MemoryCategory.LONG_TERM,
                            key = category.ifBlank { "migrated_fact" },
                            content = text,
                            importance = c.getFloat(3),
                            createdAt = c.getLong(4),
                            updatedAt = c.getLong(5),
                            userConfirmed = true,
                            metadata = mapOf("tags" to "migrated_from_mem0")
                        )
                        if (saveLongTermMemory(item)) migrated++
                    }
                }
            }
            // Old standalone file is no longer needed once its facts live here.
            context.deleteDatabase("myra_mem0.db")
        } catch (e: Exception) {
            Log.w(TAG, "Mem0 migration skipped/failed (non-fatal): ${e.message}")
        }
        return migrated
    }

    // ─── Conversation History CRUD ───────────────────────────────────────────

    fun saveConversationEntry(id: String, sender: String, text: String, timestamp: Long): Boolean {
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("id", id)
                put("sender", sender)
                put("text", text)
                put("timestamp", timestamp)
            }
            val res = db.insertWithOnConflict(TABLE_CONVERSATIONS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            res != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error saving conversation entry: ${e.message}", e)
            false
        }
    }

    fun getAllConversationEntries(limit: Int = 200): List<ConversationRecord> {
        val list = mutableListOf<ConversationRecord>()
        return try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, sender, text, timestamp FROM $TABLE_CONVERSATIONS ORDER BY timestamp ASC LIMIT ?",
                arrayOf(limit.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(
                        ConversationRecord(
                            id = it.getString(0),
                            sender = it.getString(1),
                            text = it.getString(2),
                            timestamp = it.getLong(3)
                        )
                    )
                }
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching conversations: ${e.message}", e)
            emptyList()
        }
    }

    fun clearConversationHistory(): Boolean {
        return try {
            val db = writableDatabase
            db.delete(TABLE_CONVERSATIONS, null, null) >= 0
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing conversations: ${e.message}", e)
            false
        }
    }

    fun deleteConversationEntry(id: String): Boolean {
        return try {
            val db = writableDatabase
            db.delete(TABLE_CONVERSATIONS, "id = ?", arrayOf(id)) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting conversation entry: ${e.message}", e)
            false
        }
    }

    // ─── Learned Experience V2 CRUD ──────────────────────────────────────────

    fun upsertLearnedExperience(exp: ExperienceMemory): Boolean {
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("id", exp.id)
                put("task_type", exp.taskType)
                put("trigger_pattern", exp.triggerPattern)
                put("goal", exp.goal)
                put("successful_method", exp.successfulMethod)
                put("steps", exp.stepsToJson())
                put("tools_used", exp.toolsToJson())
                put("parameters_pattern", exp.parametersPatternToJson())
                put("failed_methods", exp.failedMethodsToJson())
                put("successful_outcome", exp.successfulOutcome)
                put("confidence", exp.confidence.coerceIn(0f, 1f))
                put("success_count", exp.successCount)
                put("failure_count", exp.failureCount)
                put("last_used_at", exp.lastUsedAt)
                put("created_at", exp.createdAt)
                put("updated_at", exp.updatedAt)
                put("user_confirmed", if (exp.userConfirmed) 1 else 0)
            }
            db.insertWithOnConflict(TABLE_LEARNED_EXPERIENCE, null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error upserting learned experience: ${e.message}", e)
            false
        }
    }

    fun getLearnedExperience(id: String): ExperienceMemory? {
        val db = readableDatabase
        val cursor = db.query(TABLE_LEARNED_EXPERIENCE, null, "id = ?", arrayOf(id), null, null, null)
        cursor.use { c ->
            if (c.moveToFirst()) return cursorToExperienceMemory(c)
        }
        return null
    }

    fun findBestLearnedExperience(taskType: String, query: String = ""): ExperienceMemory? {
        val db = readableDatabase
        val normalizedType = taskType.lowercase().trim()
        val normalizedQuery = query.lowercase().trim()

        // 1. Exact or LIKE task_type match with highest confidence
        val cursor = db.query(
            TABLE_LEARNED_EXPERIENCE,
            null,
            "task_type LIKE ? OR trigger_pattern LIKE ? OR goal LIKE ?",
            arrayOf("%$normalizedType%", "%$normalizedQuery%", "%$normalizedQuery%"),
            null,
            null,
            "(user_confirmed * 2.0 + confidence * 1.5 + (success_count * 0.2)) DESC, last_used_at DESC",
            "1"
        )
        cursor.use { c ->
            if (c.moveToFirst()) return cursorToExperienceMemory(c)
        }
        return null
    }

    fun getAllLearnedExperiences(): List<ExperienceMemory> {
        val list = mutableListOf<ExperienceMemory>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_LEARNED_EXPERIENCE,
            null, null, null, null, null,
            "last_used_at DESC, confidence DESC",
            "100"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToExperienceMemory(c))
            }
        }
        return list
    }

    fun deleteLearnedExperience(id: String): Boolean {
        return try {
            val db = writableDatabase
            db.delete(TABLE_LEARNED_EXPERIENCE, "id = ?", arrayOf(id)) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting learned experience $id: ${e.message}", e)
            false
        }
    }

    private fun cursorToExperienceMemory(c: android.database.Cursor): ExperienceMemory {
        return ExperienceMemory(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            taskType = c.getString(c.getColumnIndexOrThrow("task_type")),
            triggerPattern = c.getString(c.getColumnIndexOrThrow("trigger_pattern")) ?: "",
            goal = c.getString(c.getColumnIndexOrThrow("goal")),
            successfulMethod = c.getString(c.getColumnIndexOrThrow("successful_method")) ?: "",
            steps = ExperienceMemory.stepsFromJson(c.getString(c.getColumnIndexOrThrow("steps"))),
            toolsUsed = ExperienceMemory.toolsFromJson(c.getString(c.getColumnIndexOrThrow("tools_used"))),
            parametersPattern = ExperienceMemory.parametersPatternFromJson(c.getString(c.getColumnIndexOrThrow("parameters_pattern"))),
            failedMethods = ExperienceMemory.failedMethodsFromJson(c.getString(c.getColumnIndexOrThrow("failed_methods"))),
            successfulOutcome = c.getString(c.getColumnIndexOrThrow("successful_outcome")) ?: "",
            confidence = c.getFloat(c.getColumnIndexOrThrow("confidence")),
            successCount = c.getInt(c.getColumnIndexOrThrow("success_count")),
            failureCount = c.getInt(c.getColumnIndexOrThrow("failure_count")),
            lastUsedAt = c.getLong(c.getColumnIndexOrThrow("last_used_at")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
            userConfirmed = c.getInt(c.getColumnIndexOrThrow("user_confirmed")) == 1
        )
    }

    // ─── Scheduled Tasks CRUD ────────────────────────────────────────────────

    fun insertScheduledTask(task: ScheduledTask): Boolean {
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("id", task.id)
                put("task_type", task.taskType)
                put("title", task.title)
                put("command", task.command)
                put("parsed_intent", task.parsedIntent)
                put("parameters_json", task.parametersToJson())
                put("scheduled_at", task.scheduledAt)
                put("timezone", task.timezone)
                put("recurrence_rule", task.recurrenceRule)
                put("status", task.status.name)
                put("requires_confirmation", if (task.requiresConfirmation) 1 else 0)
                put("created_at", task.createdAt)
                put("updated_at", task.updatedAt)
                put("last_executed_at", task.lastExecutedAt)
                put("next_execution_at", task.nextExecutionAt)
                put("retry_count", task.retryCount)
                put("result", task.result)
                put("error", task.error)
            }
            db.insertWithOnConflict(TABLE_SCHEDULED_TASKS, null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting scheduled task: ${e.message}", e)
            false
        }
    }

    fun updateScheduledTask(task: ScheduledTask): Boolean {
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("task_type", task.taskType)
                put("title", task.title)
                put("command", task.command)
                put("parsed_intent", task.parsedIntent)
                put("parameters_json", task.parametersToJson())
                put("scheduled_at", task.scheduledAt)
                put("timezone", task.timezone)
                put("recurrence_rule", task.recurrenceRule)
                put("status", task.status.name)
                put("requires_confirmation", if (task.requiresConfirmation) 1 else 0)
                put("updated_at", System.currentTimeMillis())
                put("last_executed_at", task.lastExecutedAt)
                put("next_execution_at", task.nextExecutionAt)
                put("retry_count", task.retryCount)
                put("result", task.result)
                put("error", task.error)
            }
            db.update(TABLE_SCHEDULED_TASKS, cv, "id = ?", arrayOf(task.id)) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error updating scheduled task ${task.id}: ${e.message}", e)
            false
        }
    }

    fun getScheduledTask(id: String): ScheduledTask? {
        val db = readableDatabase
        val cursor = db.query(TABLE_SCHEDULED_TASKS, null, "id = ?", arrayOf(id), null, null, null)
        cursor.use { c ->
            if (c.moveToFirst()) return cursorToScheduledTask(c)
        }
        return null
    }

    fun getPendingScheduledTasks(): List<ScheduledTask> {
        val list = mutableListOf<ScheduledTask>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SCHEDULED_TASKS,
            null,
            "status IN ('SCHEDULED', 'WAITING')",
            null,
            null,
            null,
            "scheduled_at ASC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToScheduledTask(c))
            }
        }
        return list
    }

    fun getAllScheduledTasks(limit: Int = 100): List<ScheduledTask> {
        val list = mutableListOf<ScheduledTask>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SCHEDULED_TASKS,
            null,
            null,
            null,
            null,
            null,
            "scheduled_at DESC",
            limit.toString()
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToScheduledTask(c))
            }
        }
        return list
    }

    fun deleteScheduledTask(id: String): Boolean {
        return try {
            val db = writableDatabase
            db.delete(TABLE_SCHEDULED_TASKS, "id = ?", arrayOf(id)) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting scheduled task $id: ${e.message}", e)
            false
        }
    }

    fun updateScheduledTaskStatus(
        id: String,
        status: ScheduledTaskStatus,
        result: String? = null,
        error: String? = null
    ): Boolean {
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("status", status.name)
                put("updated_at", System.currentTimeMillis())
                if (status == ScheduledTaskStatus.EXECUTING || status == ScheduledTaskStatus.COMPLETED || status == ScheduledTaskStatus.FAILED) {
                    put("last_executed_at", System.currentTimeMillis())
                }
                if (result != null) put("result", result)
                if (error != null) put("error", error)
            }
            db.update(TABLE_SCHEDULED_TASKS, cv, "id = ?", arrayOf(id)) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error updating status for task $id: ${e.message}", e)
            false
        }
    }

    private fun cursorToScheduledTask(c: android.database.Cursor): ScheduledTask {
        val statusStr = c.getString(c.getColumnIndexOrThrow("status"))
        val status = try {
            ScheduledTaskStatus.valueOf(statusStr)
        } catch (_: Exception) {
            ScheduledTaskStatus.SCHEDULED
        }
        val lastExec = if (c.isNull(c.getColumnIndexOrThrow("last_executed_at"))) null else c.getLong(c.getColumnIndexOrThrow("last_executed_at"))
        val nextExec = if (c.isNull(c.getColumnIndexOrThrow("next_execution_at"))) null else c.getLong(c.getColumnIndexOrThrow("next_execution_at"))

        return ScheduledTask(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            taskType = c.getString(c.getColumnIndexOrThrow("task_type")),
            title = c.getString(c.getColumnIndexOrThrow("title")),
            command = c.getString(c.getColumnIndexOrThrow("command")),
            parsedIntent = c.getString(c.getColumnIndexOrThrow("parsed_intent")) ?: "",
            parameters = ScheduledTask.parametersFromJson(c.getString(c.getColumnIndexOrThrow("parameters_json"))),
            scheduledAt = c.getLong(c.getColumnIndexOrThrow("scheduled_at")),
            timezone = c.getString(c.getColumnIndexOrThrow("timezone")) ?: java.util.TimeZone.getDefault().id,
            recurrenceRule = c.getString(c.getColumnIndexOrThrow("recurrence_rule")),
            status = status,
            requiresConfirmation = c.getInt(c.getColumnIndexOrThrow("requires_confirmation")) == 1,
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
            lastExecutedAt = lastExec,
            nextExecutionAt = nextExec,
            retryCount = c.getInt(c.getColumnIndexOrThrow("retry_count")),
            result = c.getString(c.getColumnIndexOrThrow("result")),
            error = c.getString(c.getColumnIndexOrThrow("error"))
        )
    }
}

data class ConversationRecord(
    val id: String,
    val sender: String,
    val text: String,
    val timestamp: Long
)

