package com.soltini.app.reach.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.soltini.app.reach.model.ResearchHistoryItem
import com.soltini.app.reach.model.ResearchMode

/**
 * ReachHistoryStore
 *
 * Lightweight, persistent SQLite store for completed internet research sessions.
 */
class ReachHistoryStore private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {
    companion object {
        private const val DB_NAME = "myra_reach_history.db"
        private const val DB_VERSION = 1

        private const val TABLE_HISTORY = "research_history"
        private const val COL_ID = "id"
        private const val COL_QUERY = "query"
        private const val COL_MODE = "mode"
        private const val COL_SUMMARY = "summary"
        private const val COL_SOURCE_COUNT = "source_count"
        private const val COL_SOURCES_JSON = "sources_json"
        private const val COL_DURATION = "duration_ms"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_SUCCESS = "is_success"

        @Volatile
        private var instance: ReachHistoryStore? = null

        fun getInstance(context: Context): ReachHistoryStore =
            instance ?: synchronized(this) {
                instance ?: ReachHistoryStore(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_HISTORY (
                $COL_ID TEXT PRIMARY KEY,
                $COL_QUERY TEXT NOT NULL,
                $COL_MODE TEXT NOT NULL,
                $COL_SUMMARY TEXT NOT NULL,
                $COL_SOURCE_COUNT INTEGER DEFAULT 0,
                $COL_SOURCES_JSON TEXT,
                $COL_DURATION INTEGER DEFAULT 0,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_SUCCESS INTEGER DEFAULT 1
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_reach_timestamp ON $TABLE_HISTORY ($COL_TIMESTAMP DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
        onCreate(db)
    }

    fun saveHistory(item: ResearchHistoryItem) {
        val values = ContentValues().apply {
            put(COL_ID, item.id)
            put(COL_QUERY, item.query)
            put(COL_MODE, item.mode.name)
            put(COL_SUMMARY, item.summary)
            put(COL_SOURCE_COUNT, item.sourceCount)
            put(COL_SOURCES_JSON, item.sourcesJson)
            put(COL_DURATION, item.durationMs)
            put(COL_TIMESTAMP, item.timestamp)
            put(COL_SUCCESS, if (item.isSuccess) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(TABLE_HISTORY, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getRecentHistory(limit: Int = 20): List<ResearchHistoryItem> {
        val list = mutableListOf<ResearchHistoryItem>()
        val cursor = readableDatabase.query(
            TABLE_HISTORY,
            null,
            null,
            null,
            null,
            null,
            "$COL_TIMESTAMP DESC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                val modeStr = it.getString(it.getColumnIndexOrThrow(COL_MODE))
                val mode = try { ResearchMode.valueOf(modeStr) } catch (_: Exception) { ResearchMode.NORMAL }
                list.add(
                    ResearchHistoryItem(
                        id = it.getString(it.getColumnIndexOrThrow(COL_ID)),
                        query = it.getString(it.getColumnIndexOrThrow(COL_QUERY)),
                        mode = mode,
                        summary = it.getString(it.getColumnIndexOrThrow(COL_SUMMARY)),
                        sourceCount = it.getInt(it.getColumnIndexOrThrow(COL_SOURCE_COUNT)),
                        sourcesJson = it.getString(it.getColumnIndexOrThrow(COL_SOURCES_JSON)) ?: "[]",
                        durationMs = it.getLong(it.getColumnIndexOrThrow(COL_DURATION)),
                        timestamp = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP)),
                        isSuccess = it.getInt(it.getColumnIndexOrThrow(COL_SUCCESS)) == 1
                    )
                )
            }
        }
        return list
    }

    fun deleteHistory(id: String) {
        writableDatabase.delete(TABLE_HISTORY, "$COL_ID = ?", arrayOf(id))
    }

    fun clearHistory() {
        writableDatabase.delete(TABLE_HISTORY, null, null)
    }
}
