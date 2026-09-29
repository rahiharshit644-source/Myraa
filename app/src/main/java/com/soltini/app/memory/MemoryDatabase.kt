package com.soltini.app.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MemoryDatabase
 *
 * Lightweight SQLite store for Soltini's persistent vector memory.
 *
 * Schema:
 *   id           — auto-increment primary key
 *   content      — plain text of the remembered fact
 *   embedding    — 768-dim float32 vector as raw BLOB (3072 bytes)
 *   importance   — REAL 0.0–1.0 (used for pruning)
 *   created_at   — Unix millis
 *   access_count — how many times this memory was retrieved (relevance signal)
 *
 * Hard cap: MAX_MEMORIES rows. When exceeded, lowest (importance × recency) are pruned.
 * Total worst-case size: 200 × (text ~100B + vector 3072B) ≈ ~640KB
 */
class MemoryDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {

    companion object {
        private const val TAG = "MemoryDatabase"
        private const val DB_NAME    = "soltini_memory.db"
        private const val DB_VERSION = 1
        private const val TABLE      = "memories"
        private const val COL_ID     = "id"
        private const val COL_TEXT   = "content"
        private const val COL_VEC    = "embedding"
        private const val COL_IMP    = "importance"
        private const val COL_TIME   = "created_at"
        private const val COL_COUNT  = "access_count"

        const val MAX_MEMORIES = 200
        const val EMBEDDING_DIMS = 768
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID    INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TEXT  TEXT    NOT NULL,
                $COL_VEC   BLOB,
                $COL_IMP   REAL    DEFAULT 0.5,
                $COL_TIME  INTEGER DEFAULT 0,
                $COL_COUNT INTEGER DEFAULT 0
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    fun insertMemory(content: String, embedding: FloatArray?, importance: Float = 0.5f): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_TEXT, content)
            put(COL_VEC, embedding?.toBlob())
            put(COL_IMP, importance.coerceIn(0f, 1f))
            put(COL_TIME, System.currentTimeMillis())
            put(COL_COUNT, 0)
        }
        val id = db.insert(TABLE, null, cv)
        Log.d(TAG, "Inserted memory id=$id: \"$content\"")
        pruneIfNeeded()
        return id
    }

    fun clearAll() {
        writableDatabase.delete(TABLE, null, null)
        Log.i(TAG, "All memories cleared")
    }

    fun deleteMemory(id: Long): Boolean {
        val rows = writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
        Log.i(TAG, "Deleted memory id=$id (rows=$rows)")
        return rows > 0
    }

    fun updateMemory(id: Long, newText: String): Boolean {
        val cv = ContentValues().apply {
            put(COL_TEXT, newText.trim())
        }
        val rows = writableDatabase.update(TABLE, cv, "$COL_ID = ?", arrayOf(id.toString()))
        Log.i(TAG, "Updated memory id=$id (rows=$rows)")
        return rows > 0
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    data class Memory(
        val id: Long,
        val content: String,
        val embedding: FloatArray?,
        val importance: Float,
        val createdAt: Long,
        val accessCount: Int
    )

    fun getAllMemories(): List<Memory> {
        val result = mutableListOf<Memory>()
        val db = readableDatabase
        db.query(TABLE, null, null, null, null, null, "$COL_TIME DESC").use { c ->
            while (c.moveToNext()) {
                result.add(c.toMemory())
            }
        }
        return result
    }

    fun getCount(): Int {
        return readableDatabase.query(TABLE, arrayOf("COUNT(*) as cnt"),
            null, null, null, null, null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Increment access counter for a memory — used for relevance-based pruning. */
    fun recordAccess(id: Long) {
        writableDatabase.execSQL(
            "UPDATE $TABLE SET $COL_COUNT = $COL_COUNT + 1 WHERE $COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    // ── Pruning ───────────────────────────────────────────────────────────────

    /**
     * Deletes the lowest-scored memories (score = importance × recency_factor)
     * until count is within MAX_MEMORIES.
     */
    private fun pruneIfNeeded() {
        val count = getCount()
        if (count <= MAX_MEMORIES) return

        val toDelete = count - MAX_MEMORIES
        Log.i(TAG, "Pruning $toDelete low-value memories (count=$count > max=$MAX_MEMORIES)")

        // Score = importance * (access_count + 1); delete lowest scores first
        writableDatabase.execSQL("""
            DELETE FROM $TABLE WHERE $COL_ID IN (
                SELECT $COL_ID FROM $TABLE
                ORDER BY ($COL_IMP * ($COL_COUNT + 1)) ASC, $COL_TIME ASC
                LIMIT $toDelete
            )
        """.trimIndent())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun android.database.Cursor.toMemory(): Memory {
        val vecBlob = getBlob(getColumnIndexOrThrow(COL_VEC))
        return Memory(
            id          = getLong(getColumnIndexOrThrow(COL_ID)),
            content     = getString(getColumnIndexOrThrow(COL_TEXT)),
            embedding   = vecBlob?.toFloatArray(),
            importance  = getFloat(getColumnIndexOrThrow(COL_IMP)),
            createdAt   = getLong(getColumnIndexOrThrow(COL_TIME)),
            accessCount = getInt(getColumnIndexOrThrow(COL_COUNT))
        )
    }

    private fun FloatArray.toBlob(): ByteArray {
        val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
        forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun ByteArray.toFloatArray(): FloatArray {
        val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(size / 4) { buf.float }
    }
}
