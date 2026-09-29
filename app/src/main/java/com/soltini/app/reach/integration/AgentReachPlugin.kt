package com.soltini.app.reach.integration

import android.content.Context
import com.soltini.app.plugins.*
import com.soltini.app.reach.gateway.AgentReachGatewayImpl
import com.soltini.app.reach.model.ResearchMode
import com.soltini.app.reach.planner.ResearchPlanner
import org.json.JSONArray
import org.json.JSONObject

/**
 * AgentReachPluginInitializer
 *
 * Registers Agent Reach capabilities as native plugins into MYRA's dynamic PluginRegistry.
 * Allows the orchestrator's LLM planning loop to discover and execute internet research tools.
 */
object AgentReachPluginInitializer {

    fun registerReachPlugins(context: Context, registry: PluginRegistry) {
        val gateway = AgentReachGatewayImpl.getInstance(context)

        // 1. Web Search Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "reach_web_search",
                name = "Internet Web Search (Agent Reach)",
                description = "Searches the live public internet using multi-backend fallbacks (DuckDuckGo, Jina, Exa, Wikipedia).",
                category = PluginCategory.WEB,
                tags = listOf("search", "google", "web", "internet", "dhoondo", "online", "research"),
                riskLevel = RiskLevel.LOW,
                inputParameters = JSONObject().apply {
                    put("query", "Search keywords or question")
                    put("max_results", "Number of results (default 5)")
                }
            )

            override suspend fun execute(context: PluginContext): PluginResult {
                val query = context.optString("query")
                if (query.isBlank()) return PluginResult.failure(metadata.id, "Missing query parameter")
                val limit = context.optInt("max_results", 5)

                val start = System.currentTimeMillis()
                return try {
                    val sources = gateway.searchWeb(query, limit)
                    val latency = System.currentTimeMillis() - start
                    val arr = JSONArray()
                    sources.forEach { arr.put(it.toJsonObject()) }
                    PluginResult.success(
                        metadata.id,
                        "Found ${sources.size} web search results for '$query'",
                        JSONObject().apply { put("results", arr) },
                        latency
                    )
                } catch (e: Exception) {
                    PluginResult.failure(metadata.id, "Web search failed: ${e.message}", System.currentTimeMillis() - start)
                }
            }
        })

        // 2. Web Reader Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "reach_web_read",
                name = "Web Page Reader (Agent Reach)",
                description = "Reads, parses, and converts any public webpage or URL into clean markdown.",
                category = PluginCategory.WEB,
                tags = listOf("read", "url", "webpage", "article", "link", "website", "padho"),
                riskLevel = RiskLevel.LOW,
                inputParameters = JSONObject().apply {
                    put("url", "Public HTTP(S) URL of the webpage to read")
                }
            )

            override suspend fun execute(context: PluginContext): PluginResult {
                val url = context.optString("url")
                if (url.isBlank()) return PluginResult.failure(metadata.id, "Missing URL parameter")

                val start = System.currentTimeMillis()
                return try {
                    val source = gateway.readWebPage(url)
                    val latency = System.currentTimeMillis() - start
                    PluginResult.success(
                        metadata.id,
                        "Successfully read '${source.title}'",
                        source.toJsonObject(),
                        latency
                    )
                } catch (e: Exception) {
                    PluginResult.failure(metadata.id, "Web reading failed: ${e.message}", System.currentTimeMillis() - start)
                }
            }
        })

        // 3. YouTube Research Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "reach_youtube_research",
                name = "YouTube Video & Subtitle Reader",
                description = "Extracts verified multi-language transcripts, summaries, and video metadata from YouTube.",
                category = PluginCategory.MEDIA,
                tags = listOf("youtube", "video", "transcript", "subtitle", "yt", "summary"),
                riskLevel = RiskLevel.LOW,
                inputParameters = JSONObject().apply {
                    put("url_or_id", "YouTube video URL or 11-char video ID")
                }
            )

            override suspend fun execute(context: PluginContext): PluginResult {
                val urlOrId = context.optString("url_or_id", context.optString("url"))
                if (urlOrId.isBlank()) return PluginResult.failure(metadata.id, "Missing YouTube video URL or ID")

                val start = System.currentTimeMillis()
                return try {
                    val source = gateway.getYouTubeContent(urlOrId)
                    val latency = System.currentTimeMillis() - start
                    PluginResult.success(
                        metadata.id,
                        "Extracted content for '${source.title}'",
                        source.toJsonObject(),
                        latency
                    )
                } catch (e: Exception) {
                    PluginResult.failure(metadata.id, "YouTube extraction failed: ${e.message}", System.currentTimeMillis() - start)
                }
            }
        })

        // 4. GitHub Research Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "reach_github_research",
                name = "GitHub Repository Inspector",
                description = "Inspects GitHub repositories, READMEs, open issues, releases, and architectural structure.",
                category = PluginCategory.DEVELOPER,
                tags = listOf("github", "repo", "repository", "code", "architecture", "git"),
                riskLevel = RiskLevel.LOW,
                inputParameters = JSONObject().apply {
                    put("repo", "Repository owner/repo (e.g., Panniantong/Agent-Reach)")
                }
            )

            override suspend fun execute(context: PluginContext): PluginResult {
                val repo = context.optString("repo", context.optString("owner_repo"))
                if (repo.isBlank()) return PluginResult.failure(metadata.id, "Missing GitHub repository name")

                val start = System.currentTimeMillis()
                return try {
                    val source = gateway.inspectGitHubRepository(repo)
                    val latency = System.currentTimeMillis() - start
                    PluginResult.success(
                        metadata.id,
                        "Inspected GitHub repository '${source.title}'",
                        source.toJsonObject(),
                        latency
                    )
                } catch (e: Exception) {
                    PluginResult.failure(metadata.id, "GitHub inspection failed: ${e.message}", System.currentTimeMillis() - start)
                }
            }
        })

        // 5. Reddit Research Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "reach_reddit_research",
                name = "Reddit Public Discussions",
                description = "Searches community discussions, topics, and public Reddit threads.",
                category = PluginCategory.SOCIAL,
                tags = listOf("reddit", "discussion", "community", "comments", "forum"),
                riskLevel = RiskLevel.LOW,
                inputParameters = JSONObject().apply {
                    put("query", "Search topic or question")
                    put("subreddit", "Optional subreddit name")
                }
            )

            override suspend fun execute(context: PluginContext): PluginResult {
                val query = context.optString("query")
                val subreddit = context.optString("subreddit").ifBlank { null }

                val start = System.currentTimeMillis()
                return try {
                    val sources = gateway.searchReddit(query, subreddit)
                    val latency = System.currentTimeMillis() - start
                    val arr = JSONArray()
                    sources.forEach { arr.put(it.toJsonObject()) }
                    PluginResult.success(
                        metadata.id,
                        "Found ${sources.size} Reddit discussions",
                        JSONObject().apply { put("discussions", arr) },
                        latency
                    )
                } catch (e: Exception) {
                    PluginResult.failure(metadata.id, "Reddit search failed: ${e.message}", System.currentTimeMillis() - start)
                }
            }
        })

        // 6. Autonomous Multi-Source Deep Research Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "reach_multi_research",
                name = "Autonomous Deep Internet Research",
                description = "Executes multi-platform research plans across Web, GitHub, Reddit, and Video with deduplication.",
                category = PluginCategory.WEB,
                tags = listOf("deep_research", "investigate", "multi_source", "research", "compare"),
                riskLevel = RiskLevel.LOW,
                inputParameters = JSONObject().apply {
                    put("query", "Comprehensive research topic")
                    put("mode", "QUICK, NORMAL, or DEEP")
                }
            )

            override suspend fun execute(context: PluginContext): PluginResult {
                val query = context.optString("query")
                if (query.isBlank()) return PluginResult.failure(metadata.id, "Missing research query")

                val plan = ResearchPlanner.createPlan(query)
                val start = System.currentTimeMillis()
                return try {
                    val result = gateway.executeResearch(plan)
                    val latency = System.currentTimeMillis() - start
                    PluginResult.success(
                        metadata.id,
                        "Retrieved ${result.sources.size} verified sources across platforms",
                        result.toJsonObject(),
                        latency
                    )
                } catch (e: Exception) {
                    PluginResult.failure(metadata.id, "Deep research failed: ${e.message}", System.currentTimeMillis() - start)
                }
            }
        })

        // 7. Doctor Status Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "reach_doctor_status",
                name = "Agent Reach Doctor (Health Check)",
                description = "Checks and reports availability, active backends, and health for all 16 internet capabilities.",
                category = PluginCategory.INFORMATION,
                tags = listOf("doctor", "health", "internet_status", "capabilities", "reach_status"),
                riskLevel = RiskLevel.LOW
            )

            override suspend fun execute(context: PluginContext): PluginResult {
                val start = System.currentTimeMillis()
                return try {
                    val report = gateway.getHealth(forceRefresh = true)
                    val latency = System.currentTimeMillis() - start
                    PluginResult.success(
                        metadata.id,
                        "${report.readyCapabilities}/${report.totalCapabilities} capabilities available",
                        JSONObject().apply {
                            put("report_text", report.toFormattedReport())
                            put("ready_count", report.readyCapabilities)
                            put("total_count", report.totalCapabilities)
                        },
                        latency
                    )
                } catch (e: Exception) {
                    PluginResult.failure(metadata.id, "Doctor check failed: ${e.message}", System.currentTimeMillis() - start)
                }
            }
        })
    }
}
