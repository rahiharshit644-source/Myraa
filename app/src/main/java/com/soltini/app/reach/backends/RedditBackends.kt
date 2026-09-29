package com.soltini.app.reach.backends

import com.soltini.app.reach.model.HealthStatus
import com.soltini.app.reach.model.ResearchPlatform
import com.soltini.app.reach.model.ResearchSource
import com.soltini.app.reach.model.SourceReliability
import com.soltini.app.reach.security.ReachSecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

interface RedditBackend : ReachBackend {
    suspend fun searchPosts(query: String, subreddit: String? = null, maxResults: Int = 5): List<ResearchSource>
    suspend fun readPost(urlOrPostId: String): ResearchSource
}

/**
 * RedditPublicJsonBackend
 *
 * Direct queries to Reddit's public .json endpoints with custom User-Agent.
 */
class RedditPublicJsonBackend(
    private val httpClient: OkHttpClient
) : RedditBackend {
    override val id = "reddit_public_json"
    override val name = "Reddit Public JSON"
    override val description = "Queries public Reddit JSON endpoints for discussions and top comments"

    companion object {
        private const val USER_AGENT = "android:com.soltini.app:v1.0 (by /u/MyraAssistant)"
    }

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://www.reddit.com/r/technology/hot.json?limit=1")
                .header("User-Agent", USER_AGENT)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    HealthStatus.READY to "Reddit Public JSON ready (Zero-config)"
                } else if (resp.code == 403 || resp.code == 429) {
                    HealthStatus.SETUP_REQUIRED to "Reddit returned HTTP ${resp.code} (Rate limited / Fallback active)"
                } else {
                    HealthStatus.ERROR to "Reddit returned HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            HealthStatus.NETWORK_UNAVAILABLE to "Reddit unreachable: ${e.message}"
        }
    }

    override suspend fun searchPosts(query: String, subreddit: String?, maxResults: Int): List<ResearchSource> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = if (!subreddit.isNullOrBlank()) {
            val cleanSub = subreddit.removePrefix("r/").trim()
            "https://www.reddit.com/r/$cleanSub/search.json?q=$encoded&restrict_sr=1&sort=relevance&limit=$maxResults"
        } else {
            "https://www.reddit.com/search.json?q=$encoded&sort=relevance&limit=$maxResults"
        }

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Reddit search failed with HTTP ${resp.code}")
            }
            val json = JSONObject(resp.body?.string().orEmpty())
            val children = json.optJSONObject("data")?.optJSONArray("children") ?: JSONArray()
            val list = mutableListOf<ResearchSource>()

            for (i in 0 until children.length()) {
                val post = children.getJSONObject(i).optJSONObject("data") ?: continue
                val title = post.optString("title", "Reddit Post")
                val sub = post.optString("subreddit", "reddit")
                val author = post.optString("author", "[deleted]")
                val score = post.optInt("score", 0)
                val numComments = post.optInt("num_comments", 0)
                val selftext = post.optString("selftext", "")
                val permalink = post.optString("permalink", "")
                val postUrl = "https://www.reddit.com$permalink"

                list.add(
                    ResearchSource(
                        title = "r/$sub: $title",
                        url = postUrl,
                        platform = ResearchPlatform.REDDIT,
                        author = author,
                        snippet = if (selftext.isNotBlank()) selftext.take(300) else "Reddit discussion with $numComments comments ($score upvotes).",
                        fullContent = "Title: $title\nSubreddit: r/$sub\nAuthor: u/$author\nScore: $score | Comments: $numComments\n\nContent:\n$selftext",
                        reliability = SourceReliability.COMMUNITY_DISCUSSION,
                        backendUsed = id,
                        metadata = mapOf("subreddit" to sub, "score" to score.toString(), "comments" to numComments.toString())
                    )
                )
            }
            list
        }
    }

    override suspend fun readPost(urlOrPostId: String): ResearchSource = withContext(Dispatchers.IO) {
        val targetUrl = if (urlOrPostId.startsWith("http")) {
            val base = urlOrPostId.substringBefore("?")
            if (!base.endsWith(".json")) "$base.json" else base
        } else {
            "https://www.reddit.com/comments/$urlOrPostId.json"
        }

        val req = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Reddit read post failed with HTTP ${resp.code}")
            }
            val jsonArr = JSONArray(resp.body?.string().orEmpty())
            if (jsonArr.length() == 0) throw RuntimeException("Empty response from Reddit")

            val postData = jsonArr.getJSONObject(0).getJSONObject("data").getJSONArray("children").getJSONObject(0).getJSONObject("data")
            val title = postData.optString("title", "Reddit Thread")
            val sub = postData.optString("subreddit", "")
            val author = postData.optString("author", "")
            val score = postData.optInt("score", 0)
            val selftext = postData.optString("selftext", "")
            val permalink = postData.optString("permalink", "")

            val commentsSb = StringBuilder()
            if (jsonArr.length() > 1) {
                val commentsChildren = jsonArr.getJSONObject(1).optJSONObject("data")?.optJSONArray("children") ?: JSONArray()
                commentsSb.appendLine("\n--- Top Community Comments ---")
                for (i in 0 until minOf(commentsChildren.length(), 6)) {
                    val c = commentsChildren.getJSONObject(i).optJSONObject("data") ?: continue
                    val cAuthor = c.optString("author", "anonymous")
                    val cBody = c.optString("body", "").trim()
                    val cScore = c.optInt("score", 0)
                    if (cBody.isNotBlank() && cBody != "[removed]" && cBody != "[deleted]") {
                        commentsSb.appendLine("• u/$cAuthor ($cScore pts): ${cBody.take(400)}")
                    }
                }
            }

            val fullText = buildString {
                appendLine("r/$sub - $title")
                appendLine("Author: u/$author | Score: $score")
                if (selftext.isNotBlank()) {
                    appendLine("\nPost Body:")
                    appendLine(selftext)
                }
                appendLine(commentsSb.toString())
            }

            ResearchSource(
                title = "r/$sub: $title",
                url = "https://www.reddit.com$permalink",
                platform = ResearchPlatform.REDDIT,
                author = author,
                snippet = selftext.take(300).ifBlank { "Reddit discussion with community replies" },
                fullContent = fullText,
                reliability = SourceReliability.COMMUNITY_DISCUSSION,
                backendUsed = id
            )
        }
    }
}

/**
 * RedditJinaFallbackBackend
 *
 * Fallback to read Reddit threads via Jina Reader.
 */
class RedditJinaFallbackBackend(
    private val httpClient: OkHttpClient
) : RedditBackend {
    override val id = "reddit_jina"
    override val name = "Reddit Jina Fallback"
    override val description = "Scrapes public Reddit discussion threads via Jina Reader"

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        HealthStatus.READY to "Reddit Jina fallback ready"
    }

    override suspend fun searchPosts(query: String, subreddit: String?, maxResults: Int): List<ResearchSource> = emptyList()

    override suspend fun readPost(urlOrPostId: String): ResearchSource = withContext(Dispatchers.IO) {
        val targetUrl = if (urlOrPostId.startsWith("http")) urlOrPostId else "https://www.reddit.com/comments/$urlOrPostId"
        val jinaUrl = "https://r.jina.ai/$targetUrl"

        val req = Request.Builder()
            .url(jinaUrl)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Jina Reddit read failed HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            val lines = text.lines()
            val title = lines.find { it.startsWith("Title:") }?.substringAfter("Title:")?.trim()
                ?: "Reddit Discussion"

            ResearchSource(
                title = title,
                url = targetUrl,
                platform = ResearchPlatform.REDDIT,
                snippet = text.take(300),
                fullContent = text.take(8000),
                reliability = SourceReliability.COMMUNITY_DISCUSSION,
                backendUsed = id
            )
        }
    }
}
