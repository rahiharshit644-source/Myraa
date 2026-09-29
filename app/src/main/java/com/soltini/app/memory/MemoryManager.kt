package com.soltini.app.memory

import android.util.Log
import com.soltini.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * MemoryManager — high-level facade for Soltini's vector memory system.
 *
 * Responsibilities:
 *   1. saveMemory(text)    — embed via Gemini API, store in SQLite
 *   2. retrieveRelevant()  — cosine-similarity search over stored vectors
 *   3. buildMemoryPrompt() — format top-K memories for injection into system instruction
 *   4. extractAndSave()    — parse conversation text, pull out precious facts, save them
 */
class MemoryManager(
    private val db: MemoryDatabase,
    private val appSettings: AppSettings
) {

    companion object {
        private const val TAG = "MemoryManager"
        private const val EMBED_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent"
        private const val EXTRACT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-lite:generateContent"
        private const val TOP_K = 10   // memories injected per session
        private const val MIN_SIMILARITY = 0.55f  // ignore unrelated memories
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Embed [text] via Gemini API and store in memory database.
     * Runs on Dispatchers.IO — safe to call from coroutine scope.
     */
    suspend fun saveMemory(text: String, importance: Float = 0.6f) = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return@withContext
        val embedding = embedText(trimmed)
        db.insertMemory(trimmed, embedding, importance)
    }

    /**
     * Retrieves the top-K memories most semantically similar to [query].
     * Returns an empty list if embeddings fail (no crash, just no memory).
     */
    suspend fun retrieveRelevant(query: String, topK: Int = TOP_K): List<MemoryDatabase.Memory> =
        withContext(Dispatchers.IO) {
            val queryVec = embedText(query) ?: run {
                Log.w(TAG, "Could not embed query — returning all recent memories")
                return@withContext db.getAllMemories().take(topK)
            }
            val all = db.getAllMemories()
            if (all.isEmpty()) return@withContext emptyList()

            // Rank by cosine similarity, filter by threshold
            val ranked = all
                .mapNotNull { mem ->
                    val vec = mem.embedding ?: return@mapNotNull null
                    val sim = cosineSimilarity(queryVec, vec)
                    if (sim >= MIN_SIMILARITY) Pair(mem, sim) else null
                }
                .sortedByDescending { it.second }
                .take(topK)

            // Update access counts for recalled memories
            ranked.forEach { (mem, _) -> db.recordAccess(mem.id) }

            ranked.map { it.first }
        }

    /**
     * Formats retrieved memories as a system-instruction block to inject into
     * Gemini's setup message.
     * Loads local memories directly without blocking network latency on startup.
     *
     * Returns empty string if there are no memories.
     */
    suspend fun buildMemoryPrompt(contextQuery: String = "user preferences and personal info"): String = withContext(Dispatchers.IO) {
        val memories = db.getAllMemories().take(TOP_K)
        if (memories.isEmpty()) return@withContext ""
        val lines = memories.joinToString("\n") { "- ${it.content}" }
        "\n\nWHAT YOU REMEMBER ABOUT THIS USER:\n$lines\n"
    }

    /**
     * Given a completed conversation turn (Soltini's reply text), calls
     * Gemini Flash to extract precious user-specific facts, then saves each one.
     *
     * This is called after [onTurnComplete] in BackgroundVoiceService.
     */
    suspend fun extractAndSave(conversationText: String) = withContext(Dispatchers.IO) {
        if (conversationText.isBlank()) return@withContext
        val apiKey = appSettings.effectiveApiKey()
        if (apiKey.isBlank()) return@withContext

        try {
            val prompt = """You are a memory extractor. From the following conversation excerpt, extract ONLY concrete facts specifically about the USER (not general knowledge).

Rules:
- Extract: user's name, preferences, habits, goals, relationships, important personal events
- DO NOT extract: questions the user asked, commands they gave, general statements
- Return a JSON array of short fact strings (max 15 words each)
- If nothing worth remembering, return an empty array []

Conversation:
$conversationText

Return only valid JSON array, no explanation."""

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
            }.toString()

            val req = Request.Builder()
                .url("$EXTRACT_URL?key=$apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(req).execute()
            val responseBody = response.body?.string() ?: return@withContext
            if (!response.isSuccessful) {
                Log.w(TAG, "Memory extraction failed: HTTP ${response.code}")
                return@withContext
            }

            val text = JSONObject(responseBody)
                .optJSONObject("candidates")
                ?.let { null } // will use array form below
            val candidates = JSONObject(responseBody).optJSONArray("candidates")
            val rawText = candidates
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text") ?: return@withContext

            // Parse JSON array of facts
            val facts = try {
                val arr = JSONArray(rawText.trim().removePrefix("```json").removeSuffix("```").trim())
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse extracted facts: ${e.message}")
                return@withContext
            }

            Log.i(TAG, "Extracted ${facts.size} facts from conversation")
            facts.forEach { fact ->
                if (fact.isNotBlank() && fact.length < 200) {
                    saveMemory(fact, importance = 0.7f)
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "Memory extraction error (non-fatal): ${e.message}")
        }
    }

    fun getCount(): Int = db.getCount()
    fun getAllMemories(): List<MemoryDatabase.Memory> = db.getAllMemories()
    fun clearAll() = db.clearAll()

    // ── Embedding ─────────────────────────────────────────────────────────────

    private fun embedText(text: String): FloatArray? {
        val apiKey = appSettings.effectiveApiKey()
        if (apiKey.isBlank()) return null

        return try {
            val body = JSONObject().apply {
                put("model", "models/text-embedding-004")
                put("content", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", text) })
                    })
                })
            }.toString()

            val req = Request.Builder()
                .url("$EMBED_URL?key=$apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "Embedding failed: HTTP ${resp.code}")
                return null
            }

            val arr = JSONObject(resp.body!!.string())
                .getJSONObject("embedding")
                .getJSONArray("values")
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() }

        } catch (e: Exception) {
            Log.w(TAG, "Embedding error (non-fatal): ${e.message}")
            null
        }
    }

    fun deleteMemory(id: Long): Boolean {
        return db.deleteMemory(id)
    }

    fun updateMemory(id: Long, newText: String): Boolean {
        return db.updateMemory(id, newText)
    }

    // ── Math ──────────────────────────────────────────────────────────────────

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0.0; var normA = 0.0; var normB = 0.0
        for (i in a.indices) {
            dot  += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }
}
