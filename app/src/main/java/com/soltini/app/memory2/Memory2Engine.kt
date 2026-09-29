package com.soltini.app.memory2

import android.content.Context
import android.util.Log
import com.soltini.app.util.AppLogger
import com.soltini.app.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * RetrievedMemoryContext
 *
 * Scored, filtered, and ranked contextual memory returned to MYRA Orchestrator
 * (and to the live voice pipeline) for injection into the model's context.
 */
data class RetrievedMemoryContext(
    val relevantItems: List<MemoryItem>,
    val workingSummary: String,
    val sessionSummary: String,
    val experienceRecommendation: Memory2Database.ExperienceRecord? = null,
    val formattedPromptBlock: String
)

/**
 * Memory2Engine
 *
 * MYRA's single unified memory system. Everything the assistant remembers —
 * durable facts/preferences, session-to-session "what did we talk about"
 * history, working variables for the current task, project knowledge, and
 * learned task patterns — lives here, in one SQLite-backed store.
 *
 * This used to be split across three overlapping systems (a legacy
 * embedding-based `memory` package, this `memory2` package, and a separate
 * `mem0` package wrapped inside it) that each tried to decide independently
 * what was worth remembering, each firing their own extraction LLM call per
 * conversation turn. That redundancy is why recall felt unreliable — facts
 * could land in either store, and retrieval only ever checked one path with
 * a generic, non-contextual query. This class now owns the whole thing:
 *   - Working Memory   (in-memory, current task's scratch variables)
 *   - Session Log       (persisted, timestamped "what we discussed" history)
 *   - Long-Term Memory  (persisted, deduplicated durable facts/preferences)
 *   - Knowledge Memory  (persisted project docs/notes)
 *   - Experience Memory (persisted successful tool-sequence patterns)
 */
class Memory2Engine private constructor(
    private val context: Context,
    private val appSettings: AppSettings
) {

    companion object {
        private const val TAG = "Memory2Engine"
        private const val EXTRACT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-lite:generateContent"

        @Volatile
        private var instance: Memory2Engine? = null

        fun getInstance(context: Context, appSettings: AppSettings): Memory2Engine =
            instance ?: synchronized(this) {
                instance ?: Memory2Engine(context.applicationContext, appSettings).also { instance = it }
            }
    }

    val working = WorkingMemory()
    val session = SessionMemory()
    val db = Memory2Database(context)

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    init {
        // One-time: fold the old standalone mem0 SQLite store (if it exists from
        // a previous app version) into this database, then delete it.
        engineScope.launch {
            try {
                val migrated = db.migrateLegacyMem0Facts(context)
                if (migrated > 0) Log.i(TAG, "Migrated $migrated facts from legacy mem0 store")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Legacy mem0 migration skipped: ${e.message}")
            }
        }
    }

    // ─── Retrieval ────────────────────────────────────────────────────────────

    /**
     * Retrieves memory relevant to [userRequest] and formats it for injection
     * into the model's context.
     *
     * Two things are combined:
     * 1. Relevance-scored long-term facts & knowledge (matched against the
     *    actual request — always pass the real user utterance here, never a
     *    generic placeholder string, or nothing specific will ever surface).
     * 2. The most recent persisted session-log entries, included unconditionally
     *    regardless of their relevance score, with dates, so "what did we talk
     *    about last time" always has an answer even across app restarts.
     */
    suspend fun retrieveContext(userRequest: String, maxItems: Int = 8): RetrievedMemoryContext =
        withContext(Dispatchers.IO) {
            val queryTokens = userRequest.lowercase()
                .split(" ", "_", "-", ",", "?", "!")
                .filter { it.length > 2 }

            // 1. Long-Term memories, scored against the real query
            val allLongTerm = db.getAllLongTermMemories()
            val scoredLongTerm = mutableListOf<Pair<MemoryItem, Float>>()
            for (item in allLongTerm) {
                var relevance = 0.1f
                val contentLower = item.content.lowercase()
                val keyLower = item.key.lowercase()
                for (token in queryTokens) {
                    if (contentLower.contains(token)) relevance += 0.35f
                    if (keyLower.contains(token)) relevance += 0.45f
                }
                relevance = relevance.coerceIn(0f, 1.0f)

                // Include if relevant to this request, or important enough to always surface
                if (relevance >= 0.28f || item.importance >= 0.88f) {
                    val finalScore = item.computeScore(relevance)
                    scoredLongTerm.add(Pair(item, finalScore))
                    db.recordLongTermAccess(item.id)
                }
            }

            // 2. Knowledge memories scored
            val allKnowledge = db.getAllKnowledge()
            val scoredKnowledge = mutableListOf<Pair<MemoryItem, Float>>()
            for (k in allKnowledge) {
                var relevance = 0.1f
                val textLower = "${k.title} ${k.content} ${k.tags.joinToString(" ")}".lowercase()
                for (token in queryTokens) {
                    if (textLower.contains(token)) relevance += 0.4f
                }
                relevance = relevance.coerceIn(0f, 1.0f)
                if (relevance >= 0.3f) {
                    val item = MemoryItem(
                        id = k.id,
                        category = MemoryCategory.KNOWLEDGE,
                        key = k.title,
                        content = "[Doc: ${k.title}] ${k.content}",
                        importance = 0.7f,
                        relevance = relevance
                    )
                    scoredKnowledge.add(Pair(item, item.computeScore(relevance)))
                }
            }

            val topItems = (scoredLongTerm + scoredKnowledge)
                .sortedByDescending { it.second }
                .map { it.first }
                .take(maxItems)

            // 3. Experience Memory pattern
            val expRec = db.getBestExperienceForTask(userRequest.take(40))

            // 4. Summaries from Working and in-memory Session
            val workingSummary = working.getActiveWorkingContext().joinToString("; ") { "${it.key}=${it.content}" }
            val sessionSummary = session.getSessionSummary()

            // 5. Persisted session log — always included, unconditionally, so recall
            //    survives process restarts and doesn't depend on the query matching.
            val recentSessions = db.getRecentSessionLogEntries(5)

            // 6. Build clean prompt block
            val sb = StringBuilder()
            if (topItems.isNotEmpty()) {
                sb.append("### RELEVANT USER & KNOWLEDGE MEMORY:\n")
                topItems.forEach { mem ->
                    sb.append("• [${mem.category.name}] ${mem.content}\n")
                }
            }

            if (recentSessions.isNotEmpty()) {
                sb.append("\n### RECENT CONVERSATIONS (what we discussed before, most recent first):\n")
                recentSessions.forEach { entry ->
                    val dateStr = dateFormat.format(Date(entry.createdAt))
                    val detail = if (entry.summary.isNotBlank()) " — ${entry.summary}" else ""
                    sb.append("• [$dateStr] ${entry.topic}$detail\n")
                }
            }

            if (sessionSummary.isNotBlank()) {
                sb.append("\n### ACTIVE SESSION CONTEXT:\n$sessionSummary\n")
            }
            if (workingSummary.isNotBlank()) {
                sb.append("\n### WORKING VARIABLES:\n$workingSummary\n")
            }
            if (expRec != null && expRec.successRate >= 0.7f) {
                sb.append("\n### EXPERIENCE PATTERN (Learned):\nFor task \"${expRec.taskType}\", previous proven tool sequence: ${expRec.toolSequence.joinToString(" -> ")} (Success rate: ${(expRec.successRate * 100).toInt()}%)\n")
            }

            RetrievedMemoryContext(
                relevantItems = topItems,
                workingSummary = workingSummary,
                sessionSummary = sessionSummary,
                experienceRecommendation = expRec,
                formattedPromptBlock = sb.toString().trim()
            )
        }

    // ─── Post-Execution Learning (Memory Evaluation & Storage) ────────────────

    /**
     * Evaluates a completed turn/task and decides what should be persisted.
     * Called once per turn — from both the text-command orchestrator and the
     * live voice pipeline — with exactly ONE extraction network call (not
     * three), so results are consistent regardless of which path triggered it.
     */
    suspend fun evaluateAndStore(
        goal: String,
        outcomeSummary: String,
        toolSequence: List<String>,
        isSuccess: Boolean,
        userExplicitStatement: String? = null
    ) = withContext(Dispatchers.IO) {
        // 1. In-memory session state for the current process run
        session.recordCompletedTask(goal, outcomeSummary, isSuccess, toolSequence)
        session.recordTopic(goal.take(30))

        // 2. Persisted session log — survives restarts, always recallable
        db.saveSessionLogEntry(topic = goal.take(60), summary = outcomeSummary.take(300))

        // 3. Record Skill / Experience Pattern if tools were used
        if (toolSequence.isNotEmpty()) {
            val taskType = simplifyTaskType(goal)
            db.recordExperience(taskType, toolSequence, isSuccess)
            Log.d(TAG, "Recorded experience pattern for taskType=$taskType with tools=$toolSequence (success=$isSuccess)")
        }

        // 4. Explicit "remember that..." statements go straight to storage —
        //    no need for an LLM call, just local dedup resolution.
        if (!userExplicitStatement.isNullOrBlank()) {
            val lower = userExplicitStatement.lowercase()
            if (lower.contains("remember") || lower.contains("yaad rakh") || lower.contains("yaad rakhna") || lower.contains("hamesha")) {
                val cleanFact = userExplicitStatement
                    .replace(Regex("(?i)^(myra|soltini|hey myra),?\\s*"), "")
                    .replace(Regex("(?i)^(please|kripya)\\s*"), "")
                    .replace(Regex("(?i)^(remember that|yaad rakhna ki|yaad rakho ki)\\s*"), "")
                    .trim()
                if (cleanFact.isNotBlank()) {
                    resolveAndPersist(cleanFact, key = "user_explicit_preference", importance = 0.95f, confirmed = true)
                    return@withContext
                }
            }
        }

        // 5. Single extraction call: pulls out any stable, durable facts worth
        //    remembering from this turn. Replaces the old triple-redundant path
        //    (mem0's own extraction + a second, separate "stable preferences" call).
        val apiKey = appSettings.effectiveApiKey()
        if (apiKey.isNotBlank() && isSuccess && outcomeSummary.isNotBlank()) {
            val facts = extractCandidateFacts(goal, outcomeSummary, apiKey)
            facts.forEach { fact ->
                resolveAndPersist(fact, key = "learned_preference", importance = 0.75f)
            }
        }
    }

    /**
     * Resolves a candidate fact against existing long-term memory (ADD / UPDATE
     * an existing entry / DELETE a contradicted one / NOOP if already known)
     * and applies the decision. This is the one place dedup happens — offline,
     * no extra network round-trip.
     */
    private fun resolveAndPersist(
        candidateFact: String,
        key: String,
        importance: Float,
        confirmed: Boolean = false
    ) {
        val existing = db.getAllLongTermMemories()
        val resolution = FactResolver.resolve(candidateFact, existing)
        when (resolution.action) {
            FactAction.NOOP -> Log.d(TAG, "Fact already known, skipping: ${resolution.fact}")
            FactAction.DELETE -> {
                resolution.targetId?.let { db.deleteLongTermMemory(it) }
                Log.i(TAG, "Fact invalidated: ${resolution.reason}")
            }
            FactAction.UPDATE -> {
                val targetId = resolution.targetId
                if (targetId != null) {
                    db.updateLongTermMemory(targetId, resolution.fact, newImportance = importance)
                } else {
                    saveLongTermRaw(key, resolution.fact, importance, confirmed = confirmed)
                }
            }
            FactAction.ADD -> saveLongTermRaw(key, resolution.fact, importance, confirmed = confirmed)
        }
    }

    private fun saveLongTermRaw(
        key: String,
        fact: String,
        importance: Float,
        type: MemoryType = MemoryType.FACT,
        confidence: Float = 0.85f,
        confirmed: Boolean = false,
        tags: List<String> = emptyList()
    ): Boolean {
        val item = MemoryItem(
            type = type,
            category = MemoryCategory.LONG_TERM,
            key = key,
            content = fact,
            confidence = confidence,
            importance = importance,
            userConfirmed = confirmed,
            metadata = mapOf("tags" to tags.joinToString(","))
        )
        return db.saveLongTermMemory(item)
    }

    /** Saves a long-term memory item, going through dedup resolution first. */
    fun saveLongTerm(
        key: String,
        fact: String,
        importance: Float = 0.7f,
        type: MemoryType = MemoryType.FACT,
        confidence: Float = 0.85f,
        userConfirmed: Boolean = false,
        sensitivityLevel: String = "NORMAL",
        tags: List<String> = emptyList()
    ): Boolean {
        val existing = db.getAllLongTermMemories()
        val resolution = FactResolver.resolve(fact, existing)
        return when (resolution.action) {
            FactAction.NOOP -> true
            FactAction.DELETE -> {
                resolution.targetId?.let { db.deleteLongTermMemory(it) }
                true
            }
            FactAction.UPDATE -> {
                val targetId = resolution.targetId
                if (targetId != null) db.updateLongTermMemory(targetId, resolution.fact, newImportance = importance, newType = type)
                else saveLongTermRaw(key, resolution.fact, importance, type, confidence, userConfirmed, tags)
            }
            FactAction.ADD -> saveLongTermRaw(key, resolution.fact, importance, type, confidence, userConfirmed, tags)
        }
    }

    fun deleteMemory(id: String): Boolean = db.deleteLongTermMemory(id)

    fun updateMemory(id: String, newContent: String, newType: MemoryType? = null, newConfirmed: Boolean = true): Boolean =
        db.updateLongTermMemory(id, newContent, newType = newType)

    fun deleteMemoriesMatching(query: String): Int {
        var count = 0
        db.searchLongTermMemories(query).forEach { item ->
            if (deleteMemory(item.id)) count++
        }
        return count
    }

    fun getAllMemoriesUnified(): List<MemoryItem> =
        db.getAllLongTermMemories().sortedByDescending { it.updatedAt }

    fun searchAllMemories(query: String): List<MemoryItem> {
        if (query.isBlank()) return getAllMemoriesUnified()
        val q = query.lowercase().trim()
        return getAllMemoriesUnified().filter {
            it.content.lowercase().contains(q) ||
            it.key.lowercase().contains(q) ||
            it.type.name.lowercase().contains(q)
        }
    }

    fun searchMemoriesUnified(query: String): List<MemoryItem> = searchAllMemories(query)

    fun saveUserCorrection(correctionText: String, taskContext: String? = null): MemoryItem {
        val item = MemoryItem(
            type = MemoryType.CORRECTION,
            category = MemoryCategory.LONG_TERM,
            key = "user_correction",
            content = correctionText.trim(),
            confidence = 0.99f,
            importance = 0.95f,
            userConfirmed = true,
            metadata = if (taskContext != null) mapOf("task_context" to taskContext) else emptyMap()
        )
        db.saveLongTermMemory(item)
        return item
    }

    fun saveExplicitMemory(
        text: String,
        type: MemoryType = MemoryType.PREFERENCE,
        confirmed: Boolean = true
    ): MemoryItem {
        val item = MemoryItem(
            type = type,
            category = MemoryCategory.LONG_TERM,
            key = type.name.lowercase(),
            content = text.trim(),
            confidence = 0.98f,
            importance = 0.90f,
            userConfirmed = confirmed,
            metadata = mapOf("explicit" to "true")
        )
        db.saveLongTermMemory(item)
        return item
    }

    fun getMemoriesSummaryForUser(): String {
        val memories = getAllMemoriesUnified()
        if (memories.isEmpty()) {
            return "Boss, abhi meri memory me koi saved facts ya preferences nahi hain."
        }

        val sb = StringBuilder()
        sb.append("Boss, mujhe aapke baare mein yeh sab yaad hai:\n\n")

        val grouped = memories.groupBy { it.type }
        grouped.forEach { (type, items) ->
            val header = when (type) {
                MemoryType.PREFERENCE -> "🌟 Aapki Preferences:"
                MemoryType.CORRECTION -> "📌 Aapke Rules & Corrections:"
                MemoryType.PROJECT -> "🚀 Aapke Projects:"
                MemoryType.USER_PROFILE -> "👤 User Profile:"
                MemoryType.FACT -> "💡 Facts & Info:"
                MemoryType.ROUTINE -> "⏰ Daily Routines:"
                else -> "📝 General Notes:"
            }
            sb.append(header).append("\n")
            items.take(5).forEach { item ->
                sb.append("• ").append(item.content).append("\n")
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    /**
     * Saves a knowledge entry into Knowledge Memory.
     */
    fun saveKnowledge(title: String, content: String, source: String = "user_input", tags: List<String> = emptyList()) {
        db.saveKnowledge(
            Memory2Database.KnowledgeEntry(
                id = java.util.UUID.randomUUID().toString(),
                title = title,
                content = content,
                source = source,
                tags = tags,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun simplifyTaskType(goal: String): String {
        val lower = goal.lowercase()
        return when {
            lower.contains("weather") || lower.contains("mausam") -> "weather_lookup"
            lower.contains("crypto") || lower.contains("bitcoin") -> "crypto_lookup"
            lower.contains("whatsapp") -> "whatsapp_action"
            lower.contains("instagram") || lower.contains("insta") -> "instagram_action"
            lower.contains("youtube") -> "youtube_action"
            lower.contains("torch") || lower.contains("flashlight") -> "torch_action"
            lower.contains("volume") || lower.contains("sound") -> "volume_action"
            lower.contains("alarm") || lower.contains("timer") -> "clock_action"
            lower.contains("screenshot") -> "screenshot_action"
            lower.contains("screen") || lower.contains("dekho") -> "screen_vision"
            lower.contains("form") || lower.contains("fill") || lower.contains("shop") -> "screen_operator"
            else -> lower.split(" ").take(3).joinToString("_")
        }
    }

    /**
     * Single Gemini call that extracts durable, stable facts worth remembering
     * from a completed turn. This is the ONLY extraction call per turn now —
     * it used to be up to three separate calls across the old mem0 + legacy
     * memory systems, each independently deciding what to remember.
     */
    private fun extractCandidateFacts(goal: String, outcome: String, apiKey: String): List<String> {
        try {
            val prompt = """Evaluate if this task and result contains STABLE, LONG-TERM personal preferences or rules about the user.
Do NOT remember temporary information like weather forecasts, one-time flight numbers, or fleeting questions.
DO remember: dietary preferences, favorite teams, preferred contact names, recurring habit timings, communication style.

Task: $goal
Outcome: $outcome

If nothing stable to remember, return [].
If something stable should be remembered, return a JSON array of strings (max 15 words each).
Output JSON array ONLY:"""

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

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string() ?: ""
                AppLogger.w(TAG, "Memory extraction HTTP ${resp.code}: $errBody")
                return emptyList()
            }
            val resBody = resp.body?.string() ?: return emptyList()
            val candidates = JSONObject(resBody).optJSONArray("candidates")
            val text = candidates?.optJSONObject(0)?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: return emptyList()

            val clean = text.trim().removePrefix("```json").removeSuffix("```").trim()
            val arr = JSONArray(clean)
            val facts = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val fact = arr.getString(i)
                if (fact.isNotBlank()) facts.add(fact)
            }
            return facts
        } catch (e: Exception) {
            AppLogger.w(TAG, "Memory extraction error (non-fatal): ${e.message}")
            return emptyList()
        }
    }

    fun clearAllMemories() {
        db.clearAllMemories()
        session.clear()
        working.clear()
    }
}
