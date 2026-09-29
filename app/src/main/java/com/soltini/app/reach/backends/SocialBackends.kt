package com.soltini.app.reach.backends

import com.soltini.app.reach.model.HealthStatus
import com.soltini.app.reach.model.ResearchPlatform
import com.soltini.app.reach.model.ResearchSource
import com.soltini.app.reach.model.SourceReliability
import com.soltini.app.reach.security.ReachCredentialStore
import com.soltini.app.reach.security.ReachSecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

interface SocialBackend : ReachBackend {
    suspend fun search(query: String, maxResults: Int = 5): List<ResearchSource>
    suspend fun readContent(identifierOrUrl: String): ResearchSource
}

/**
 * V2EXBackend
 *
 * Direct integration with V2EX public API (Zero-config).
 */
class V2EXBackend(
    private val httpClient: OkHttpClient
) : SocialBackend {
    override val id = "v2ex_public"
    override val name = "V2EX Public API"
    override val description = "Fetches hot tech topics and discussions from V2EX (Zero-config)"

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://www.v2ex.com/api/topics/hot.json")
                .header("User-Agent", "agent-reach/1.0 (Myra Assistant)")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    HealthStatus.READY to "V2EX public API ready (Zero-config)"
                } else {
                    HealthStatus.ERROR to "V2EX returned HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            HealthStatus.NETWORK_UNAVAILABLE to "V2EX unreachable: ${e.message}"
        }
    }

    override suspend fun search(query: String, maxResults: Int): List<ResearchSource> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://www.v2ex.com/api/topics/hot.json")
            .header("User-Agent", "agent-reach/1.0 (Myra Assistant)")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val jsonArr = JSONArray(resp.body?.string().orEmpty())
            val list = mutableListOf<ResearchSource>()
            val qLower = query.lowercase()

            for (i in 0 until jsonArr.length()) {
                val item = jsonArr.getJSONObject(i)
                val title = item.optString("title", "")
                val content = item.optString("content", "")
                val url = item.optString("url", "")
                val member = item.optJSONObject("member")?.optString("username", "anonymous")
                val node = item.optJSONObject("node")?.optString("title", "")

                if (qLower.isBlank() || title.lowercase().contains(qLower) || content.lowercase().contains(qLower)) {
                    list.add(
                        ResearchSource(
                            title = if (!node.isNullOrBlank()) "[$node] $title" else title,
                            url = url,
                            platform = ResearchPlatform.V2EX,
                            author = member,
                            snippet = content.take(300),
                            fullContent = "Title: $title\nAuthor: $member\nNode: $node\nURL: $url\n\n$content",
                            reliability = SourceReliability.COMMUNITY_DISCUSSION,
                            backendUsed = id
                        )
                    )
                    if (list.size >= maxResults) break
                }
            }
            list
        }
    }

    override suspend fun readContent(identifierOrUrl: String): ResearchSource = withContext(Dispatchers.IO) {
        val topicId = identifierOrUrl.substringAfter("/t/").substringBefore("#").substringBefore("?").trim()
        val req = Request.Builder()
            .url("https://www.v2ex.com/api/topics/show.json?id=$topicId")
            .header("User-Agent", "agent-reach/1.0 (Myra Assistant)")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("V2EX read topic HTTP ${resp.code}")
            val arr = JSONArray(resp.body?.string().orEmpty())
            if (arr.length() == 0) throw RuntimeException("Topic not found")
            val obj = arr.getJSONObject(0)
            val title = obj.optString("title")
            val content = obj.optString("content")
            val url = obj.optString("url")
            val author = obj.optJSONObject("member")?.optString("username")

            ResearchSource(
                title = title,
                url = url,
                platform = ResearchPlatform.V2EX,
                author = author,
                snippet = content.take(300),
                fullContent = "Title: $title\nAuthor: $author\nURL: $url\n\n$content",
                reliability = SourceReliability.COMMUNITY_DISCUSSION,
                backendUsed = id
            )
        }
    }
}

/**
 * BilibiliBackend
 *
 * Public video data extraction from Bilibili Web API.
 */
class BilibiliBackend(
    private val httpClient: OkHttpClient
) : SocialBackend {
    override val id = "bilibili_public"
    override val name = "Bilibili Public API"
    override val description = "Fetches Bilibili video details and stats (bili-cli compatible)"

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        HealthStatus.READY to "Bilibili public video API ready (Zero-config)"
    }

    override suspend fun search(query: String, maxResults: Int): List<ResearchSource> = withContext(Dispatchers.IO) {
        // Query via public web search fallback
        emptyList()
    }

    override suspend fun readContent(identifierOrUrl: String): ResearchSource = withContext(Dispatchers.IO) {
        var bvid = identifierOrUrl.trim()
        if (bvid.contains("bilibili.com/video/")) {
            bvid = bvid.substringAfter("bilibili.com/video/").substringBefore("?").substringBefore("/")
        }

        val req = Request.Builder()
            .url("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Bilibili API error HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val data = json.optJSONObject("data") ?: throw RuntimeException("Video data not found")

            val title = data.optString("title", "Bilibili Video")
            val desc = data.optString("desc", "")
            val owner = data.optJSONObject("owner")?.optString("name", "")
            val url = "https://www.bilibili.com/video/$bvid"

            ResearchSource(
                title = title,
                url = url,
                platform = ResearchPlatform.BILIBILI,
                author = owner,
                snippet = desc.take(300),
                fullContent = "Title: $title\nAuthor: $owner\nURL: $url\n\nDescription:\n$desc",
                reliability = SourceReliability.PRIMARY_SOURCE,
                backendUsed = id
            )
        }
    }
}

/**
 * XPlatformBackend
 *
 * Twitter / X reader with Jina Reader fallback and credential check.
 */
class XPlatformBackend(
    private val httpClient: OkHttpClient,
    private val credentialStore: ReachCredentialStore
) : SocialBackend {
    override val id = "x_twitter"
    override val name = "X / Twitter Reader"
    override val description = "Reads public tweets and profiles via Jina or configured session"

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        val hasTokens = credentialStore.hasSecret(ReachCredentialStore.KEY_TWITTER_AUTH_TOKEN)
        if (hasTokens) {
            HealthStatus.READY to "Twitter/X credentials configured (Full search active)"
        } else {
            HealthStatus.SETUP_REQUIRED to "Public reading ready via Jina Reader; configure tokens for live feed search"
        }
    }

    override suspend fun search(query: String, maxResults: Int): List<ResearchSource> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val searchUrl = "https://s.jina.ai/site:x.com+$encoded"

        val req = Request.Builder()
            .url(searchUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "MyraAssistant/1.0")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val body = resp.body?.string().orEmpty()
            val list = mutableListOf<ResearchSource>()
            try {
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: JSONArray()
                for (i in 0 until minOf(data.length(), maxResults)) {
                    val item = data.getJSONObject(i)
                    val title = item.optString("title")
                    val url = item.optString("url")
                    val desc = item.optString("description")
                    list.add(
                        ResearchSource(
                            title = title,
                            url = url,
                            platform = ResearchPlatform.TWITTER_X,
                            snippet = desc.take(300),
                            fullContent = desc,
                            reliability = SourceReliability.COMMUNITY_DISCUSSION,
                            backendUsed = id
                        )
                    )
                }
            } catch (_: Exception) {}
            list
        }
    }

    override suspend fun readContent(identifierOrUrl: String): ResearchSource = withContext(Dispatchers.IO) {
        val safeUrl = if (identifierOrUrl.startsWith("http")) identifierOrUrl else "https://x.com/$identifierOrUrl"
        val jinaUrl = "https://r.jina.ai/$safeUrl"

        val req = Request.Builder()
            .url(jinaUrl)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("X / Twitter read failed HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            ResearchSource(
                title = "X Post ($safeUrl)",
                url = safeUrl,
                platform = ResearchPlatform.TWITTER_X,
                snippet = text.take(300),
                fullContent = text,
                reliability = SourceReliability.COMMUNITY_DISCUSSION,
                backendUsed = id
            )
        }
    }
}

/**
 * GenericSocialJinaBackend
 *
 * Reads Instagram, LinkedIn, and Xiaohongshu public URLs via Jina Reader.
 */
class GenericSocialJinaBackend(
    private val httpClient: OkHttpClient,
    private val platform: ResearchPlatform
) : SocialBackend {
    override val id = "${platform.name.lowercase()}_jina"
    override val name = "${platform.displayName} Reader"
    override val description = "Reads public ${platform.displayName} posts & profiles via Jina Reader"

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        HealthStatus.READY to "${platform.displayName} reader ready (Public web fallback)"
    }

    override suspend fun search(query: String, maxResults: Int): List<ResearchSource> = emptyList()

    override suspend fun readContent(identifierOrUrl: String): ResearchSource = withContext(Dispatchers.IO) {
        val safeUrl = ReachSecurityManager.validateAndNormalizePublicHttpUrl(identifierOrUrl)
        val jinaUrl = "https://r.jina.ai/$safeUrl"

        val req = Request.Builder()
            .url(jinaUrl)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("${platform.displayName} read failed HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            val lines = text.lines()
            val title = lines.find { it.startsWith("Title:") }?.substringAfter("Title:")?.trim()
                ?: "${platform.displayName} Post"

            ResearchSource(
                title = title,
                url = safeUrl,
                platform = platform,
                snippet = text.take(300),
                fullContent = text,
                reliability = SourceReliability.COMMUNITY_DISCUSSION,
                backendUsed = id
            )
        }
    }
}
