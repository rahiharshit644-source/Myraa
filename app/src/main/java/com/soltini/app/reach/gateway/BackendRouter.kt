package com.soltini.app.reach.gateway

import android.util.Log
import com.soltini.app.reach.backends.*
import com.soltini.app.reach.model.HealthStatus
import com.soltini.app.reach.model.ResearchSource
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * BackendRouter
 *
 * Implements Agent Reach's core architectural principle:
 * Ordered candidate backends (Primary + Fallbacks) with automatic retry and degradation.
 * The rest of MYRA never needs to know which backend serves a capability.
 */
class BackendRouter(
    private val capabilityRegistry: CapabilityRegistry
) {
    companion object {
        private const val TAG = "BackendRouter"
        private const val MAX_FALLBACK_ATTEMPTS = 3
    }

    private val backends = ConcurrentHashMap<String, ReachBackend>()

    fun registerBackend(backend: ReachBackend) {
        backends[backend.id] = backend
    }

    fun getBackend(id: String): ReachBackend? = backends[id]

    /**
     * Executes a web search with automatic fallback across candidate backends:
     * Exa (if key) -> DuckDuckGo -> Jina Search -> Wikipedia
     */
    suspend fun executeWebSearch(query: String, maxResults: Int = 5): Pair<List<ResearchSource>, String> {
        val capability = capabilityRegistry.getCapability("web.search")
        val candidateIds = capability?.backends ?: listOf("duckduckgo_html", "jina_search", "wikipedia_search")

        val errors = mutableListOf<String>()
        var attempts = 0

        for (backendId in candidateIds) {
            if (attempts >= MAX_FALLBACK_ATTEMPTS) break
            val backend = backends[backendId] as? WebSearchBackend ?: continue

            try {
                Log.d(TAG, "Attempting Web Search via backend: $backendId for query '$query'")
                val results = backend.search(query, maxResults)
                if (results.isNotEmpty()) {
                    capabilityRegistry.updateActiveBackend("web.search", backendId)
                    return results to backendId
                } else {
                    errors.add("$backendId returned 0 results")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Backend $backendId failed: ${e.message}. Trying next fallback...")
                errors.add("$backendId: ${e.message}")
            }
            attempts++
            delay(200L) // Small breather before fallback
        }

        throw RuntimeException("All web search backends failed: ${errors.joinToString("; ")}")
    }

    /**
     * Executes a web page read with automatic fallback:
     * Jina Reader -> Direct HTTP Reader
     */
    suspend fun executeWebRead(url: String): Pair<ResearchSource, String> {
        val capability = capabilityRegistry.getCapability("web.read")
        val candidateIds = capability?.backends ?: listOf("jina_reader", "direct_http_reader")

        val errors = mutableListOf<String>()
        var attempts = 0

        for (backendId in candidateIds) {
            if (attempts >= MAX_FALLBACK_ATTEMPTS) break
            val backend = backends[backendId] as? WebReaderBackend ?: continue

            try {
                Log.d(TAG, "Attempting Web Read via backend: $backendId for URL '$url'")
                val source = backend.read(url)
                capabilityRegistry.updateActiveBackend("web.read", backendId)
                return source to backendId
            } catch (e: Exception) {
                Log.w(TAG, "Backend $backendId failed: ${e.message}. Trying next fallback...")
                errors.add("$backendId: ${e.message}")
            }
            attempts++
            delay(200L)
        }

        throw RuntimeException("All web reader backends failed: ${errors.joinToString("; ")}")
    }

    /**
     * Executes YouTube transcript / metadata extraction with automatic fallback:
     * YouTube Transcript -> YouTube oEmbed -> YouTube Jina Fallback
     */
    suspend fun executeYouTubeContent(urlOrVideoId: String): Pair<ResearchSource, String> {
        val candidateIds = listOf("youtube_transcript", "youtube_oembed", "youtube_jina")
        val errors = mutableListOf<String>()

        for (backendId in candidateIds) {
            val backend = backends[backendId] as? YouTubeBackend ?: continue
            try {
                Log.d(TAG, "Attempting YouTube extraction via backend: $backendId")
                val source = backend.getVideoContent(urlOrVideoId)
                capabilityRegistry.updateActiveBackend("youtube.content", backendId)
                return source to backendId
            } catch (e: Exception) {
                Log.w(TAG, "YouTube backend $backendId failed: ${e.message}. Trying next fallback...")
                errors.add("$backendId: ${e.message}")
            }
        }

        throw RuntimeException("All YouTube backends failed: ${errors.joinToString("; ")}")
    }

    /**
     * Executes GitHub repository inspection with automatic fallback:
     * GitHub REST API -> GitHub Jina Reader
     */
    suspend fun executeGitHubInspection(ownerAndRepo: String): Pair<ResearchSource, String> {
        val candidateIds = listOf("github_rest", "github_jina")
        val errors = mutableListOf<String>()

        for (backendId in candidateIds) {
            val backend = backends[backendId] as? GitHubBackend ?: continue
            try {
                Log.d(TAG, "Attempting GitHub repo inspection via backend: $backendId")
                val source = backend.getRepositoryInfo(ownerAndRepo)
                capabilityRegistry.updateActiveBackend("github.inspect", backendId)
                return source to backendId
            } catch (e: Exception) {
                Log.w(TAG, "GitHub backend $backendId failed: ${e.message}. Trying next fallback...")
                errors.add("$backendId: ${e.message}")
            }
        }

        throw RuntimeException("All GitHub backends failed: ${errors.joinToString("; ")}")
    }

    /**
     * Executes Reddit discussion post / search with fallback:
     * Reddit Public JSON -> Reddit Jina Reader
     */
    suspend fun executeRedditRead(urlOrPostId: String): Pair<ResearchSource, String> {
        val candidateIds = listOf("reddit_public_json", "reddit_jina")
        val errors = mutableListOf<String>()

        for (backendId in candidateIds) {
            val backend = backends[backendId] as? RedditBackend ?: continue
            try {
                Log.d(TAG, "Attempting Reddit read via backend: $backendId")
                val source = backend.readPost(urlOrPostId)
                capabilityRegistry.updateActiveBackend("reddit.discussions", backendId)
                return source to backendId
            } catch (e: Exception) {
                Log.w(TAG, "Reddit backend $backendId failed: ${e.message}. Trying next fallback...")
                errors.add("$backendId: ${e.message}")
            }
        }

        throw RuntimeException("All Reddit backends failed: ${errors.joinToString("; ")}")
    }

    /**
     * Executes Reddit search with fallback.
     */
    suspend fun executeRedditSearch(query: String, subreddit: String? = null, maxResults: Int = 5): Pair<List<ResearchSource>, String> {
        val redditJson = backends["reddit_public_json"] as? RedditBackend
        if (redditJson != null) {
            try {
                val results = redditJson.searchPosts(query, subreddit, maxResults)
                if (results.isNotEmpty()) {
                    capabilityRegistry.updateActiveBackend("reddit.discussions", redditJson.id)
                    return results to redditJson.id
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reddit search failed: ${e.message}. Trying Web search fallback...")
            }
        }

        // Fallback: search Reddit via Web Search
        val (webResults, bId) = executeWebSearch("site:reddit.com $query", maxResults)
        return webResults to "web_search_fallback ($bId)"
    }
}
