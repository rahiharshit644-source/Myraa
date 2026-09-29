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
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

interface YouTubeBackend : ReachBackend {
    suspend fun getVideoContent(urlOrVideoId: String): ResearchSource
    suspend fun searchVideos(query: String, maxResults: Int = 5): List<ResearchSource>
}

/**
 * YouTubeTranscriptBackend
 *
 * Extracts real captions and subtitles directly from YouTube's public timedtext stream.
 */
class YouTubeTranscriptBackend(
    private val httpClient: OkHttpClient
) : YouTubeBackend {
    override val id = "youtube_transcript"
    override val name = "YouTube Transcript & Subtitles"
    override val description = "Extracts verified multi-language subtitles and transcripts directly"

    companion object {
        private val VIDEO_ID_PATTERN = Pattern.compile(
            """(?:youtube\.com\/(?:[^\/]+\/.+\/|(?:v|e(?:mbed)?)\/|.*[?&]v=)|youtu\.be\/)([^"&?\/\s]{11})""",
            Pattern.CASE_INSENSITIVE
        )
    }

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        HealthStatus.READY to "YouTube transcript extractor ready (Native stream parser)"
    }

    fun extractVideoId(input: String): String {
        val trimmed = input.trim()
        if (trimmed.length == 11 && trimmed.matches(Regex("""^[A-Za-z0-9_-]{11}$"""))) {
            return trimmed
        }
        val matcher = VIDEO_ID_PATTERN.matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(1) ?: trimmed
        }
        return trimmed
    }

    override suspend fun getVideoContent(urlOrVideoId: String): ResearchSource = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(urlOrVideoId)
        val videoUrl = "https://www.youtube.com/watch?v=$videoId"

        // 1. Fetch video watch page to parse ytInitialPlayerResponse
        val pageReq = Request.Builder()
            .url(videoUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")
            .build()

        val html = httpClient.newCall(pageReq).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("YouTube page error HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }

        val titleMatch = Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE).find(html)
        val title = titleMatch?.groupValues?.get(1)?.replace(" - YouTube", "")?.trim() ?: "YouTube Video ($videoId)"

        // 2. Locate captions tracks in player response
        val captionUrl = findCaptionUrl(html)
        val transcriptText = if (captionUrl.isNotBlank()) {
            fetchAndParseTimedText(captionUrl)
        } else {
            ""
        }

        if (transcriptText.isBlank()) {
            throw RuntimeException("No subtitles or transcript found for YouTube video $videoId.")
        }

        ResearchSource(
            title = title,
            url = videoUrl,
            platform = ResearchPlatform.YOUTUBE,
            snippet = transcriptText.take(400),
            fullContent = transcriptText,
            reliability = SourceReliability.PRIMARY_SOURCE,
            backendUsed = id,
            metadata = mapOf("video_id" to videoId, "has_transcript" to "true")
        )
    }

    private fun findCaptionUrl(html: String): String {
        val match = Regex(""""captionTracks":\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return ""
        val jsonStr = match.groupValues[1]
        try {
            val tracks = JSONArray(jsonStr)
            if (tracks.length() == 0) return ""
            // Prefer English or Hindi or the first track
            var selectedUrl = ""
            for (i in 0 until tracks.length()) {
                val track = tracks.getJSONObject(i)
                val lang = track.optString("languageCode", "").lowercase()
                val url = track.optString("baseUrl")
                if (url.isNotBlank()) {
                    if (lang.startsWith("en") || lang.startsWith("hi")) {
                        return url
                    }
                    if (selectedUrl.isBlank()) selectedUrl = url
                }
            }
            return selectedUrl
        } catch (_: Exception) {
            return ""
        }
    }

    private fun fetchAndParseTimedText(captionUrl: String): String {
        val req = Request.Builder()
            .url(captionUrl)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        return httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use ""
            val xml = resp.body?.string().orEmpty()
            val textMatcher = Pattern.compile("<text[^>]*>(.*?)</text>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            val matcher = textMatcher.matcher(xml)
            val sb = StringBuilder()

            while (matcher.find()) {
                val chunk = matcher.group(1).orEmpty()
                val clean = chunk
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("\n", " ")
                    .trim()
                if (clean.isNotBlank()) {
                    sb.append(clean).append(" ")
                }
            }
            sb.toString().trim()
        }
    }

    override suspend fun searchVideos(query: String, maxResults: Int): List<ResearchSource> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "https://www.youtube.com/results?search_query=$encoded"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val html = resp.body?.string().orEmpty()
            val results = mutableListOf<ResearchSource>()

            val videoRegex = Regex(""""videoId":"([A-Za-z0-9_-]{11})".*?"title":\{"runs":\[\{"text":"(.*?)"\}""", RegexOption.DOT_MATCHES_ALL)
            val matches = videoRegex.findAll(html)

            for (m in matches) {
                if (results.size >= maxResults) break
                val vid = m.groupValues[1]
                val vTitle = m.groupValues[2]
                val videoUrl = "https://www.youtube.com/watch?v=$vid"

                if (results.none { it.metadata["video_id"] == vid }) {
                    results.add(
                        ResearchSource(
                            title = vTitle,
                            url = videoUrl,
                            platform = ResearchPlatform.YOUTUBE,
                            snippet = "YouTube Video: $vTitle (ID: $vid)",
                            reliability = SourceReliability.SECONDARY_SOURCE,
                            backendUsed = id,
                            metadata = mapOf("video_id" to vid)
                        )
                    )
                }
            }
            results
        }
    }
}

/**
 * YouTubeOEmbedBackend
 *
 * Fast metadata fetcher for YouTube videos via oEmbed API.
 */
class YouTubeOEmbedBackend(
    private val httpClient: OkHttpClient
) : YouTubeBackend {
    override val id = "youtube_oembed"
    override val name = "YouTube oEmbed Metadata"
    override val description = "Official YouTube oEmbed metadata provider (Zero-config)"

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        HealthStatus.READY to "YouTube oEmbed metadata ready (Zero-config)"
    }

    override suspend fun getVideoContent(urlOrVideoId: String): ResearchSource = withContext(Dispatchers.IO) {
        val vid = if (urlOrVideoId.length == 11) "https://www.youtube.com/watch?v=$urlOrVideoId" else urlOrVideoId
        val encodedUrl = URLEncoder.encode(vid, StandardCharsets.UTF_8.name())
        val reqUrl = "https://www.youtube.com/oembed?url=$encodedUrl&format=json"

        val req = Request.Builder()
            .url(reqUrl)
            .header("User-Agent", "MyraAgentReach/1.0")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("YouTube oEmbed returned HTTP ${resp.code}")
            }
            val json = JSONObject(resp.body?.string().orEmpty())
            val title = json.optString("title", "YouTube Video")
            val author = json.optString("author_name")

            ResearchSource(
                title = title,
                url = vid,
                platform = ResearchPlatform.YOUTUBE,
                author = author,
                snippet = "YouTube video by $author: '$title'",
                fullContent = "Title: $title\nAuthor: $author\nURL: $vid",
                reliability = SourceReliability.OFFICIAL,
                backendUsed = id
            )
        }
    }

    override suspend fun searchVideos(query: String, maxResults: Int): List<ResearchSource> {
        return emptyList() // oEmbed only inspects URLs
    }
}

/**
 * YouTubeJinaFallbackBackend
 *
 * Fallback via Jina Reader for YouTube pages.
 */
class YouTubeJinaFallbackBackend(
    private val httpClient: OkHttpClient
) : YouTubeBackend {
    override val id = "youtube_jina"
    override val name = "YouTube Jina Reader Fallback"
    override val description = "Extracts YouTube page content & video description via Jina Reader"

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        HealthStatus.READY to "YouTube Jina reader fallback ready"
    }

    override suspend fun getVideoContent(urlOrVideoId: String): ResearchSource = withContext(Dispatchers.IO) {
        val vid = if (urlOrVideoId.length == 11) "https://www.youtube.com/watch?v=$urlOrVideoId" else urlOrVideoId
        val jinaUrl = "https://r.jina.ai/$vid"

        val req = Request.Builder()
            .url(jinaUrl)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "text/plain")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Jina returned HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            val lines = text.lines()
            val title = lines.find { it.startsWith("Title:") }?.substringAfter("Title:")?.trim()
                ?: "YouTube Video Content"

            ResearchSource(
                title = title,
                url = vid,
                platform = ResearchPlatform.YOUTUBE,
                snippet = text.take(400),
                fullContent = text,
                reliability = SourceReliability.SECONDARY_SOURCE,
                backendUsed = id
            )
        }
    }

    override suspend fun searchVideos(query: String, maxResults: Int): List<ResearchSource> = emptyList()
}
