package com.soltini.app.reach.backends

import com.soltini.app.reach.model.HealthStatus
import com.soltini.app.reach.model.ResearchPlatform
import com.soltini.app.reach.model.ResearchSource
import com.soltini.app.reach.model.SourceReliability
import com.soltini.app.reach.security.ReachCredentialStore
import com.soltini.app.reach.security.ReachSecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

interface WebSearchBackend : ReachBackend {
    suspend fun search(query: String, maxResults: Int = 5): List<ResearchSource>
}

/**
 * DuckDuckGoHtmlSearchBackend
 *
 * Zero-config web search via public HTML search.
 */
class DuckDuckGoHtmlSearchBackend(
    private val httpClient: OkHttpClient
) : WebSearchBackend {
    override val id = "duckduckgo_html"
    override val name = "DuckDuckGo Web Search"
    override val description = "Zero-config public web search engine"

    private val titlePattern = Pattern.compile("<a[^>]*class=\"result__url\"[^>]*href=\"([^\"]+)\"[^>]*>", Pattern.CASE_INSENSITIVE)
    private val snippetPattern = Pattern.compile("<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE)
    private val resultTitlePattern = Pattern.compile("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE)

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://html.duckduckgo.com/html/?q=ping")
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    HealthStatus.READY to "DuckDuckGo web search ready (Zero-config)"
                } else {
                    HealthStatus.ERROR to "DuckDuckGo returned HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            HealthStatus.NETWORK_UNAVAILABLE to "DuckDuckGo probe failed: ${e.message}"
        }
    }

    override suspend fun search(query: String, maxResults: Int): List<ResearchSource> = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("q", query)
            .add("b", "")
            .add("kl", "us-en")
            .build()

        val req = Request.Builder()
            .url("https://html.duckduckgo.com/html/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .post(form)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("DuckDuckGo search error HTTP ${resp.code}")
            }
            val html = resp.body?.string().orEmpty()
            parseDuckDuckGoResults(html, query, maxResults)
        }
    }

    private fun parseDuckDuckGoResults(html: String, query: String, maxResults: Int): List<ResearchSource> {
        val results = mutableListOf<ResearchSource>()
        val titleMatcher = resultTitlePattern.matcher(html)
        val snippetMatcher = snippetPattern.matcher(html)

        val snippets = mutableListOf<String>()
        while (snippetMatcher.find()) {
            val cleanSnippet = snippetMatcher.group(1)?.replace(Regex("<[^>]*>"), "")?.trim().orEmpty()
            if (cleanSnippet.isNotBlank()) snippets.add(cleanSnippet)
        }

        var index = 0
        while (titleMatcher.find() && results.size < maxResults) {
            val rawLink = titleMatcher.group(1).orEmpty()
            val rawTitle = titleMatcher.group(2)?.replace(Regex("<[^>]*>"), "")?.trim().orEmpty()

            val actualUrl = extractActualUrl(rawLink)
            if (actualUrl.isNotBlank() && rawTitle.isNotBlank()) {
                val snippetText = if (index < snippets.size) snippets[index] else rawTitle
                try {
                    val safeUrl = ReachSecurityManager.validateAndNormalizePublicHttpUrl(actualUrl)
                    results.add(
                        ResearchSource(
                            title = rawTitle,
                            url = safeUrl,
                            platform = ResearchPlatform.WEB,
                            snippet = snippetText,
                            reliability = SourceReliability.SECONDARY_SOURCE,
                            backendUsed = id
                        )
                    )
                } catch (_: Exception) {}
            }
            index++
        }
        return results
    }

    private fun extractActualUrl(rawLink: String): String {
        return if (rawLink.contains("uddg=")) {
            val part = rawLink.substringAfter("uddg=").substringBefore("&")
            try {
                URLDecoder.decode(part, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                rawLink
            }
        } else if (rawLink.startsWith("//duckduckgo.com/l/?uddg=")) {
            val part = rawLink.substringAfter("uddg=").substringBefore("&")
            try {
                URLDecoder.decode(part, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                rawLink
            }
        } else if (rawLink.startsWith("http")) {
            rawLink
        } else {
            ""
        }
    }
}

/**
 * JinaSearchBackend
 *
 * Fallback / alternative search using Jina AI search engine (https://s.jina.ai/{query}).
 */
class JinaSearchBackend(
    private val httpClient: OkHttpClient
) : WebSearchBackend {
    override val id = "jina_search"
    override val name = "Jina AI Search"
    override val description = "Deep neural search with clean markdown extraction (s.jina.ai)"

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://s.jina.ai/test")
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (AI Agent Reach; Myra Assistant)")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    HealthStatus.READY to "Jina AI Search ready (Zero-config)"
                } else {
                    HealthStatus.SETUP_REQUIRED to "Jina Search returned HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            HealthStatus.NETWORK_UNAVAILABLE to "Jina Search unreachable: ${e.message}"
        }
    }

    override suspend fun search(query: String, maxResults: Int): List<ResearchSource> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val req = Request.Builder()
            .url("https://s.jina.ai/$encoded")
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (AI Agent Reach; Myra Assistant)")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Jina search returned HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            parseJinaResponse(body, maxResults)
        }
    }

    private fun parseJinaResponse(body: String, maxResults: Int): List<ResearchSource> {
        val results = mutableListOf<ResearchSource>()
        try {
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: JSONArray()
            for (i in 0 until minOf(data.length(), maxResults)) {
                val item = data.optJSONObject(i) ?: continue
                val title = item.optString("title", "Untitled Web Result")
                val url = item.optString("url")
                val description = item.optString("description", "")
                val content = item.optString("content", description)

                if (url.isNotBlank()) {
                    try {
                        val safeUrl = ReachSecurityManager.validateAndNormalizePublicHttpUrl(url)
                        results.add(
                            ResearchSource(
                                title = title,
                                url = safeUrl,
                                platform = ResearchPlatform.WEB,
                                snippet = description.ifBlank { content.take(300) },
                                fullContent = content.take(6000),
                                reliability = SourceReliability.SECONDARY_SOURCE,
                                backendUsed = id
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            // If response was pure markdown text rather than JSON
            val lines = body.lines()
            var currentTitle = ""
            var currentUrl = ""
            val snippetBuilder = StringBuilder()

            for (line in lines) {
                if (line.startsWith("Title:") || line.startsWith("## ")) {
                    if (currentUrl.isNotBlank() && results.size < maxResults) {
                        try {
                            val safeUrl = ReachSecurityManager.validateAndNormalizePublicHttpUrl(currentUrl)
                            results.add(
                                ResearchSource(
                                    title = currentTitle.ifBlank { "Web Source" },
                                    url = safeUrl,
                                    platform = ResearchPlatform.WEB,
                                    snippet = snippetBuilder.toString().take(400),
                                    reliability = SourceReliability.SECONDARY_SOURCE,
                                    backendUsed = id
                                )
                            )
                        } catch (_: Exception) {}
                    }
                    currentTitle = line.substringAfter(":").substringAfter("## ").trim()
                    currentUrl = ""
                    snippetBuilder.clear()
                } else if (line.startsWith("URL Source:") || line.startsWith("[Source]:") || line.contains("http://") || line.contains("https://")) {
                    val match = Regex("""https?://[^\s)]+""").find(line)
                    if (match != null && currentUrl.isBlank()) {
                        currentUrl = match.value
                    }
                } else if (line.isNotBlank() && !line.startsWith("Markdown Content:")) {
                    snippetBuilder.append(line).append(" ")
                }
            }
            if (currentUrl.isNotBlank() && results.size < maxResults) {
                try {
                    val safeUrl = ReachSecurityManager.validateAndNormalizePublicHttpUrl(currentUrl)
                    results.add(
                        ResearchSource(
                            title = currentTitle.ifBlank { "Web Source" },
                            url = safeUrl,
                            platform = ResearchPlatform.WEB,
                            snippet = snippetBuilder.toString().take(400),
                            reliability = SourceReliability.SECONDARY_SOURCE,
                            backendUsed = id
                        )
                    )
                } catch (_: Exception) {}
            }
        }
        return results
    }
}

/**
 * ExaSearchBackend
 *
 * High-precision neural AI search (requires free API key from Exa).
 */
class ExaSearchBackend(
    private val httpClient: OkHttpClient,
    private val credentialStore: ReachCredentialStore
) : WebSearchBackend {
    override val id = "exa_search"
    override val name = "Exa Neural Search"
    override val description = "Deep AI search for developer docs, code & papers (mcporter exa)"

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        val key = credentialStore.getSecret(ReachCredentialStore.KEY_EXA_API_KEY)
        if (key.isNullOrBlank()) {
            HealthStatus.SETUP_REQUIRED to "Exa API key not configured (free tier available at exa.ai)"
        } else {
            HealthStatus.READY to "Exa AI Search ready (API Key configured)"
        }
    }

    override suspend fun search(query: String, maxResults: Int): List<ResearchSource> = withContext(Dispatchers.IO) {
        val apiKey = credentialStore.getSecret(ReachCredentialStore.KEY_EXA_API_KEY)
            ?: throw IllegalStateException("Exa API Key not configured")

        val payload = JSONObject().apply {
            put("query", query)
            put("numResults", maxResults)
            put("useAutoprompt", true)
            put("type", "neural")
            put("contents", JSONObject().apply {
                put("text", true)
            })
        }

        val req = Request.Builder()
            .url("https://api.exa.ai/search")
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Exa API returned HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val resultsArr = json.optJSONArray("results") ?: JSONArray()
            val list = mutableListOf<ResearchSource>()

            for (i in 0 until resultsArr.length()) {
                val item = resultsArr.getJSONObject(i)
                val url = item.optString("url")
                val title = item.optString("title", "Exa Web Result")
                val text = item.optString("text", "")
                val author = item.optString("author").ifBlank { null }
                val publishedDate = item.optString("publishedDate").ifBlank { null }

                try {
                    val safeUrl = ReachSecurityManager.validateAndNormalizePublicHttpUrl(url)
                    list.add(
                        ResearchSource(
                            title = title,
                            url = safeUrl,
                            platform = ResearchPlatform.WEB,
                            author = author,
                            publishedAt = publishedDate,
                            snippet = text.take(400),
                            fullContent = text.take(6000),
                            reliability = SourceReliability.PRIMARY_SOURCE,
                            backendUsed = id
                        )
                    )
                } catch (_: Exception) {}
            }
            list
        }
    }
}

/**
 * WikipediaSearchBackend
 *
 * Encyclopedic fallback for verified factual and historical information.
 */
class WikipediaSearchBackend(
    private val httpClient: OkHttpClient
) : WebSearchBackend {
    override val id = "wikipedia_search"
    override val name = "Wikipedia Knowledge"
    override val description = "Verified encyclopedic entries via MediaWiki API"

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=test&format=json")
                .header("User-Agent", "MyraAssistant/1.0 (Mobile AI Agent)")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    HealthStatus.READY to "Wikipedia search ready (Zero-config)"
                } else {
                    HealthStatus.ERROR to "Wikipedia returned HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            HealthStatus.NETWORK_UNAVAILABLE to "Wikipedia unreachable: ${e.message}"
        }
    }

    override suspend fun search(query: String, maxResults: Int): List<ResearchSource> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val req = Request.Builder()
            .url("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srlimit=$maxResults&format=json")
            .header("User-Agent", "MyraAssistant/1.0 (Mobile AI Agent)")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Wikipedia search error HTTP ${resp.code}")
            }
            val json = JSONObject(resp.body?.string().orEmpty())
            val searchArr = json.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
            val list = mutableListOf<ResearchSource>()

            for (i in 0 until searchArr.length()) {
                val item = searchArr.getJSONObject(i)
                val title = item.optString("title", "Wikipedia Article")
                val pageId = item.optInt("pageid", 0)
                val snippetHtml = item.optString("snippet", "")
                val cleanSnippet = snippetHtml.replace(Regex("<[^>]*>"), "")
                val pageUrl = "https://en.wikipedia.org/wiki/${URLEncoder.encode(title.replace(" ", "_"), StandardCharsets.UTF_8.name())}"

                try {
                    val safeUrl = ReachSecurityManager.validateAndNormalizePublicHttpUrl(pageUrl)
                    list.add(
                        ResearchSource(
                            title = title,
                            url = safeUrl,
                            platform = ResearchPlatform.WEB,
                            snippet = cleanSnippet,
                            reliability = SourceReliability.OFFICIAL,
                            backendUsed = id,
                            metadata = mapOf("wikipedia_page_id" to pageId.toString())
                        )
                    )
                } catch (_: Exception) {}
            }
            list
        }
    }
}
