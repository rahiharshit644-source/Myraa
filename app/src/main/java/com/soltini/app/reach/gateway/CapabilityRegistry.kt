package com.soltini.app.reach.gateway

import com.soltini.app.reach.model.*

/**
 * CapabilityRegistry
 *
 * Master registry of all 16 platforms and capabilities in Agent Reach.
 * Defines ordered candidate backends (Primary + Fallbacks), tiers, and auth requirements.
 */
class CapabilityRegistry {

    private val capabilities = mutableMapOf<String, ReachCapability>()

    init {
        registerDefaultCapabilities()
    }

    private fun registerDefaultCapabilities() {
        // ── TIER 0: ZERO CONFIGURATION (Ready immediately out of the box) ──

        // 1. Web Search
        register(
            ReachCapability(
                id = "web.search",
                name = "Web Search",
                description = "Universal web search across multiple search engines",
                platform = ResearchPlatform.WEB,
                tier = 0,
                supportedOperations = listOf("SEARCH_WEB", "SEARCH_CODE", "SEARCH_DOCUMENTATION"),
                backends = listOf("duckduckgo_html", "jina_search", "wikipedia_search", "exa_search"),
                activeBackend = "duckduckgo_html",
                authRequirement = AuthenticationRequirement.NONE,
                authState = AuthenticationState.NOT_REQUIRED,
                healthStatus = HealthStatus.READY,
                statusMessage = "Zero-config web search via DuckDuckGo and Jina AI",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // 2. Web Reader
        register(
            ReachCapability(
                id = "web.read",
                name = "Web Reader",
                description = "Converts any public web page into structured, readable content",
                platform = ResearchPlatform.WEB_READER,
                tier = 0,
                supportedOperations = listOf("READ_URL", "EXTRACT_ARTICLE"),
                backends = listOf("jina_reader", "direct_http_reader"),
                activeBackend = "jina_reader",
                authRequirement = AuthenticationRequirement.NONE,
                authState = AuthenticationState.NOT_REQUIRED,
                healthStatus = HealthStatus.READY,
                statusMessage = "Zero-config web reading via Jina Reader (r.jina.ai)",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // 3. YouTube Subtitles & Transcripts
        register(
            ReachCapability(
                id = "youtube.content",
                name = "YouTube Video & Transcripts",
                description = "Extracts verified multi-language subtitles, summaries & video metadata",
                platform = ResearchPlatform.YOUTUBE,
                tier = 0,
                supportedOperations = listOf("GET_TRANSCRIPT", "SEARCH_VIDEOS", "GET_METADATA"),
                backends = listOf("youtube_transcript", "youtube_oembed", "youtube_jina"),
                activeBackend = "youtube_transcript",
                authRequirement = AuthenticationRequirement.NONE,
                authState = AuthenticationState.NOT_REQUIRED,
                healthStatus = HealthStatus.READY,
                statusMessage = "Zero-config subtitle and transcript extraction",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // 4. GitHub Repositories & Code
        register(
            ReachCapability(
                id = "github.inspect",
                name = "GitHub Repositories & Code",
                description = "Analyzes repositories, READMEs, issues, releases, and architecture",
                platform = ResearchPlatform.GITHUB,
                tier = 0,
                supportedOperations = listOf("INSPECT_REPO", "READ_README", "SEARCH_REPOS"),
                backends = listOf("github_rest", "github_jina"),
                activeBackend = "github_rest",
                authRequirement = AuthenticationRequirement.NONE,
                authState = AuthenticationState.NOT_REQUIRED,
                healthStatus = HealthStatus.READY,
                statusMessage = "Zero-config GitHub REST inspection (Token optional for high quota)",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // 5. Reddit Public Discussions
        register(
            ReachCapability(
                id = "reddit.discussions",
                name = "Reddit Public Discussions",
                description = "Searches community discussions, posts, and top comments",
                platform = ResearchPlatform.REDDIT,
                tier = 0,
                supportedOperations = listOf("SEARCH_POSTS", "READ_THREAD", "READ_COMMENTS"),
                backends = listOf("reddit_public_json", "reddit_jina"),
                activeBackend = "reddit_public_json",
                authRequirement = AuthenticationRequirement.NONE,
                authState = AuthenticationState.NOT_REQUIRED,
                healthStatus = HealthStatus.READY,
                statusMessage = "Zero-config public thread and comment search",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // 6. RSS & Atom Feeds
        register(
            ReachCapability(
                id = "rss.feed",
                name = "RSS & News Feeds",
                description = "Reads blogs, news feeds, podcasts, and release feeds directly",
                platform = ResearchPlatform.RSS,
                tier = 0,
                supportedOperations = listOf("READ_FEED", "GET_LATEST_POSTS"),
                backends = listOf("rss_feed_parser", "jina_reader"),
                activeBackend = "rss_feed_parser",
                authRequirement = AuthenticationRequirement.NONE,
                authState = AuthenticationState.NOT_REQUIRED,
                healthStatus = HealthStatus.READY,
                statusMessage = "Zero-config RSS/Atom XML feed parsing",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // 7. V2EX Tech Community
        register(
            ReachCapability(
                id = "v2ex.topics",
                name = "V2EX Discussions",
                description = "Public developer discussions, hot topics, and tech questions",
                platform = ResearchPlatform.V2EX,
                tier = 0,
                supportedOperations = listOf("GET_HOT_TOPICS", "READ_TOPIC"),
                backends = listOf("v2ex_public"),
                activeBackend = "v2ex_public",
                authRequirement = AuthenticationRequirement.NONE,
                authState = AuthenticationState.NOT_REQUIRED,
                healthStatus = HealthStatus.READY,
                statusMessage = "Zero-config V2EX public API",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // 8. Bilibili Video Details
        register(
            ReachCapability(
                id = "bilibili.video",
                name = "Bilibili Video Info",
                description = "Fetches Bilibili video metadata, stats, and summaries",
                platform = ResearchPlatform.BILIBILI,
                tier = 0,
                supportedOperations = listOf("READ_VIDEO_METADATA"),
                backends = listOf("bilibili_public", "jina_reader"),
                activeBackend = "bilibili_public",
                authRequirement = AuthenticationRequirement.NONE,
                authState = AuthenticationState.NOT_REQUIRED,
                healthStatus = HealthStatus.READY,
                statusMessage = "Zero-config Bilibili public video lookup",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // ── TIER 1: KEY-SUPPORTED / LOGIN-BACKED CHANNELS ──

        // 9. X / Twitter
        register(
            ReachCapability(
                id = "x.search",
                name = "X / Twitter",
                description = "Public post search and thread reading (cookies/tokens supported)",
                platform = ResearchPlatform.TWITTER_X,
                tier = 1,
                supportedOperations = listOf("SEARCH_TWEETS", "READ_TWEET", "READ_PROFILE"),
                backends = listOf("x_twitter", "jina_reader"),
                activeBackend = "x_twitter",
                authRequirement = AuthenticationRequirement.SESSION_CREDENTIAL,
                authState = AuthenticationState.NOT_CONFIGURED,
                healthStatus = HealthStatus.SETUP_REQUIRED,
                statusMessage = "Public reading via Jina fallback; auth tokens optional for direct search",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // 10. Instagram
        register(
            ReachCapability(
                id = "instagram.user",
                name = "Instagram Profiles & Posts",
                description = "Public profile and post reading via OpenCLI / Jina Reader",
                platform = ResearchPlatform.INSTAGRAM,
                tier = 1,
                supportedOperations = listOf("READ_PROFILE", "READ_POST"),
                backends = listOf("instagram_jina"),
                activeBackend = "instagram_jina",
                authRequirement = AuthenticationRequirement.SESSION_CREDENTIAL,
                authState = AuthenticationState.NOT_CONFIGURED,
                healthStatus = HealthStatus.READY,
                statusMessage = "Public profile lookup active via Jina Reader",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // 11. LinkedIn
        register(
            ReachCapability(
                id = "linkedin.profile",
                name = "LinkedIn Career & Jobs",
                description = "Public company profiles, job posts, and articles",
                platform = ResearchPlatform.LINKEDIN,
                tier = 1,
                supportedOperations = listOf("READ_PROFILE", "READ_JOB_POST"),
                backends = listOf("linkedin_jina"),
                activeBackend = "linkedin_jina",
                authRequirement = AuthenticationRequirement.SESSION_CREDENTIAL,
                authState = AuthenticationState.NOT_CONFIGURED,
                healthStatus = HealthStatus.READY,
                statusMessage = "Public company & article reading ready via Jina Reader",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // 12. Facebook
        register(
            ReachCapability(
                id = "facebook.profile",
                name = "Facebook Pages & Groups",
                description = "Public Facebook pages and community articles",
                platform = ResearchPlatform.FACEBOOK,
                tier = 1,
                supportedOperations = listOf("READ_PAGE"),
                backends = listOf("facebook_jina"),
                activeBackend = "facebook_jina",
                authRequirement = AuthenticationRequirement.SESSION_CREDENTIAL,
                authState = AuthenticationState.NOT_CONFIGURED,
                healthStatus = HealthStatus.READY,
                statusMessage = "Public page reading active via Jina Reader",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // 13. XiaoHongShu
        register(
            ReachCapability(
                id = "xiaohongshu.note",
                name = "XiaoHongShu (RED)",
                description = "Lifestyle notes and product reviews via OpenCLI/Jina",
                platform = ResearchPlatform.XIAOHONGSHU,
                tier = 1,
                supportedOperations = listOf("READ_NOTE"),
                backends = listOf("xiaohongshu_jina"),
                activeBackend = "xiaohongshu_jina",
                authRequirement = AuthenticationRequirement.SESSION_CREDENTIAL,
                authState = AuthenticationState.NOT_CONFIGURED,
                healthStatus = HealthStatus.READY,
                statusMessage = "Public note reading active via Jina Reader",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // ── TIER 2: ADVANCED & COMPANION PLATFORMS ──

        // 14. Xueqiu Finance
        register(
            ReachCapability(
                id = "xueqiu.quote",
                name = "Xueqiu Stock & Finance",
                description = "Market data, community sentiment, and stock quotes",
                platform = ResearchPlatform.XUEQIU,
                tier = 2,
                supportedOperations = listOf("READ_QUOTE"),
                backends = listOf("xueqiu_jina"),
                activeBackend = "xueqiu_jina",
                authRequirement = AuthenticationRequirement.NONE,
                authState = AuthenticationState.NOT_REQUIRED,
                healthStatus = HealthStatus.READY,
                statusMessage = "Stock topic reader ready via Jina Reader",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // 15. Xiaoyuzhou Podcast
        register(
            ReachCapability(
                id = "xiaoyuzhou.podcast",
                name = "Xiaoyuzhou Podcast",
                description = "Podcast episode notes and audio transcripts",
                platform = ResearchPlatform.XIAOYUZHOU,
                tier = 2,
                supportedOperations = listOf("READ_PODCAST_NOTES"),
                backends = listOf("xiaoyuzhou_jina"),
                activeBackend = "xiaoyuzhou_jina",
                authRequirement = AuthenticationRequirement.NONE,
                authState = AuthenticationState.NOT_REQUIRED,
                healthStatus = HealthStatus.READY,
                statusMessage = "Podcast episode notes ready via Jina Reader",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )

        // 16. Boss Zhipin
        register(
            ReachCapability(
                id = "boss.job",
                name = "Boss Zhipin Recruiting",
                description = "Job recruitment and positions via OpenCLI desktop/companion",
                platform = ResearchPlatform.COMPANION_CUSTOM,
                tier = 2,
                supportedOperations = listOf("SEARCH_JOBS"),
                backends = listOf("companion_bridge", "boss_jina"),
                activeBackend = "companion_bridge",
                authRequirement = AuthenticationRequirement.COMPANION_REQUIRED,
                authState = AuthenticationState.NOT_CONFIGURED,
                healthStatus = HealthStatus.SETUP_REQUIRED,
                statusMessage = "Requires Termux companion or OpenCLI browser profile",
                permissionLevel = PermissionLevel.READ_ONLY
            )
        )
    }

    fun register(capability: ReachCapability) {
        capabilities[capability.id] = capability
    }

    fun getCapability(id: String): ReachCapability? = capabilities[id]

    fun getAllCapabilities(): List<ReachCapability> = capabilities.values.toList()

    fun updateActiveBackend(capabilityId: String, backendId: String?) {
        capabilities[capabilityId]?.activeBackend = backendId
    }

    fun updateHealth(capabilityId: String, status: HealthStatus, message: String) {
        capabilities[capabilityId]?.let {
            it.healthStatus = status
            it.statusMessage = message
            it.lastChecked = System.currentTimeMillis()
        }
    }
}
