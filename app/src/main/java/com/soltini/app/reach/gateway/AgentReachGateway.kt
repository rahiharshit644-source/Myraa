package com.soltini.app.reach.gateway

import android.content.Context
import android.util.Log
import com.soltini.app.reach.backends.*
import com.soltini.app.reach.doctor.AgentReachHealthManager
import com.soltini.app.reach.model.*
import com.soltini.app.reach.planner.ResearchDeduplicator
import com.soltini.app.reach.security.ReachCredentialStore
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

interface AgentReachGateway {
    fun getCapabilities(): List<ReachCapability>
    suspend fun getHealth(forceRefresh: Boolean = false): DiagnosticReport
    suspend fun searchWeb(query: String, maxResults: Int = 5): List<ResearchSource>
    suspend fun readWebPage(url: String): ResearchSource
    suspend fun searchYouTube(query: String, maxResults: Int = 5): List<ResearchSource>
    suspend fun getYouTubeContent(urlOrVideoId: String): ResearchSource
    suspend fun searchGitHub(query: String, maxResults: Int = 5): List<ResearchSource>
    suspend fun inspectGitHubRepository(ownerAndRepo: String): ResearchSource
    suspend fun searchReddit(query: String, subreddit: String? = null, maxResults: Int = 5): List<ResearchSource>
    suspend fun readRedditContent(urlOrPostId: String): ResearchSource
    suspend fun readRSS(feedUrl: String, maxItems: Int = 10): List<ResearchSource>
    suspend fun searchSocial(platform: ResearchPlatform, query: String, maxResults: Int = 5): List<ResearchSource>
    suspend fun readSocialContent(platform: ResearchPlatform, identifierOrUrl: String): ResearchSource
    suspend fun executeResearch(plan: ResearchPlan, onProgress: ((String) -> Unit)? = null): ResearchResult
    suspend fun runTestCheck(): org.json.JSONObject
    fun getActiveBackend(capabilityId: String): String?
    fun getCredentialStore(): ReachCredentialStore
    fun getCapabilityRegistry(): CapabilityRegistry
    fun getBackendRouter(): BackendRouter
}

/**
 * AgentReachGatewayImpl
 *
 * Primary entry point for MYRA's Internet Reach, Research, and External Retrieval capability.
 */
class AgentReachGatewayImpl private constructor(
    private val context: Context
) : AgentReachGateway {

    companion object {
        private const val TAG = "AgentReachGateway"

        @Volatile
        private var instance: AgentReachGatewayImpl? = null

        fun getInstance(context: Context): AgentReachGatewayImpl =
            instance ?: synchronized(this) {
                instance ?: AgentReachGatewayImpl(context.applicationContext).also { instance = it }
            }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val credentialStore = ReachCredentialStore.getInstance(context)
    private val capabilityRegistry = CapabilityRegistry()
    private val backendRouter = BackendRouter(capabilityRegistry)
    private val healthManager = AgentReachHealthManager(context, capabilityRegistry, backendRouter, credentialStore)

    init {
        // Register all built-in backends into the router
        backendRouter.registerBackend(DuckDuckGoHtmlSearchBackend(httpClient))
        backendRouter.registerBackend(JinaSearchBackend(httpClient))
        backendRouter.registerBackend(ExaSearchBackend(httpClient, credentialStore))
        backendRouter.registerBackend(WikipediaSearchBackend(httpClient))

        backendRouter.registerBackend(JinaReaderBackend(httpClient))
        backendRouter.registerBackend(DirectHttpReaderBackend(httpClient))

        backendRouter.registerBackend(YouTubeTranscriptBackend(httpClient))
        backendRouter.registerBackend(YouTubeOEmbedBackend(httpClient))
        backendRouter.registerBackend(YouTubeJinaFallbackBackend(httpClient))

        backendRouter.registerBackend(GitHubRestBackend(httpClient, credentialStore))
        backendRouter.registerBackend(GitHubJinaFallbackBackend(httpClient))

        backendRouter.registerBackend(RedditPublicJsonBackend(httpClient))
        backendRouter.registerBackend(RedditJinaFallbackBackend(httpClient))

        backendRouter.registerBackend(RssFeedBackend(httpClient))

        backendRouter.registerBackend(V2EXBackend(httpClient))
        backendRouter.registerBackend(BilibiliBackend(httpClient))
        backendRouter.registerBackend(XPlatformBackend(httpClient, credentialStore))
        backendRouter.registerBackend(GenericSocialJinaBackend(httpClient, ResearchPlatform.INSTAGRAM))
        backendRouter.registerBackend(GenericSocialJinaBackend(httpClient, ResearchPlatform.LINKEDIN))
        backendRouter.registerBackend(GenericSocialJinaBackend(httpClient, ResearchPlatform.FACEBOOK))
        backendRouter.registerBackend(GenericSocialJinaBackend(httpClient, ResearchPlatform.XIAOHONGSHU))
        backendRouter.registerBackend(GenericSocialJinaBackend(httpClient, ResearchPlatform.XUEQIU))
        backendRouter.registerBackend(GenericSocialJinaBackend(httpClient, ResearchPlatform.XIAOYUZHOU))

        backendRouter.registerBackend(CompanionBridgeBackend(httpClient, credentialStore))
    }

    override fun getCapabilities(): List<ReachCapability> = capabilityRegistry.getAllCapabilities()

    override suspend fun getHealth(forceRefresh: Boolean): DiagnosticReport =
        healthManager.runDoctorCheck(forceRefresh)

    override suspend fun searchWeb(query: String, maxResults: Int): List<ResearchSource> {
        val (results, _) = backendRouter.executeWebSearch(query, maxResults)
        return results
    }

    override suspend fun readWebPage(url: String): ResearchSource {
        val (source, _) = backendRouter.executeWebRead(url)
        return source
    }

    override suspend fun searchYouTube(query: String, maxResults: Int): List<ResearchSource> {
        val ytBackend = backendRouter.getBackend("youtube_transcript") as? YouTubeBackend
        return ytBackend?.searchVideos(query, maxResults) ?: emptyList()
    }

    override suspend fun getYouTubeContent(urlOrVideoId: String): ResearchSource {
        val (source, _) = backendRouter.executeYouTubeContent(urlOrVideoId)
        return source
    }

    override suspend fun searchGitHub(query: String, maxResults: Int): List<ResearchSource> {
        val gh = backendRouter.getBackend("github_rest") as? GitHubBackend
        return gh?.searchRepositories(query, maxResults) ?: emptyList()
    }

    override suspend fun inspectGitHubRepository(ownerAndRepo: String): ResearchSource {
        val (source, _) = backendRouter.executeGitHubInspection(ownerAndRepo)
        return source
    }

    override suspend fun searchReddit(query: String, subreddit: String?, maxResults: Int): List<ResearchSource> {
        val (results, _) = backendRouter.executeRedditSearch(query, subreddit, maxResults)
        return results
    }

    override suspend fun readRedditContent(urlOrPostId: String): ResearchSource {
        val (source, _) = backendRouter.executeRedditRead(urlOrPostId)
        return source
    }

    override suspend fun readRSS(feedUrl: String, maxItems: Int): List<ResearchSource> {
        val rss = backendRouter.getBackend("rss_feed_parser") as? RssBackend
        return rss?.parseFeed(feedUrl, maxItems) ?: emptyList()
    }

    override suspend fun searchSocial(platform: ResearchPlatform, query: String, maxResults: Int): List<ResearchSource> {
        return when (platform) {
            ResearchPlatform.V2EX -> {
                val b = backendRouter.getBackend("v2ex_public") as? SocialBackend
                b?.search(query, maxResults) ?: emptyList()
            }
            ResearchPlatform.TWITTER_X -> {
                val b = backendRouter.getBackend("x_twitter") as? SocialBackend
                b?.search(query, maxResults) ?: emptyList()
            }
            else -> searchWeb("site:${platform.name.lowercase()}.com $query", maxResults)
        }
    }

    override suspend fun readSocialContent(platform: ResearchPlatform, identifierOrUrl: String): ResearchSource {
        return when (platform) {
            ResearchPlatform.V2EX -> {
                val b = backendRouter.getBackend("v2ex_public") as? SocialBackend
                b?.readContent(identifierOrUrl) ?: readWebPage(identifierOrUrl)
            }
            ResearchPlatform.BILIBILI -> {
                val b = backendRouter.getBackend("bilibili_public") as? SocialBackend
                b?.readContent(identifierOrUrl) ?: readWebPage(identifierOrUrl)
            }
            ResearchPlatform.TWITTER_X -> {
                val b = backendRouter.getBackend("x_twitter") as? SocialBackend
                b?.readContent(identifierOrUrl) ?: readWebPage(identifierOrUrl)
            }
            else -> {
                val backendId = "${platform.name.lowercase()}_jina"
                val b = backendRouter.getBackend(backendId) as? SocialBackend
                b?.readContent(identifierOrUrl) ?: readWebPage(identifierOrUrl)
            }
        }
    }

    override suspend fun executeResearch(
        plan: ResearchPlan,
        onProgress: ((String) -> Unit)?
    ): ResearchResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val allSources = mutableListOf<ResearchSource>()
        val backendsUsed = mutableSetOf<String>()
        val warnings = mutableListOf<String>()

        onProgress?.invoke("Researching: Discovering sources across ${plan.targets.size} channel(s)...")

        // Concurrently query independent targets
        coroutineScope {
            val deferredList = plan.targets.map { target ->
                async {
                    try {
                        when (target.platform) {
                            ResearchPlatform.WEB -> {
                                onProgress?.invoke("Searching the web for '${target.query}'...")
                                val (results, backend) = backendRouter.executeWebSearch(target.query, maxResults = 5)
                                backendsUsed.add(backend)
                                results
                            }
                            ResearchPlatform.WEB_READER -> {
                                target.specificUrl?.let { url ->
                                    onProgress?.invoke("Reading web source: $url...")
                                    val (src, backend) = backendRouter.executeWebRead(url)
                                    backendsUsed.add(backend)
                                    listOf(src)
                                } ?: emptyList()
                            }
                            ResearchPlatform.YOUTUBE -> {
                                if (target.specificUrl != null) {
                                    onProgress?.invoke("Extracting YouTube video content & transcript...")
                                    val (src, backend) = backendRouter.executeYouTubeContent(target.specificUrl)
                                    backendsUsed.add(backend)
                                    listOf(src)
                                } else {
                                    onProgress?.invoke("Searching YouTube for videos...")
                                    searchYouTube(target.query, maxResults = 3)
                                }
                            }
                            ResearchPlatform.GITHUB -> {
                                if (target.specificUrl != null || (target.query.contains("/") && !target.query.contains(" "))) {
                                    onProgress?.invoke("Inspecting GitHub repository...")
                                    val slug = target.specificUrl ?: target.query
                                    val (src, backend) = backendRouter.executeGitHubInspection(slug)
                                    backendsUsed.add(backend)
                                    listOf(src)
                                } else {
                                    onProgress?.invoke("Searching GitHub repositories for '${target.query}'...")
                                    searchGitHub(target.query, maxResults = 3)
                                }
                            }
                            ResearchPlatform.REDDIT -> {
                                if (target.specificUrl != null) {
                                    onProgress?.invoke("Reading Reddit discussion thread...")
                                    val (src, backend) = backendRouter.executeRedditRead(target.specificUrl)
                                    backendsUsed.add(backend)
                                    listOf(src)
                                } else {
                                    onProgress?.invoke("Searching Reddit public discussions...")
                                    val (results, backend) = backendRouter.executeRedditSearch(target.query, maxResults = 4)
                                    backendsUsed.add(backend)
                                    results
                                }
                            }
                            ResearchPlatform.RSS -> {
                                target.specificUrl?.let { url ->
                                    onProgress?.invoke("Parsing RSS feed...")
                                    readRSS(url, maxItems = 5)
                                } ?: emptyList()
                            }
                            ResearchPlatform.V2EX, ResearchPlatform.BILIBILI, ResearchPlatform.TWITTER_X,
                            ResearchPlatform.INSTAGRAM, ResearchPlatform.LINKEDIN, ResearchPlatform.FACEBOOK,
                            ResearchPlatform.XIAOHONGSHU, ResearchPlatform.XUEQIU, ResearchPlatform.XIAOYUZHOU,
                            ResearchPlatform.COMPANION_CUSTOM -> {
                                if (target.specificUrl != null) {
                                    listOf(readSocialContent(target.platform, target.specificUrl))
                                } else {
                                    searchSocial(target.platform, target.query, maxResults = 3)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Target execution error for ${target.platform}: ${e.message}")
                        warnings.add("${target.platform.displayName}: ${e.message}")
                        emptyList<ResearchSource>()
                    }
                }
            }

            deferredList.forEach { deferred ->
                allSources.addAll(deferred.await())
            }
        }

        onProgress?.invoke("Deduplicating & normalizing retrieved sources...")
        val beforeCount = allSources.size
        val uniqueSources = ResearchDeduplicator.deduplicate(allSources, maxSources = plan.maxSourcesTotal)
        val budgetedSources = ResearchDeduplicator.budgetSources(uniqueSources)
        val deduplicatedCount = beforeCount - budgetedSources.size

        val duration = System.currentTimeMillis() - startTime
        val isSuccess = budgetedSources.isNotEmpty()

        // Calculate sources actually used by platform
        val sourcesUsedByPlatform = budgetedSources.groupBy { it.platform.displayName }.mapValues { it.value.size }
        val retrievedPlatforms = budgetedSources.map { it.platform }.toSet()
        val requestedPlatforms = plan.targets.map { it.platform }.toSet()
        val unavailablePlatforms = requestedPlatforms
            .filter { !retrievedPlatforms.contains(it) }
            .map { it.displayName }

        ResearchResult(
            planId = plan.id,
            query = plan.originalQuery,
            mode = plan.mode,
            sources = budgetedSources,
            deduplicatedCount = deduplicatedCount,
            durationMs = duration,
            isSuccess = isSuccess,
            error = if (!isSuccess) "Could not retrieve sources from any configured backend." else null,
            backendsAttempted = backendsUsed.toList(),
            warnings = warnings,
            sourcesUsedByPlatform = sourcesUsedByPlatform,
            unavailablePlatforms = unavailablePlatforms
        )
    }

    override suspend fun runTestCheck(): org.json.JSONObject =
        healthManager.runTestCheck()

    override fun getActiveBackend(capabilityId: String): String? =
        capabilityRegistry.getCapability(capabilityId)?.activeBackend

    override fun getCredentialStore(): ReachCredentialStore = credentialStore
    override fun getCapabilityRegistry(): CapabilityRegistry = capabilityRegistry
    override fun getBackendRouter(): BackendRouter = backendRouter
}
