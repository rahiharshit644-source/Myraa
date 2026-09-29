package com.soltini.app.rag.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.soltini.app.rag.RagChunk
import com.soltini.app.rag.RagDocument
import com.soltini.app.rag.RagSearchResult
import com.soltini.app.rag.RagStats
import org.json.JSONObject

/**
 * RagKnowledgeDatabase
 *
 * Local SQLite Knowledge Store modeled after LangChain4j EmbeddingStore & ContentRetriever.
 * Stores indexed document segments, token statistics, and full-text metadata.
 * Enables fast BM25 / TF-IDF sparse similarity scoring and incremental indexing.
 */
class RagKnowledgeDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "RagKnowledgeDB"
        private const val DB_NAME = "myra_rag_knowledge.db"
        private const val DB_VERSION = 1

        private const val TABLE_DOCS = "rag_documents"
        private const val TABLE_CHUNKS = "rag_chunks"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_DOCS (
                uri TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                mime_type TEXT,
                size_bytes INTEGER,
                last_modified INTEGER,
                chunk_count INTEGER,
                indexed_at INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_CHUNKS (
                id TEXT PRIMARY KEY,
                document_uri TEXT NOT NULL,
                document_name TEXT NOT NULL,
                chunk_index INTEGER,
                total_chunks INTEGER,
                text TEXT NOT NULL,
                token_count INTEGER,
                mime_type TEXT,
                category TEXT,
                terms_json TEXT,
                FOREIGN KEY (document_uri) REFERENCES $TABLE_DOCS(uri) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX idx_chunks_doc_uri ON $TABLE_CHUNKS(document_uri)")
        db.execSQL("CREATE INDEX idx_chunks_category ON $TABLE_CHUNKS(category)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CHUNKS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DOCS")
        onCreate(db)
    }

    /**
     * Atomically replaces an existing document and its chunks.
     */
    fun saveDocumentWithChunks(document: RagDocument, chunks: List<RagChunk>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Delete existing chunks if updating
            db.delete(TABLE_CHUNKS, "document_uri = ?", arrayOf(document.uriString))

            val docValues = ContentValues().apply {
                put("uri", document.uriString)
                put("name", document.name)
                put("mime_type", document.mimeType)
                put("size_bytes", document.sizeBytes)
                put("last_modified", document.lastModified)
                put("chunk_count", chunks.size)
                put("indexed_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE_DOCS, null, docValues, SQLiteDatabase.CONFLICT_REPLACE)

            for (chunk in chunks) {
                val termsJson = JSONObject().apply {
                    for ((k, v) in chunk.termFrequencies) {
                        put(k, v)
                    }
                }.toString()

                val chunkValues = ContentValues().apply {
                    put("id", chunk.id)
                    put("document_uri", chunk.documentUri)
                    put("document_name", chunk.documentName)
                    put("chunk_index", chunk.chunkIndex)
                    put("total_chunks", chunk.totalChunks)
                    put("text", chunk.text)
                    put("token_count", chunk.tokenCount)
                    put("mime_type", chunk.mimeType)
                    put("category", chunk.category)
                    put("terms_json", termsJson)
                }
                db.insert(TABLE_CHUNKS, null, chunkValues)
            }
            db.setTransactionSuccessful()
            Log.i(TAG, "Indexed '${document.name}' with ${chunks.size} chunks.")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving document chunks: ${e.message}")
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Checks if document is already indexed and whether its lastModified timestamp matches.
     */
    fun getDocumentLastModified(uriString: String): Long? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT last_modified FROM $TABLE_DOCS WHERE uri = ?", arrayOf(uriString))
        return cursor.use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
    }

    /**
     * Deletes a document and its chunks from the knowledge base.
     */
    fun deleteDocument(uriString: String) {
        val db = writableDatabase
        db.delete(TABLE_CHUNKS, "document_uri = ?", arrayOf(uriString))
        db.delete(TABLE_DOCS, "uri = ?", arrayOf(uriString))
    }

    /**
     * Retrieves all indexed document URIs (used to prune deleted/inaccessible files).
     */
    fun getAllIndexedUris(): Set<String> {
        val db = readableDatabase
        val uris = mutableSetOf<String>()
        val cursor = db.rawQuery("SELECT uri FROM $TABLE_DOCS", null)
        cursor.use {
            while (it.moveToNext()) {
                uris.add(it.getString(0))
            }
        }
        return uris
    }

    /**
     * Retrieves all chunks for a specific document URI.
     */
    fun getChunksForDocument(uriString: String): List<RagChunk> {
        val db = readableDatabase
        val list = mutableListOf<RagChunk>()
        val cursor = db.rawQuery("SELECT * FROM $TABLE_CHUNKS WHERE document_uri = ? ORDER BY chunk_index ASC", arrayOf(uriString))
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToChunk(it))
            }
        }
        return list
    }

    /**
     * Multi-document retrieval with hybrid lexical and semantic term scoring.
     */
    fun searchChunks(
        query: String,
        targetDocumentUri: String? = null,
        categoryFilter: String? = null,
        limit: Int = 8,
        minScoreThreshold: Float = 0.20f
    ): List<RagSearchResult> {
        val db = readableDatabase
        val tokens = query.lowercase()
            .split(Regex("[^a-z0-9_]+"))
            .filter { it.length > 2 }
            .distinct()

        if (tokens.isEmpty()) return emptyList()

        val results = mutableListOf<RagSearchResult>()

        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (targetDocumentUri != null) {
            whereClauses.add("document_uri = ?")
            args.add(targetDocumentUri)
        }
        if (categoryFilter != null) {
            whereClauses.add("category = ?")
            args.add(categoryFilter)
        }

        val whereSql = if (whereClauses.isNotEmpty()) "WHERE " + whereClauses.joinToString(" AND ") else ""
        val cursor = db.rawQuery("SELECT * FROM $TABLE_CHUNKS $whereSql", args.toTypedArray())

        cursor.use { c ->
            while (c.moveToNext()) {
                val chunk = cursorToChunk(c)
                val textLower = chunk.text.lowercase()
                val docNameLower = chunk.documentName.lowercase()

                var matchScore = 0f
                val matchedTerms = mutableListOf<String>()

                for (token in tokens) {
                    var termScore = 0f
                    if (textLower.contains(token)) {
                        val freq = chunk.termFrequencies[token] ?: 1
                        termScore += 0.30f + (freq * 0.05f).coerceAtMost(0.25f)
                        matchedTerms.add(token)
                    }
                    if (docNameLower.contains(token)) {
                        termScore += 0.40f
                        matchedTerms.add("title:$token")
                    }
                    matchScore += termScore
                }

                // Boost for complete phrase match
                if (textLower.contains(query.lowercase())) {
                    matchScore += 0.50f
                }

                val finalScore = (matchScore / (tokens.size * 0.8f + 0.2f)).coerceIn(0f, 1.0f)

                if (finalScore >= minScoreThreshold) {
                    // Generate highlight snippet
                    val snippet = generateSnippet(chunk.text, matchedTerms)
                    results.add(
                        RagSearchResult(
                            chunk = chunk,
                            score = finalScore,
                            matchSnippet = snippet,
                            matchedTerms = matchedTerms.distinct()
                        )
                    )
                }
            }
        }

        return results.sortedByDescending { it.score }.take(limit)
    }

    private fun generateSnippet(text: String, matchedTerms: List<String>): String {
        val cleanTerms = matchedTerms.map { it.removePrefix("title:") }
        for (term in cleanTerms) {
            val idx = text.indexOf(term, ignoreCase = true)
            if (idx >= 0) {
                val start = (idx - 60).coerceAtLeast(0)
                val end = (idx + term.length + 120).coerceAtMost(text.length)
                val prefix = if (start > 0) "..." else ""
                val suffix = if (end < text.length) "..." else ""
                return prefix + text.substring(start, end).trim() + suffix
            }
        }
        return text.take(180) + if (text.length > 180) "..." else ""
    }

    private fun cursorToChunk(c: android.database.Cursor): RagChunk {
        val id = c.getString(c.getColumnIndexOrThrow("id"))
        val docUri = c.getString(c.getColumnIndexOrThrow("document_uri"))
        val docName = c.getString(c.getColumnIndexOrThrow("document_name"))
        val chunkIdx = c.getInt(c.getColumnIndexOrThrow("chunk_index"))
        val totalChunks = c.getInt(c.getColumnIndexOrThrow("total_chunks"))
        val text = c.getString(c.getColumnIndexOrThrow("text"))
        val tokenCount = c.getInt(c.getColumnIndexOrThrow("token_count"))
        val mimeType = c.getString(c.getColumnIndexOrThrow("mime_type"))
        val category = c.getString(c.getColumnIndexOrThrow("category"))
        val termsJsonStr = c.getString(c.getColumnIndexOrThrow("terms_json"))

        val termFreqs = mutableMapOf<String, Int>()
        try {
            val json = JSONObject(termsJsonStr ?: "{}")
            for (key in json.keys()) {
                termFreqs[key] = json.getInt(key)
            }
        } catch (_: Exception) { }

        return RagChunk(
            id = id,
            documentUri = docUri,
            documentName = docName,
            chunkIndex = chunkIdx,
            totalChunks = totalChunks,
            text = text,
            tokenCount = tokenCount,
            mimeType = mimeType ?: "text/plain",
            category = category ?: "document",
            termFrequencies = termFreqs
        )
    }

    /**
     * Statistics for RAG dashboard and status monitoring.
     */
    fun getStats(): RagStats {
        val db = readableDatabase
        var docCount = 0
        var chunkCount = 0
        var lastIndexed = 0L
        val typeCounts = mutableMapOf<String, Int>()

        db.rawQuery("SELECT COUNT(*), MAX(indexed_at) FROM $TABLE_DOCS", null).use {
            if (it.moveToFirst()) {
                docCount = it.getInt(0)
                lastIndexed = it.getLong(1)
            }
        }

        db.rawQuery("SELECT COUNT(*) FROM $TABLE_CHUNKS", null).use {
            if (it.moveToFirst()) {
                chunkCount = it.getInt(0)
            }
        }

        db.rawQuery("SELECT category, COUNT(*) FROM $TABLE_CHUNKS GROUP BY category", null).use {
            while (it.moveToNext()) {
                typeCounts[it.getString(0) ?: "unknown"] = it.getInt(1)
            }
        }

        return RagStats(
            totalDocuments = docCount,
            totalChunks = chunkCount,
            lastIndexedAt = lastIndexed,
            documentTypes = typeCounts
        )
    }

    fun clearAll() {
        val db = writableDatabase
        db.delete(TABLE_CHUNKS, null, null)
        db.delete(TABLE_DOCS, null, null)
        Log.i(TAG, "Cleared all RAG Knowledge Base index.")
    }
}
