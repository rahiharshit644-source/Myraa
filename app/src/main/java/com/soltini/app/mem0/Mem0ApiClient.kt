package com.soltini.app.mem0

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for official Mem0 Platform REST API (https://api.mem0.ai).
 * Handles cloud sync, remote search, and remote updates when a Mem0 API key is configured.
 */
class Mem0ApiClient(
    private val apiKeyProvider: () -> String
) {
    companion object {
        private const val TAG = "Mem0ApiClient"
        private const val BASE_URL = "https://api.mem0.ai/v1/memories"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean {
        val key = apiKeyProvider().trim()
        return key.isNotBlank() && (key.startsWith("m0-") || key.length >= 16)
    }

    /**
     * Add conversation or statements to Mem0 cloud.
     */
    suspend fun addMemories(
        messages: List<Pair<String, String>>, // role to content
        userId: String = "boss",
        agentId: String = "myra",
        metadata: Map<String, String> = emptyMap()
    ): List<Mem0Resolution> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (!isConfigured()) return@withContext emptyList()

        try {
            val jsonBody = JSONObject().apply {
                val msgsArray = JSONArray()
                messages.forEach { (role, text) ->
                    msgsArray.put(JSONObject().apply {
                        put("role", role)
                        put("content", text)
                    })
                }
                put("messages", msgsArray)
                put("user_id", userId)
                put("agent_id", agentId)
                if (metadata.isNotEmpty()) {
                    put("metadata", JSONObject(metadata))
                }
            }

            val request = Request.Builder()
                .url(BASE_URL)
                .header("Authorization", "Token $apiKey")
                .header("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Mem0 API add failed HTTP ${response.code}: ${response.body?.string()}")
                return@withContext emptyList()
            }

            val respStr = response.body?.string() ?: return@withContext emptyList()
            val resolutions = mutableListOf<Mem0Resolution>()

            // Can be JSON array or object
            if (respStr.trim().startsWith("[")) {
                val arr = JSONArray(respStr)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val eventStr = item.optString("event", "ADD").uppercase()
                    val action = when (eventStr) {
                        "UPDATE" -> Mem0Action.UPDATE
                        "DELETE" -> Mem0Action.DELETE
                        "NOOP" -> Mem0Action.NOOP
                        else -> Mem0Action.ADD
                    }
                    resolutions.add(
                        Mem0Resolution(
                            action = action,
                            memory = item.optString("memory", ""),
                            targetId = item.optString("id", null),
                            category = item.optJSONArray("categories")?.optString(0) ?: "general",
                            reason = "Mem0 Cloud resolved: $eventStr"
                        )
                    )
                }
            }
            return@withContext resolutions
        } catch (e: Exception) {
            Log.e(TAG, "Error adding to Mem0 cloud: ${e.message}")
            return@withContext emptyList()
        }
    }

    /**
     * Search relevant memories on Mem0 cloud.
     */
    suspend fun searchMemories(
        query: String,
        userId: String = "boss",
        agentId: String = "myra",
        limit: Int = 6
    ): List<Mem0SearchResult> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (!isConfigured()) return@withContext emptyList()

        try {
            val jsonBody = JSONObject().apply {
                put("query", query)
                put("user_id", userId)
                put("agent_id", agentId)
                put("limit", limit)
            }

            val request = Request.Builder()
                .url("$BASE_URL/search/")
                .header("Authorization", "Token $apiKey")
                .header("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Mem0 API search failed HTTP ${response.code}")
                return@withContext emptyList()
            }

            val respStr = response.body?.string() ?: return@withContext emptyList()
            val results = mutableListOf<Mem0SearchResult>()

            if (respStr.trim().startsWith("[")) {
                val arr = JSONArray(respStr)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val text = item.optString("memory", "")
                    if (text.isBlank()) continue
                    val score = item.optDouble("score", 0.8).toFloat()
                    val catList = mutableListOf<String>()
                    val cats = item.optJSONArray("categories")
                    if (cats != null) {
                        for (c in 0 until cats.length()) {
                            catList.add(cats.optString(c))
                        }
                    }
                    val mem = Mem0Memory(
                        id = item.optString("id", java.util.UUID.randomUUID().toString()),
                        memory = text,
                        userId = userId,
                        agentId = agentId,
                        categories = if (catList.isNotEmpty()) catList else listOf("cloud"),
                        importance = score
                    )
                    results.add(Mem0SearchResult(mem, score, score))
                }
            }
            return@withContext results
        } catch (e: Exception) {
            Log.e(TAG, "Error searching Mem0 cloud: ${e.message}")
            return@withContext emptyList()
        }
    }

    /**
     * Delete memory on Mem0 cloud.
     */
    suspend fun deleteMemory(memoryId: String): Boolean = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (!isConfigured()) return@withContext false
        try {
            val request = Request.Builder()
                .url("$BASE_URL/$memoryId/")
                .header("Authorization", "Token $apiKey")
                .delete()
                .build()
            val response = httpClient.newCall(request).execute()
            return@withContext response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Failed deleting memory in Mem0 cloud: ${e.message}")
            return@withContext false
        }
    }
}
