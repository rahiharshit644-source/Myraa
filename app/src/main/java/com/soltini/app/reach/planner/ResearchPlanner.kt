package com.soltini.app.reach.planner

import com.soltini.app.reach.model.*

/**
 * ResearchPlanner
 *
 * Autonomous planner that examines user intent and constructs a multi-source research plan.
 * Decides whether to engage Web, GitHub, YouTube, Reddit, or Social platforms,
 * and sets research depth (QUICK, NORMAL, DEEP).
 */
object ResearchPlanner {

    private val URL_REGEX = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)

    fun createPlan(userQuery: String): ResearchPlan {
        val trimmed = userQuery.trim()
        val lower = trimmed.lowercase()

        // 1. Determine Research Mode
        val mode = when {
            lower.contains("deep research") || lower.contains("deep dive") ||
                    lower.contains("comprehensive") || lower.contains("cross check") ||
                    lower.contains("cross-check") || lower.contains("compare sources") ||
                    lower.contains("multiple sources") || lower.contains("detail me") ||
                    lower.contains("in detail") -> ResearchMode.DEEP

            lower.contains("quickly") || lower.contains("quick") ||
                    lower.contains("short summary") || lower.contains("briefly") ||
                    lower.contains("jaldi se") || lower.contains("chhota") -> ResearchMode.QUICK

            else -> ResearchMode.NORMAL
        }

        // 2. Check for explicit URL in query
        val urlMatch = URL_REGEX.find(trimmed)
        if (urlMatch != null) {
            val url = urlMatch.value
            val target = when {
                url.contains("youtube.com") || url.contains("youtu.be") -> {
                    ResearchTarget(platform = ResearchPlatform.YOUTUBE, query = trimmed, specificUrl = url)
                }
                url.contains("github.com") -> {
                    ResearchTarget(platform = ResearchPlatform.GITHUB, query = trimmed, specificUrl = url)
                }
                url.contains("reddit.com") -> {
                    ResearchTarget(platform = ResearchPlatform.REDDIT, query = trimmed, specificUrl = url)
                }
                url.contains("v2ex.com") -> {
                    ResearchTarget(platform = ResearchPlatform.V2EX, query = trimmed, specificUrl = url)
                }
                url.contains("bilibili.com") -> {
                    ResearchTarget(platform = ResearchPlatform.BILIBILI, query = trimmed, specificUrl = url)
                }
                url.contains("x.com") || url.contains("twitter.com") -> {
                    ResearchTarget(platform = ResearchPlatform.TWITTER_X, query = trimmed, specificUrl = url)
                }
                else -> {
                    ResearchTarget(platform = ResearchPlatform.WEB_READER, query = trimmed, specificUrl = url)
                }
            }
            return ResearchPlan(
                originalQuery = trimmed,
                mode = mode,
                targets = listOf(target),
                maxSourcesTotal = if (mode == ResearchMode.DEEP) 6 else 3
            )
        }

        // 3. Multi-platform query planning based on topic intent
        val targets = mutableListOf<ResearchTarget>()
        val cleanQuery = cleanSearchQuery(trimmed)

        val isCodeOrTech = lower.contains("github") || lower.contains("repo") ||
                lower.contains("library") || lower.contains("framework") ||
                lower.contains("code") || lower.contains("sdk") ||
                lower.contains("architecture") || lower.contains("open source")

        val isDiscussion = lower.contains("reddit") || lower.contains("opinion") ||
                lower.contains("what people say") || lower.contains("discussions") ||
                lower.contains("review") || lower.contains("log kya bol rahe")

        val isVideo = lower.contains("youtube") || lower.contains("video") ||
                lower.contains("lecture") || lower.contains("talk") || lower.contains("podcast")

        val mentionsMultipleExplicitly = (lower.contains("web") && lower.contains("github")) ||
                (lower.contains("reddit") && lower.contains("youtube")) ||
                (lower.contains("github") && lower.contains("reddit")) ||
                lower.contains("information collect karo") ||
                lower.contains("jo available ho unse")

        if (mentionsMultipleExplicitly || mode == ResearchMode.DEEP) {
            // Comprehensive Multi-Source Research across Web, GitHub, Reddit, and YouTube
            targets.add(ResearchTarget(platform = ResearchPlatform.WEB, query = cleanQuery, priority = 1))
            targets.add(ResearchTarget(platform = ResearchPlatform.GITHUB, query = cleanQuery, priority = 2))
            targets.add(ResearchTarget(platform = ResearchPlatform.REDDIT, query = cleanQuery, priority = 3))
            targets.add(ResearchTarget(platform = ResearchPlatform.YOUTUBE, query = cleanQuery, priority = 4))
        } else if (isCodeOrTech) {
            targets.add(ResearchTarget(platform = ResearchPlatform.GITHUB, query = cleanQuery, priority = 1))
            targets.add(ResearchTarget(platform = ResearchPlatform.WEB, query = "$cleanQuery documentation", priority = 2))
            if (mode == ResearchMode.DEEP) {
                targets.add(ResearchTarget(platform = ResearchPlatform.REDDIT, query = cleanQuery, priority = 3))
            }
        } else if (isDiscussion) {
            targets.add(ResearchTarget(platform = ResearchPlatform.REDDIT, query = cleanQuery, priority = 1))
            targets.add(ResearchTarget(platform = ResearchPlatform.WEB, query = cleanQuery, priority = 2))
            if (mode == ResearchMode.DEEP) {
                targets.add(ResearchTarget(platform = ResearchPlatform.TWITTER_X, query = cleanQuery, priority = 3))
            }
        } else if (isVideo) {
            targets.add(ResearchTarget(platform = ResearchPlatform.YOUTUBE, query = cleanQuery, priority = 1))
            targets.add(ResearchTarget(platform = ResearchPlatform.WEB, query = cleanQuery, priority = 2))
        } else {
            // NORMAL & QUICK: Primary web search
            targets.add(ResearchTarget(platform = ResearchPlatform.WEB, query = cleanQuery, priority = 1))
        }

        val maxSources = when (mode) {
            ResearchMode.QUICK -> 2
            ResearchMode.NORMAL -> 5
            ResearchMode.DEEP -> 10
        }

        return ResearchPlan(
            originalQuery = trimmed,
            mode = mode,
            targets = targets,
            maxSourcesTotal = maxSources,
            crossCheckRequired = mode == ResearchMode.DEEP
        )
    }

    private fun cleanSearchQuery(input: String): String {
        return input
            .replace(Regex("""(?i)^(hey\s+)?myra\s*,?\s*"""), "")
            .replace(Regex("""(?i)^(search\s+(the\s+)?web\s+for|search\s+for|web\s+par\s+search\s+karo|deep\s+research\s+karo|research\s+on|research)\s*"""), "")
            .replace(Regex("""(?i)(ke\s+baare\s+mein\s+batao|par\s+search\s+karo|search\s+karo|dhoondo|kya\s+hai)\s*$"""), "")
            .trim()
    }
}
