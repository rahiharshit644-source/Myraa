package com.soltini.app.memory2

/**
 * FactResolver
 *
 * Decides what to do with a newly-extracted candidate fact against the facts
 * already stored in Long-Term Memory: ADD it, UPDATE an existing one, DELETE
 * an existing one (the user contradicted it), or NOOP (it's already known).
 *
 * This runs entirely offline (Jaccard token-overlap similarity) — no network
 * call, no extra LLM round-trip. It replaces the old mem0 package, which used
 * to make its own separate Gemini call per candidate fact on top of the one
 * that extracted the fact in the first place.
 */
enum class FactAction { ADD, UPDATE, DELETE, NOOP }

data class FactResolution(
    val action: FactAction,
    val fact: String,
    val targetId: String? = null,
    val reason: String = ""
)

object FactResolver {

    private val STOP_WORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "to", "in", "at", "for", "of", "on", "and", "or",
        "my", "i", "me", "boss", "user", "likes", "preferred", "prefers", "ko", "ki", "ka", "ke", "hai",
        "tha", "thi", "mera", "meri", "mere", "mujhe", "that", "this", "it"
    )

    private val TOPIC_KEYWORDS = mapOf(
        "location" to listOf("live", "lives", "living", "stay", "city", "delhi", "mumbai", "bangalore", "noida", "rehta"),
        "work" to listOf("work", "works", "job", "company", "office", "developer", "engineer", "naukri", "kaam"),
        "diet" to listOf("coffee", "tea", "chai", "food", "eat", "vegetarian", "non-vegetarian", "vegan", "sugar", "diet", "khana"),
        "contact" to listOf("friend", "wife", "mom", "dad", "brother", "sister", "boss", "colleague", "contact"),
        "timing" to listOf("wake", "sleep", "morning", "night", "routine", "uthna", "sona"),
        "phone" to listOf("silent", "vibrate", "ringer", "volume", "torch", "brightness", "wallpaper")
    )

    fun resolve(candidateFact: String, existing: List<MemoryItem>): FactResolution {
        val cleanFact = candidateFact.trim()
        if (cleanFact.isBlank()) return FactResolution(FactAction.NOOP, cleanFact, reason = "Empty candidate")

        val candidateTokens = tokenize(cleanFact)
        if (candidateTokens.isEmpty()) return FactResolution(FactAction.NOOP, cleanFact, reason = "No significant tokens")

        val isNegation = isNegationStatement(cleanFact)
        var bestMatch: MemoryItem? = null
        var bestSimilarity = 0.0f
        for (item in existing) {
            val sim = jaccard(candidateTokens, tokenize(item.content))
            if (sim > bestSimilarity) {
                bestSimilarity = sim
                bestMatch = item
            }
        }

        // Near-identical fact already stored -> skip
        if (bestSimilarity >= 0.70f && !isNegation) {
            return FactResolution(
                FactAction.NOOP, cleanFact, bestMatch?.id,
                "Duplicate of existing memory (similarity ${(bestSimilarity * 100).toInt()}%)"
            )
        }

        // Same topic, but phrased as a negation -> the user invalidated it
        if (bestSimilarity >= 0.50f && isNegation) {
            return FactResolution(
                FactAction.DELETE, cleanFact, bestMatch?.id,
                "User invalidated previous memory: \"${bestMatch?.content}\""
            )
        }

        // Same topic (e.g. "lives in") but a different value -> replaces the old one
        val topicMatch = findTopicMatch(candidateTokens, existing)
        if (topicMatch != null) {
            return FactResolution(
                FactAction.UPDATE, cleanFact, topicMatch.id,
                "Replaces outdated memory: \"${topicMatch.content}\""
            )
        }

        return FactResolution(FactAction.ADD, cleanFact, reason = "New fact")
    }

    private fun findTopicMatch(candidateTokens: Set<String>, existing: List<MemoryItem>): MemoryItem? {
        for ((_, keywords) in TOPIC_KEYWORDS) {
            if (candidateTokens.intersect(keywords.toSet()).isEmpty()) continue
            for (item in existing) {
                val itemTokens = tokenize(item.content)
                if (itemTokens.intersect(keywords.toSet()).isNotEmpty() &&
                    item.content.lowercase() != candidateTokens.joinToString(" ")
                ) {
                    return item
                }
            }
        }
        return null
    }

    private fun isNegationStatement(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("no longer") || lower.contains("stopped") || lower.contains("don't") ||
            lower.contains("do not") || lower.contains("never") || lower.contains("nahi") ||
            lower.contains("chhod diya") || lower.contains("band kar diya")
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(" ", ",", ".", ";", ":", "-", "_", "!", "?")
            .map { it.trim() }
            .filter { it.length > 2 && !STOP_WORDS.contains(it) }
            .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0f else inter.toFloat() / union.toFloat()
    }
}
