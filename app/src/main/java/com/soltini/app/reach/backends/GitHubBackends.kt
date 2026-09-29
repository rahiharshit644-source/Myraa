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

interface GitHubBackend : ReachBackend {
    suspend fun getRepositoryInfo(ownerAndRepo: String): ResearchSource
    suspend fun searchRepositories(query: String, maxResults: Int = 5): List<ResearchSource>
}

/**
 * GitHubRestBackend
 *
 * Direct GitHub REST API integration for repository inspection, README retrieval,
 * releases, and code repository searching.
 */
class GitHubRestBackend(
    private val httpClient: OkHttpClient,
    private val credentialStore: ReachCredentialStore
) : GitHubBackend {
    override val id = "github_rest"
    override val name = "GitHub REST API"
    override val description = "Official GitHub REST v3 API (repos, README, issues, search)"

    private fun buildRequest(url: String, accept: String = "application/vnd.github+json"): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "MyraAssistant-AgentReach/1.0")

        val token = credentialStore.getSecret(ReachCredentialStore.KEY_GITHUB_TOKEN)
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder.build()
    }

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        val hasToken = credentialStore.hasSecret(ReachCredentialStore.KEY_GITHUB_TOKEN)
        try {
            val req = buildRequest("https://api.github.com/zen")
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val authMsg = if (hasToken) "Authenticated (Token configured)" else "Public rate limit (60 req/hr)"
                    HealthStatus.READY to "GitHub API ready: $authMsg"
                } else if (resp.code == 403) {
                    HealthStatus.RATE_LIMITED to "GitHub API rate limit exceeded (Configure GitHub Token in Settings)"
                } else {
                    HealthStatus.ERROR to "GitHub API error HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            HealthStatus.NETWORK_UNAVAILABLE to "GitHub API unreachable: ${e.message}"
        }
    }

    private fun normalizeRepoSlug(input: String): String {
        var slug = input.trim()
        if (slug.contains("github.com/")) {
            slug = slug.substringAfter("github.com/").substringBefore("#").substringBefore("?")
        }
        return slug.trim('/')
    }

    override suspend fun getRepositoryInfo(ownerAndRepo: String): ResearchSource = withContext(Dispatchers.IO) {
        val slug = normalizeRepoSlug(ownerAndRepo)
        val repoUrl = "https://api.github.com/repos/$slug"

        val repoJson = httpClient.newCall(buildRequest(repoUrl)).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("GitHub repo lookup failed for '$slug' HTTP ${resp.code}")
            }
            JSONObject(resp.body?.string().orEmpty())
        }

        val name = repoJson.optString("full_name", slug)
        val description = repoJson.optString("description", "No description provided.")
        val stars = repoJson.optInt("stargazers_count", 0)
        val forks = repoJson.optInt("forks_count", 0)
        val language = repoJson.optString("language", "Not specified")
        val htmlUrl = repoJson.optString("html_url", "https://github.com/$slug")
        val defaultBranch = repoJson.optString("default_branch", "main")
        val topicsArr = repoJson.optJSONArray("topics") ?: JSONArray()
        val topics = (0 until topicsArr.length()).map { topicsArr.getString(it) }.joinToString(", ")

        // Fetch README
        var readmeContent = ""
        try {
            val readmeUrl = "https://api.github.com/repos/$slug/readme"
            val readmeReq = buildRequest(readmeUrl, accept = "application/vnd.github.raw+json")
            httpClient.newCall(readmeReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    readmeContent = resp.body?.string().orEmpty()
                }
            }
        } catch (_: Exception) {}

        val summaryContent = buildString {
            appendLine("Repository: $name")
            appendLine("URL: $htmlUrl")
            appendLine("Description: $description")
            appendLine("Stars: $stars | Forks: $forks | Primary Language: $language")
            if (topics.isNotBlank()) appendLine("Topics: $topics")
            appendLine("Default Branch: $defaultBranch")
            appendLine()
            if (readmeContent.isNotBlank()) {
                appendLine("=== README.md ===")
                appendLine(readmeContent.take(10000))
            }
        }

        ResearchSource(
            title = "$name ($stars ★)",
            url = htmlUrl,
            platform = ResearchPlatform.GITHUB,
            author = slug.substringBefore("/"),
            snippet = description,
            fullContent = summaryContent,
            reliability = SourceReliability.OFFICIAL,
            backendUsed = id,
            metadata = mapOf(
                "repo_slug" to slug,
                "stars" to stars.toString(),
                "forks" to forks.toString(),
                "language" to language
            )
        )
    }

    override suspend fun searchRepositories(query: String, maxResults: Int): List<ResearchSource> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val searchUrl = "https://api.github.com/search/repositories?q=$encoded&sort=stars&order=desc&per_page=$maxResults"

        httpClient.newCall(buildRequest(searchUrl)).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("GitHub search error HTTP ${resp.code}")
            }
            val json = JSONObject(resp.body?.string().orEmpty())
            val items = json.optJSONArray("items") ?: JSONArray()
            val list = mutableListOf<ResearchSource>()

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val fullName = item.optString("full_name")
                val desc = item.optString("description", "")
                val stars = item.optInt("stargazers_count", 0)
                val lang = item.optString("language", "")
                val url = item.optString("html_url")

                list.add(
                    ResearchSource(
                        title = "$fullName ($stars ★)",
                        url = url,
                        platform = ResearchPlatform.GITHUB,
                        author = fullName.substringBefore("/"),
                        snippet = if (lang.isNotBlank()) "[$lang] $desc" else desc,
                        fullContent = "Repository: $fullName\nURL: $url\nStars: $stars\nLanguage: $lang\nDescription: $desc",
                        reliability = SourceReliability.OFFICIAL,
                        backendUsed = id,
                        metadata = mapOf("stars" to stars.toString(), "language" to lang)
                    )
                )
            }
            list
        }
    }
}

/**
 * GitHubJinaFallbackBackend
 *
 * Reads GitHub repository page or files via Jina Reader fallback.
 */
class GitHubJinaFallbackBackend(
    private val httpClient: OkHttpClient
) : GitHubBackend {
    override val id = "github_jina"
    override val name = "GitHub Jina Reader Fallback"
    override val description = "Scrapes GitHub repositories and READMEs via Jina Reader (Zero-config)"

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        HealthStatus.READY to "GitHub Jina Reader fallback ready"
    }

    override suspend fun getRepositoryInfo(ownerAndRepo: String): ResearchSource = withContext(Dispatchers.IO) {
        val safeSlug = ownerAndRepo.trim().trim('/')
        val url = "https://github.com/$safeSlug"
        val jinaUrl = "https://r.jina.ai/$url"

        val req = Request.Builder()
            .url(jinaUrl)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Jina returned HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            val lines = text.lines()
            val title = lines.find { it.startsWith("Title:") }?.substringAfter("Title:")?.trim()
                ?: "GitHub Repo: $safeSlug"

            ResearchSource(
                title = title,
                url = url,
                platform = ResearchPlatform.GITHUB,
                snippet = text.take(400),
                fullContent = text.take(10000),
                reliability = SourceReliability.PRIMARY_SOURCE,
                backendUsed = id
            )
        }
    }

    override suspend fun searchRepositories(query: String, maxResults: Int): List<ResearchSource> = emptyList()
}
