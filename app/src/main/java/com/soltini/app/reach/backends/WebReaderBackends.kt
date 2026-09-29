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
import java.util.Locale

interface WebReaderBackend : ReachBackend {
    suspend fun read(url: String): ResearchSource
}

/**
 * JinaReaderBackend
 *
 * Implements Jina Reader (https://r.jina.ai/{url}) matching Agent Reach's WebChannel.
 * Features antibot challenge detection, size limit, and markdown extraction.
 */
class JinaReaderBackend(
    private val httpClient: OkHttpClient
) : WebReaderBackend {
    override val id = "jina_reader"
    override val name = "Jina Reader"
    override val description = "Converts any web URL into clean, LLM-ready markdown (r.jina.ai)"

    companion object {
        private const val MAX_BYTES = 5 * 1024 * 1024 // 5MB matching Agent Reach
        private const val ANTIBOT_SCAN_BYTES = 4096
    }

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        // Zero-config, zero-overhead probe
        HealthStatus.READY to "Jina Reader ready for any web URL (https://r.jina.ai/URL)"
    }

    override suspend fun read(url: String): ResearchSource = withContext(Dispatchers.IO) {
        val safeUrl = ReachSecurityManager.validateAndNormalizePublicHttpUrl(url)
        val jinaUrl = "https://r.jina.ai/$safeUrl"

        val req = Request.Builder()
            .url(jinaUrl)
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
            .header("Accept", "text/plain, text/markdown, */*")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Jina Reader returned HTTP ${resp.code}")
            }
            val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
            if (bodyBytes.size > MAX_BYTES) {
                throw IllegalArgumentException("Page content exceeds 5MB limit")
            }

            if (isAntibotPage(bodyBytes)) {
                throw RuntimeException("Jina Reader encountered anti-bot / Cloudflare security verification on target website.")
            }

            val content = String(bodyBytes, Charsets.UTF_8)
            parseJinaReaderOutput(safeUrl, content)
        }
    }

    private fun isAntibotPage(body: ByteArray): Boolean {
        val sampleLength = minOf(body.size, ANTIBOT_SCAN_BYTES)
        val sample = String(body, 0, sampleLength, Charsets.UTF_8).lowercase(Locale.ROOT)

        val jinaCaptcha = sample.contains("warning:") && sample.contains("requiring captcha")
        val challengeStructure = sample.contains("title: just a moment...") ||
                sample.contains("## performing security verification") ||
                sample.contains("title: attention required! | cloudflare")
        val cloudflareBlock = sample.contains("title: attention required! | cloudflare") &&
                (sample.contains("ray id") || sample.contains("/cdn-cgi/challenge-platform/"))

        return (jinaCaptcha && challengeStructure) || cloudflareBlock
    }

    private fun parseJinaReaderOutput(originalUrl: String, markdown: String): ResearchSource {
        var title = "Web Document"
        val lines = markdown.lines()
        for (line in lines.take(15)) {
            if (line.startsWith("Title:")) {
                title = line.substringAfter("Title:").trim()
                break
            } else if (line.startsWith("# ")) {
                title = line.substringAfter("# ").trim()
                break
            }
        }

        val snippet = markdown.take(500).replace("\n", " ").trim()

        return ResearchSource(
            title = title,
            url = originalUrl,
            platform = ResearchPlatform.WEB_READER,
            snippet = snippet,
            fullContent = markdown,
            reliability = SourceReliability.SECONDARY_SOURCE,
            backendUsed = id
        )
    }
}

/**
 * DirectHttpReaderBackend
 *
 * Fallback reader that directly fetches HTML using OkHttp and extracts clean text.
 */
class DirectHttpReaderBackend(
    private val httpClient: OkHttpClient
) : WebReaderBackend {
    override val id = "direct_http_reader"
    override val name = "Direct Web Reader"
    override val description = "Direct HTTP parser with readability heuristics (fallback)"

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        HealthStatus.READY to "Direct HTTP reader ready (Native fallback)"
    }

    override suspend fun read(url: String): ResearchSource = withContext(Dispatchers.IO) {
        val safeUrl = ReachSecurityManager.validateAndNormalizePublicHttpUrl(url)

        val req = Request.Builder()
            .url(safeUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP GET error HTTP ${resp.code}")
            }
            val html = resp.body?.string().orEmpty()
            extractReadableContent(safeUrl, html)
        }
    }

    private fun extractReadableContent(url: String, html: String): ResearchSource {
        val titleMatch = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.IGNORE_CASE).find(html)
        val title = titleMatch?.groupValues?.get(1)?.trim() ?: "Web Article"

        val metaDescMatch = Regex("""<meta[^>]*name=["']description["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
        val description = metaDescMatch?.groupValues?.get(1)?.trim() ?: ""

        // Strip scripts, styles, navigations, headers
        var cleaned = html.replace(Regex("""<script[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("""<style[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("""<svg[^>]*>[\s\S]*?</svg>""", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("""<nav[^>]*>[\s\S]*?</nav>""", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("""<footer[^>]*>[\s\S]*?</footer>""", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("""<[^>]+>"""), " ")
        cleaned = cleaned.replace(Regex("""\s+"""), " ").trim()

        val snippet = if (description.isNotBlank()) description else cleaned.take(400)

        return ResearchSource(
            title = title,
            url = url,
            platform = ResearchPlatform.WEB_READER,
            snippet = snippet,
            fullContent = cleaned.take(8000),
            reliability = SourceReliability.SECONDARY_SOURCE,
            backendUsed = id
        )
    }
}
