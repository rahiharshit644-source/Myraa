package com.soltini.app.reach

import com.soltini.app.reach.model.ResearchPlatform
import com.soltini.app.reach.model.ResearchSource
import com.soltini.app.reach.planner.ResearchDeduplicator
import org.junit.Assert.*
import org.junit.Test

class ResearchDeduplicatorTest {

    @Test
    fun testUrlNormalizationStripsTrackingParams() {
        val raw = "https://www.example.com/article?utm_source=twitter&utm_medium=social&ref=123"
        val normalized = ResearchDeduplicator.normalizeUrl(raw)
        assertEquals("https://example.com/article", normalized)
    }

    @Test
    fun testDeduplicationRemovesSameUrls() {
        val s1 = ResearchSource(
            title = "Article One",
            url = "https://example.com/ai?utm_source=news",
            platform = ResearchPlatform.WEB,
            snippet = "Snippet 1"
        )
        val s2 = ResearchSource(
            title = "Article One Copy",
            url = "https://example.com/ai",
            platform = ResearchPlatform.WEB,
            snippet = "Snippet 2"
        )
        val s3 = ResearchSource(
            title = "Different Article",
            url = "https://other.com/unique",
            platform = ResearchPlatform.WEB,
            snippet = "Snippet 3"
        )

        val unique = ResearchDeduplicator.deduplicate(listOf(s1, s2, s3))
        assertEquals(2, unique.size)
        assertEquals("https://example.com/ai?utm_source=news", unique[0].url)
        assertEquals("https://other.com/unique", unique[1].url)
    }

    @Test
    fun testTokenBudgetingTruncatesExcessContent() {
        val longContent = "A".repeat(15000)
        val src = ResearchSource(
            title = "Long Document",
            url = "https://example.com/long",
            platform = ResearchPlatform.WEB,
            snippet = "Short snippet",
            fullContent = longContent
        )

        val budgeted = ResearchDeduplicator.budgetSources(listOf(src), totalCharBudget = 5000)
        assertEquals(1, budgeted.size)
        assertTrue(budgeted[0].fullContent!!.length <= 5500)
        assertTrue(budgeted[0].fullContent!!.contains("Remaining content budgeted"))
    }
}
