package com.soltini.app.reach.planner

import com.soltini.app.reach.model.ResearchSource
import java.net.URI
import java.util.Locale

/**
 * ResearchDeduplicator
 *
 * Normalizes URLs (strips tracking tags like utm_*, ref),
 * eliminates duplicate sources, detects title similarity,
 * and manages LLM context budget limits.
 */
object ResearchDeduplicator {

    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "ref", "fbclid", "gclid", "msclkid", "mc_cid", "mc_eid"
    )

    /**
     * Normalizes a URL for deduplication purposes.
     */
    fun normalizeUrl(rawUrl: String): String {
        return try {
            val uri = URI(rawUrl.trim())
            val host = (uri.host ?: "").lowercase(Locale.ROOT).removePrefix("www.")
            val path = (uri.path ?: "").trimEnd('/')

            val query = uri.query
            val cleanQuery = if (!query.isNullOrBlank()) {
                val keptParams = query.split("&")
                    .map { it.split("=") }
                    .filter { it.isNotEmpty() && !TRACKING_PARAMS.contains(it[0].lowercase(Locale.ROOT)) }
                    .joinToString("&") { if (it.size > 1) "${it[0]}=${it[1]}" else it[0] }
                if (keptParams.isNotBlank()) "?$keptParams" else ""
            } else ""

            "${uri.scheme?.lowercase(Locale.ROOT) ?: "https"}://$host$path$cleanQuery"
        } catch (_: Exception) {
            rawUrl.trim().removeSuffix("/")
        }
    }

    /**
     * Deduplicates a list of sources by normalized URL and title similarity.
     */
    fun deduplicate(sources: List<ResearchSource>, maxSources: Int = 10): List<ResearchSource> {
        val seenUrls = mutableSetOf<String>()
        val seenTitles = mutableListOf<String>()
        val uniqueList = mutableListOf<ResearchSource>()

        for (source in sources) {
            val normalizedUrl = normalizeUrl(source.url)
            if (seenUrls.contains(normalizedUrl)) continue

            val cleanTitle = source.title.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]"""), " ")
            val isDuplicateTitle = seenTitles.any { wordOverlapScore(it, cleanTitle) > 0.85f }
            if (isDuplicateTitle) continue

            seenUrls.add(normalizedUrl)
            seenTitles.add(cleanTitle)
            uniqueList.add(source)

            if (uniqueList.size >= maxSources) break
        }

        return uniqueList
    }

    /**
     * Computes Jaccard word overlap between two titles.
     */
    private fun wordOverlapScore(s1: String, s2: String): Float {
        val words1 = s1.split(Regex("""\s+""")).filter { it.length > 2 }.toSet()
        val words2 = s2.split(Regex("""\s+""")).filter { it.length > 2 }.toSet()
        if (words1.isEmpty() || words2.isEmpty()) return 0f
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        return intersection.toFloat() / union.toFloat()
    }

    /**
     * Enforces token budgeting across all retrieved sources.
     * Prevents exceeding Gemini model context limits while maintaining source diversity.
     */
    fun budgetSources(sources: List<ResearchSource>, totalCharBudget: Int = 24000): List<ResearchSource> {
        if (sources.isEmpty()) return emptyList()
        val perSourceBudget = (totalCharBudget / sources.size).coerceAtLeast(1200)

        return sources.map { src ->
            val content = src.fullContent ?: src.snippet
            if (content.length > perSourceBudget) {
                val truncated = content.take(perSourceBudget) + "\n... [Remaining content budgeted]"
                src.copy(fullContent = truncated)
            } else {
                src
            }
        }
    }
}
