package com.soltini.app.mem0

import android.util.Log
import com.soltini.app.util.AppLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Intelligent Deduplicator & Conflict Resolver for Mem0.
 *
 * Implements Mem0's 4-action decision matrix:
 *   - ADD: Completely new user fact or preference.
 *   - UPDATE: Replaces or updates an existing outdated memory (e.g. location moved, habit changed).
 *   - DELETE: Invalidates a memory that the user has stopped or contradicted.
 *   - NOOP: Avoids inserting duplicate memories when the fact is already known.
 */
class Mem0Deduplicator(
    private val geminiApiKeyProvider: () -> String
) {
    companion object {
        private const val TAG = "Mem0Deduplicator"
        private const val RESOLVE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-lite:generateContent"

        private val STOP_WORDS = setOf(
            "the", "a", "an", "is", "are", "was", "were", "to", "in", "at", "for", "of", "on", "and", "or",
            "my", "i", "me", "boss", "user", "likes", "preferred", "prefers", "ko", "ki", "ka", "ke", "hai",
            "tha", "thi", "mera", "meri", "mere", "mujhe", "that", "this", "it"
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Resolves a new candidate memory against existing active memories.
     * Tries Gemini LLM first; falls back to algorithmic fuzzy resolver if offline.
     */
    suspend fun resolveMemory(
        candidateFact: String,
        existingMemories: List<Mem0Memory>
    ): Mem0Resolution {
        val cleanFact = candidateFact.trim()
        if (cleanFact.isBlank()) {
            return Mem0Resolution(Mem0Action.NOOP, "", reason = "Empty candidate")
        }

        // Try LLM resolution if Gemini API key is available
        val apiKey = geminiApiKeyProvider().trim()
        if (apiKey.isNotBlank() && existingMemories.isNotEmpty()) {
            try {
                val llmResolution = resolveViaGemini(cleanFact, existingMemories, apiKey)
                if (llmResolution != null) {
                    return llmResolution
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Gemini Mem0 resolution error: ${e.message}")
                Log.w(TAG, "Gemini Mem0 resolution failed, falling back to local resolver: ${e.message}")
            }
        }

        // Fallback: Algorithmic Local Conflict & Deduplication Resolver
        return resolveLocally(cleanFact, existingMemories)
    }

    /**
     * Extracts memory statements from a multi-turn conversation using Mem0 principles.
     */
    suspend fun extractFromConversation(
        userMessage: String,
        assistantResponse: String,
        existingMemories: List<Mem0Memory>
    ): List<Mem0Resolution> {
        val apiKey = geminiApiKeyProvider().trim()
        if (apiKey.isBlank()) {
            // Local heuristic extraction
            val lower = userMessage.lowercase()
            if (lower.contains("remember") || lower.contains("yaad rakh") || lower.contains("prefer") || lower.contains("hamesha")) {
                val resolution = resolveLocally(userMessage, existingMemories)
                return listOf(resolution)
            }
            return emptyList()
        }

        return try {
            extractViaGemini(userMessage, assistantResponse, existingMemories, apiKey)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Mem0 conversation extraction failed: ${e.message}", e)
            Log.e(TAG, "Conversation extraction failed: ${e.message}")
            emptyList()
        }
    }

    // ─── Local Algorithmic Resolver (100% Offline Capable) ───────────────────

    fun resolveLocally(
        candidateFact: String,
        existingMemories: List<Mem0Memory>
    ): Mem0Resolution {
        val candidateTokens = tokenize(candidateFact)
        if (candidateTokens.isEmpty()) {
            return Mem0Resolution(Mem0Action.NOOP, candidateFact, reason = "No significant tokens")
        }

        var bestMatch: Mem0Memory? = null
        var bestSimilarity = 0.0f
        var isNegation = isNegationStatement(candidateFact)

        for (existing in existingMemories) {
            val existingTokens = tokenize(existing.memory)
            val similarity = computeJaccardSimilarity(candidateTokens, existingTokens)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestMatch = existing
            }
        }

        // 1. High similarity (> 0.70) without negation -> Duplicate! (NOOP)
        if (bestSimilarity >= 0.70f && !isNegation) {
            return Mem0Resolution(
                action = Mem0Action.NOOP,
                memory = candidateFact,
                targetId = bestMatch?.id,
                category = bestMatch?.categories?.firstOrNull() ?: "general",
                reason = "Identical or duplicate memory already exists (similarity: ${(bestSimilarity * 100).toInt()}%)",
                confidence = bestSimilarity
            )
        }

        // 2. High similarity with negation -> Contradiction! (DELETE)
        if (bestSimilarity >= 0.50f && isNegation) {
            return Mem0Resolution(
                action = Mem0Action.DELETE,
                memory = candidateFact,
                targetId = bestMatch?.id,
                category = bestMatch?.categories?.firstOrNull() ?: "general",
                reason = "User invalidated previous memory (${bestMatch?.memory})",
                confidence = 0.85f
            )
        }

        // 3. Entity / Topic overlap with different value -> Outdated Memory! (UPDATE)
        // E.g. "Lives in Delhi" vs "Lives in Mumbai" or "Preferred coffee: black" vs "Preferred coffee: latte"
        val topicMatch = findTopicMatch(candidateTokens, existingMemories)
        if (topicMatch != null) {
            return Mem0Resolution(
                action = Mem0Action.UPDATE,
                memory = candidateFact,
                targetId = topicMatch.id,
                category = topicMatch.categories.firstOrNull() ?: categorize(candidateFact),
                reason = "Updated outdated memory: replaces \"${topicMatch.memory}\"",
                confidence = 0.88f
            )
        }

        // 4. Brand new fact -> (ADD)
        return Mem0Resolution(
            action = Mem0Action.ADD,
            memory = candidateFact,
            category = categorize(candidateFact),
            reason = "New personal fact or preference discovered",
            confidence = 0.90f
        )
    }

    private fun findTopicMatch(candidateTokens: Set<String>, existingMemories: List<Mem0Memory>): Mem0Memory? {
        val topicKeywords = mapOf(
            "location" to listOf("live", "lives", "living", "stay", "city", "delhi", "mumbai", "bangalore", "noida", "rehta"),
            "work" to listOf("work", "works", "job", "company", "office", "developer", "engineer", "naukri", "kaam"),
            "diet" to listOf("coffee", "tea", "chai", "food", "eat", "vegetarian", "non-vegetarian", "vegan", "sugar", "diet", "khana"),
            "contact" to listOf("friend", "wife", "mom", "dad", "brother", "sister", "boss", "colleague", "contact"),
            "timing" to listOf("wake", "sleep", "morning", "night", "routine", "uthna", "sona"),
            "phone" to listOf("silent", "vibrate", "ringer", "volume", "torch", "brightness", "wallpaper")
        )

        for ((_, keywords) in topicKeywords) {
            val candidateMatches = candidateTokens.intersect(keywords.toSet())
            if (candidateMatches.isNotEmpty()) {
                for (mem in existingMemories) {
                    val memTokens = tokenize(mem.memory)
                    val memMatches = memTokens.intersect(keywords.toSet())
                    if (memMatches.isNotEmpty() && mem.memory.lowercase() != candidateTokens.joinToString(" ")) {
                        return mem
                    }
                }
            }
        }
        return null
    }

    private fun isNegationStatement(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("no longer") ||
               lower.contains("stopped") ||
               lower.contains("don't") ||
               lower.contains("do not") ||
               lower.contains("never") ||
               lower.contains("nahi") ||
               lower.contains("chhod diya") ||
               lower.contains("band kar diya")
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(" ", ",", ".", ";", ":", "-", "_", "!", "?")
            .map { it.trim() }
            .filter { it.length > 2 && !STOP_WORDS.contains(it) }
            .toSet()
    }

    private fun computeJaccardSimilarity(s1: Set<String>, s2: Set<String>): Float {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0f
        val intersection = s1.intersect(s2).size
        val union = s1.union(s2).size
        return if (union == 0) 0.0f else intersection.toFloat() / union.toFloat()
    }

    private fun categorize(fact: String): String {
        val lower = fact.lowercase()
        return when {
            lower.contains("food") || lower.contains("coffee") || lower.contains("tea") || lower.contains("eat") || lower.contains("khana") -> "diet"
            lower.contains("live") || lower.contains("city") || lower.contains("home") || lower.contains("house") || lower.contains("rehta") -> "location"
            lower.contains("work") || lower.contains("job") || lower.contains("project") || lower.contains("office") -> "work"
            lower.contains("habit") || lower.contains("sleep") || lower.contains("wake") || lower.contains("daily") || lower.contains("routine") -> "habits"
            lower.contains("phone") || lower.contains("silent") || lower.contains("torch") || lower.contains("volume") || lower.contains("app") -> "device_preferences"
            lower.contains("like") || lower.contains("favorite") || lower.contains("prefer") || lower.contains("pasand") -> "preferences"
            else -> "general"
        }
    }

    // ─── Gemini LLM-Powered Mem0 Conflict Resolver ───────────────────────────

    private fun resolveViaGemini(
        candidateFact: String,
        existingMemories: List<Mem0Memory>,
        apiKey: String
    ): Mem0Resolution? {
        val existingJson = JSONArray()
        existingMemories.take(15).forEach { mem ->
            existingJson.put(JSONObject().apply {
                put("id", mem.id)
                put("text", mem.memory)
                put("category", mem.categories.firstOrNull() ?: "general")
            })
        }

        val prompt = """You are the Mem0 Memory Conflict and Deduplication Engine for AI Assistant MYRA.
Evaluate this candidate fact against existing memories. Decide the single best Action:
- NOOP: The fact is already present in existing memories (exact or semantic duplicate). Do NOT duplicate.
- UPDATE: The fact modifies, refines, or replaces an outdated existing memory (e.g. location changed, habit updated).
- DELETE: The fact negates or contradicts an existing memory.
- ADD: The fact is brand new information not covered by existing memories.

EXISTING MEMORIES:
${existingJson.toString(2)}

NEW CANDIDATE FACT:
"$candidateFact"

OUTPUT FORMAT: Return a single valid JSON object ONLY:
{
  "action": "ADD" | "UPDATE" | "DELETE" | "NOOP",
  "memory": "<cleaned concise fact statement>",
  "target_id": "<id of existing memory if action is UPDATE or DELETE, otherwise null>",
  "category": "preferences" | "personal" | "habits" | "work" | "diet" | "device",
  "reason": "<short explanation>"
}"""

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
            .url("$RESOLVE_URL?key=$apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            val errBody = resp.body?.string() ?: ""
            AppLogger.w(TAG, "Gemini Mem0 resolve HTTP ${resp.code}: $errBody")
            return null
        }
        val resBody = resp.body?.string() ?: return null
        val candidates = JSONObject(resBody).optJSONArray("candidates")
        val text = candidates?.optJSONObject(0)?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: return null

        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = JSONObject(clean)

        val actionStr = json.optString("action", "ADD").uppercase()
        val action = when (actionStr) {
            "UPDATE" -> Mem0Action.UPDATE
            "DELETE" -> Mem0Action.DELETE
            "NOOP" -> Mem0Action.NOOP
            else -> Mem0Action.ADD
        }

        return Mem0Resolution(
            action = action,
            memory = json.optString("memory", candidateFact),
            targetId = json.optString("target_id", null).takeIf { it != "null" && it?.isNotBlank() == true },
            category = json.optString("category", "general"),
            reason = json.optString("reason", "Mem0 LLM resolved")
        )
    }

    private fun extractViaGemini(
        userMessage: String,
        assistantResponse: String,
        existingMemories: List<Mem0Memory>,
        apiKey: String
    ): List<Mem0Resolution> {
        val existingJson = JSONArray()
        existingMemories.take(15).forEach { mem ->
            existingJson.put(JSONObject().apply {
                put("id", mem.id)
                put("text", mem.memory)
            })
        }

        val prompt = """You are the Mem0 Memory Extraction Engine for MYRA.
Extract STABLE user preferences, personal details, habits, or rules from this interaction.
Ignore temporary ephemeral chatter, greetings, or single-use numbers.

EXISTING MEMORIES:
${existingJson.toString(2)}

CONVERSATION:
User: "$userMessage"
Assistant: "$assistantResponse"

Apply Mem0 principles:
- NOOP if already captured in existing memories.
- UPDATE if user's situation changed (e.g. moved city, new job).
- DELETE if contradicted.
- ADD if new stable fact.

If nothing stable to remember, return [].
Otherwise return a JSON array of objects:
[
  {
    "action": "ADD" | "UPDATE" | "DELETE" | "NOOP",
    "memory": "<concise fact statement, max 15 words>",
    "target_id": "<id if UPDATE/DELETE, else null>",
    "category": "preferences" | "personal" | "habits" | "work" | "diet" | "device",
    "reason": "<short explanation>"
  }
]
Output JSON ONLY:"""

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
            .url("$RESOLVE_URL?key=$apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            val errBody = resp.body?.string() ?: ""
            AppLogger.w(TAG, "Gemini Mem0 extraction HTTP ${resp.code}: $errBody")
            return emptyList()
        }
        val resBody = resp.body?.string() ?: return emptyList()
        val candidates = JSONObject(resBody).optJSONArray("candidates")
        val text = candidates?.optJSONObject(0)?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: return emptyList()

        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = JSONArray(clean)
        val list = mutableListOf<Mem0Resolution>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val actionStr = obj.optString("action", "ADD").uppercase()
            val action = when (actionStr) {
                "UPDATE" -> Mem0Action.UPDATE
                "DELETE" -> Mem0Action.DELETE
                "NOOP" -> Mem0Action.NOOP
                else -> Mem0Action.ADD
            }
            list.add(
                Mem0Resolution(
                    action = action,
                    memory = obj.optString("memory", ""),
                    targetId = obj.optString("target_id", null).takeIf { it != "null" && it?.isNotBlank() == true },
                    category = obj.optString("category", "general"),
                    reason = obj.optString("reason", "Mem0 extracted")
                )
            )
        }
        return list
    }
}
