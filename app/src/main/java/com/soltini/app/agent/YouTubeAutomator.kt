package com.soltini.app.agent

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * YouTubeAutomator
 *
 * Dedicated automation layer for YouTube. Combines:
 *  1. Intent-based deep links (fastest — no UI scraping needed for search/open)
 *  2. Media key dispatch (play/pause, next, previous — works even in background)
 *  3. AccessibilityService node clicking (like, subscribe, skip ad, quality)
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  TOOL               │ TRIGGER PHRASE                                    │
 * │─────────────────────┼───────────────────────────────────────────────────│
 * │  youtube_search     │ "search YouTube for X" / "play X on YouTube"     │
 * │  youtube_open_video │ "open the first video" / "play that video"        │
 * │  youtube_play_pause │ "pause" / "resume" / "play" / "pause YouTube"    │
 * │  youtube_seek       │ "skip forward 30 seconds" / "rewind 10 seconds"  │
 * │  youtube_next       │ "next video" / "skip video"                      │
 * │  youtube_previous   │ "previous video" / "go back"                     │
 * │  youtube_like       │ "like this video" / "give a thumbs up"           │
 * │  youtube_dislike    │ "dislike" / "thumbs down"                        │
 * │  youtube_subscribe  │ "subscribe to this channel"                      │
 * │  youtube_skip_ad    │ "skip the ad" / "skip ad"                        │
 * │  youtube_set_quality│ "change quality to 1080p" / "set to 720p"        │
 * │  youtube_fullscreen │ "go fullscreen" / "exit fullscreen"              │
 * │  youtube_mute       │ "mute YouTube"                                   │
 * │  youtube_captions   │ "turn on captions" / "show subtitles"            │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * NOTE: youtube_search and most intents work WITHOUT accessibility.
 *       Node-based actions (like, subscribe, skip ad, quality) require
 *       the AccessibilityService to be active.
 */
class YouTubeAutomator(private val context: Context) {

    companion object {
        private const val TAG = "YouTubeAutomator"
        private const val YOUTUBE_PKG = "com.google.android.youtube"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // YouTube accessibility content descriptions (English — shipped with app)
        private val LIKE_LABELS = listOf("like", "thumb up", "thumbs up", "Like this video")
        private val DISLIKE_LABELS = listOf("dislike", "thumb down", "thumbs down", "Dislike this video")
        private val SUBSCRIBE_LABELS = listOf("subscribe", "Subscribe")
        private val SKIP_AD_LABELS = listOf("Skip Ad", "Skip Ads", "SKIP ADS", "Skip ad", "skip")
        private val FULLSCREEN_LABELS = listOf("Full screen", "Fullscreen", "Exit full screen", "full screen")
        private val CAPTIONS_LABELS = listOf("Closed captions", "Captions", "CC", "Subtitles")
        private val QUALITY_MENU_LABELS = listOf("More", "overflow", "Settings", "Quality")
        private val QUALITY_OPTION_LABELS = listOf("Quality")
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ─── Search / Open ────────────────────────────────────────────────────────

    /**
     * Opens YouTube and performs a search for [query], or resolves and plays the song directly if asked to play.
     */
    fun search(query: String): JSONObject {
        if (query.isBlank()) return error("Search query is required")

        // If the query indicates a request to play a song/video, resolve and play the matching video directly
        val lower = query.trim().lowercase()
        val isPlayRequest = lower.startsWith("play ") || lower.startsWith("play\t") ||
                lower.startsWith("listen to ") || lower.contains("chalao") || lower.contains("bajao")

        if (isPlayRequest) {
            val resolvedId = searchPlayableVideoId(query)
            if (resolvedId != null) {
                val success = playVideoIntent(resolvedId)
                if (success) {
                    val url = "https://www.youtube.com/watch?v=$resolvedId"
                    Log.i(TAG, "Played song from search '$query' -> $url")
                    return result("status", "playing_video", "video_id", resolvedId, "url", url, "query", query)
                }
            }
        }

        return try {
            // Method 1: YouTube search deep link (most reliable)
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage(YOUTUBE_PKG)
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "YouTube search: $query")
            result("status", "searching", "query", query)
        } catch (e: Exception) {
            // Method 2: Fallback via web URL
            try {
                val encodedQuery = Uri.encode(query)
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=$encodedQuery")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(webIntent)
                result("status", "searching_web_fallback", "query", query)
            } catch (e2: Exception) {
                error("Could not open YouTube: ${e2.message}")
            }
        }
    }

    /**
     * Opens a YouTube video or plays a requested song by URL, video ID, or song title/query.
     * Accurately searches YouTube and resolves to a verified, playable public video to prevent
     * "Video unavailable" errors caused by invalid IDs, restricted content, or unreleased premieres.
     */
    fun playSong(query: String): JSONObject {
        return openVideo(query)
    }

    fun openVideo(videoIdOrUrl: String): JSONObject {
        if (videoIdOrUrl.isBlank()) return error("video_id or song name is required")

        // 1. Try to extract an 11-char ID if the input is already a valid URL or 11-char ID
        val extractedId = extractVideoId(videoIdOrUrl)
        var playableId: String? = null

        if (extractedId != null && isPlayableVideo(extractedId)) {
            playableId = extractedId
        } else {
            // 2. Search YouTube to fetch the correct, playable video ID matching the song/query
            playableId = searchPlayableVideoId(videoIdOrUrl)
        }

        if (playableId != null) {
            val success = playVideoIntent(playableId)
            if (success) {
                val url = "https://www.youtube.com/watch?v=$playableId"
                Log.i(TAG, "Successfully opened playable video for '$videoIdOrUrl': $url")
                return result("status", "playing_video", "video_id", playableId, "url", url)
            }
            return error("Failed to launch video playback intent for video ID: $playableId")
        }

        // 3. Fallback: If network lookup could not resolve a specific video ID, perform standard search
        // rather than opening an invalid watch URL which triggers "Video unavailable"
        Log.w(TAG, "Could not resolve playable ID for '$videoIdOrUrl', falling back to search")
        return search(videoIdOrUrl)
    }

    /**
     * Validates whether a given YouTube video ID corresponds to a valid, publicly playable video.
     * Uses the official YouTube oEmbed service which returns 200 only for valid, playable, non-restricted videos.
     */
    private fun isPlayableVideo(videoId: String): Boolean {
        if (!videoId.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return false
        return try {
            val url = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "isPlayableVideo check failed for $videoId: ${e.message}")
            false
        }
    }

    /**
     * Extracts an 11-character YouTube video ID from a URL, deep link, or raw ID.
     */
    private fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        val urlRegex = Regex("""(?:youtu\.be\/|youtube\.com\/(?:embed\/|v\/|watch\?v=|watch\?.+&v=|shorts\/)|vnd\.youtube:)([a-zA-Z0-9_-]{11})""")
        val match = urlRegex.find(trimmed)
        if (match != null) {
            return match.groupValues[1]
        }
        if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            return trimmed
        }
        return null
    }

    /**
     * Searches YouTube for [query], inspects results to skip unreleased premieres,
     * live streams, and restricted items, and returns the first verified playable video ID.
     */
    private fun searchPlayableVideoId(query: String): String? {
        val cleanQuery = query.trim()
            .replace(Regex("""(?i)^(play|search|open|find|listen to)\s+"""), "")
            .replace(Regex("""(?i)\s+(on|in|from|via)\s+youtube.*$"""), "")
            .replace(Regex("""(?i)\s+youtube\s+pe.*$"""), "")
            .trim()
        val finalQuery = if (cleanQuery.isBlank()) query.trim() else cleanQuery

        try {
            val encoded = URLEncoder.encode(finalQuery, "UTF-8")
            val searchUrl = "https://www.youtube.com/results?search_query=$encoded"
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                // FIX: without a consent cookie, YouTube serves an EU/region "before you
                // continue" consent page instead of search results for many requests, which
                // has no ytInitialData/videoId matches at all — causing silent failures.
                .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
                .build()

            val html = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string() ?: ""
            }

            // Strategy 1: Parse structured ytInitialData from YouTube search page
            // FIX: added RegexOption.DOT_MATCHES_ALL — without it, "." never matched newline
            // characters, so any response where ytInitialData's JSON blob spanned multiple
            // lines (which happens depending on region/consent-page variant) silently failed
            // to match, falling through to just opening a search page instead of playing.
            val jsonMatch = Regex(
                """var ytInitialData\s*=\s*(\{.+?\});\s*</script>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(html)
            if (jsonMatch != null) {
                try {
                    val root = JSONObject(jsonMatch.groupValues[1])
                    val candidates = mutableListOf<String>()
                    extractVideoCandidatesFromYtInitialData(root, candidates)
                    for (candidateId in candidates) {
                        if (isPlayableVideo(candidateId)) {
                            Log.i(TAG, "Resolved query '$query' via ytInitialData to playable ID: $candidateId")
                            return candidateId
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ytInitialData parsing failed: ${e.message}")
                }
            }

            // Strategy 2: Fast regex extraction fallback
            val regex = Regex(""""videoId":"([a-zA-Z0-9_-]{11})"""")
            val matches = regex.findAll(html).map { it.groupValues[1] }.distinct().take(10).toList()
            for (candidateId in matches) {
                if (isPlayableVideo(candidateId)) {
                    Log.i(TAG, "Resolved query '$query' via regex to playable ID: $candidateId")
                    return candidateId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "searchPlayableVideoId error for query '$query': ${e.message}")
        }
        return null
    }

    /**
     * Recursively walks ytInitialData JSON to find videoRenderers, filtering out
     * premiere countdowns, live streams, and non-video items.
     */
    private fun extractVideoCandidatesFromYtInitialData(obj: Any?, candidates: MutableList<String>) {
        when (obj) {
            is JSONObject -> {
                if (obj.has("videoRenderer")) {
                    val vr = obj.optJSONObject("videoRenderer")
                    if (vr != null) {
                        val vid = vr.optString("videoId", "")
                        val isUpcoming = vr.has("upcomingEventData")
                        val isLive = vr.optBoolean("isLive", false)
                        val badges = vr.optJSONArray("badges")
                        var badgeLiveOrPremiere = false
                        if (badges != null) {
                            for (i in 0 until badges.length()) {
                                val label = badges.optJSONObject(i)
                                    ?.optJSONObject("metadataBadgeRenderer")
                                    ?.optString("label", "") ?: ""
                                if (label.contains("LIVE", ignoreCase = true) ||
                                    label.contains("PREMIERE", ignoreCase = true) ||
                                    label.contains("UPCOMING", ignoreCase = true)) {
                                    badgeLiveOrPremiere = true
                                    break
                                }
                            }
                        }
                        if (vid.length == 11 && !isUpcoming && !isLive && !badgeLiveOrPremiere) {
                            if (!candidates.contains(vid)) {
                                candidates.add(vid)
                            }
                        }
                    }
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    extractVideoCandidatesFromYtInitialData(obj.opt(keys.next()), candidates)
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    extractVideoCandidatesFromYtInitialData(obj.opt(i), candidates)
                }
            }
        }
    }

    /**
     * Launches playback of a verified video ID using the best available Android Intent.
     */
    private fun playVideoIntent(videoId: String): Boolean {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"

        // 1. YouTube App standard watch URL intent
        try {
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl)).apply {
                setPackage(YOUTUBE_PKG)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(appIntent)
            Log.i(TAG, "Launched YouTube app with watchUrl: $watchUrl")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "YouTube app intent with watchUrl failed: ${e.message}")
        }

        // 2. YouTube App vnd.youtube scheme intent
        try {
            val vndIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).apply {
                setPackage(YOUTUBE_PKG)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(vndIntent)
            Log.i(TAG, "Launched YouTube app with vnd.youtube:$videoId")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "vnd.youtube intent failed: ${e.message}")
        }

        // 3. Fallback to generic browser/player intent without package requirement
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            Log.i(TAG, "Launched browser fallback for: $watchUrl")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "All video play intents failed: ${e.message}")
            return false
        }
    }

    // ─── Playback Control (Media Keys) ────────────────────────────────────────

    /**
     * Toggles play/pause on YouTube (or any active media session).
     * Media key dispatch works even when YouTube is in the background.
     */
    fun playPause(): JSONObject {
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        return result("status", "play_pause_toggled")
    }

    /**
     * Skips to the next video / track in the queue.
     */
    fun nextVideo(): JSONObject {
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        return result("status", "next_video")
    }

    /**
     * Goes back to the previous video / track.
     */
    fun previousVideo(): JSONObject {
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        return result("status", "previous_video")
    }

    /**
     * Seeks forward by tapping the fast-forward region (double-tap right side of video).
     * For custom durations, we use the +10s / -10s button via accessibility.
     *
     * @param seconds how many seconds to seek (positive = forward, negative = backward)
     */
    fun seek(seconds: Int): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        val taps = Math.abs(seconds) / 10  // Each tap = ~10 seconds
        val forward = seconds > 0

        if (taps == 0) return error("Seek amount must be at least 10 seconds")

        // Approximate center of the screen
        val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
        val screenHeight = context.resources.displayMetrics.heightPixels.toFloat()

        // Double-tap the right 1/3 of screen to skip forward, left 1/3 to rewind
        val tapX = if (forward) screenWidth * 0.75f else screenWidth * 0.25f
        val tapY = screenHeight * 0.5f

        repeat(taps.coerceAtMost(5)) { // Max 5 taps (50s)
            svc.tapAt(tapX, tapY)
            Thread.sleep(150)
            svc.tapAt(tapX, tapY) // Double-tap
            Thread.sleep(200)
        }

        val direction = if (forward) "forward" else "backward"
        return result("status", "seeked_$direction", "seconds", seconds.toString())
    }

    // ─── UI Actions (Accessibility Required) ─────────────────────────────────

    /**
     * Likes the current video by finding and clicking the Like button.
     */
    fun likeVideo(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        return clickOneOf(svc, LIKE_LABELS, "Like button")
    }

    /**
     * Dislikes the current video.
     */
    fun dislikeVideo(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        return clickOneOf(svc, DISLIKE_LABELS, "Dislike button")
    }

    /**
     * Subscribes to the channel of the currently playing video.
     */
    fun subscribe(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        return clickOneOf(svc, SUBSCRIBE_LABELS, "Subscribe button")
    }

    /**
     * Clicks the "Skip Ad" button if a skippable ad is playing.
     */
    fun skipAd(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        // Try up to 3 times (ad skip button sometimes appears with a delay)
        repeat(3) {
            for (label in SKIP_AD_LABELS) {
                if (svc.clickByText(label)) {
                    return result("status", "ad_skipped")
                }
            }
            Thread.sleep(500)
        }
        return error("No skippable ad found — the ad may not be skippable yet or there is no ad playing")
    }

    /**
     * Opens the quality settings menu and selects the requested quality.
     * @param quality e.g. "1080p", "720p", "480p", "Auto"
     */
    fun setQuality(quality: String): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        // First, tap the video to show controls
        val screen = context.resources.displayMetrics
        svc.tapAt(screen.widthPixels / 2f, screen.heightPixels / 2f)
        Thread.sleep(400)

        // Click the overflow / more options button
        var opened = false
        for (label in QUALITY_MENU_LABELS) {
            if (svc.clickByText(label)) { opened = true; break }
        }
        if (!opened) return error("Could not open YouTube settings menu — tap the video first to show controls")
        Thread.sleep(500)

        // Click "Quality" option in the menu
        if (!svc.clickByText("Quality")) {
            return error("Quality option not found in menu")
        }
        Thread.sleep(400)

        // Click the requested quality
        if (svc.clickByText(quality)) {
            return result("status", "quality_set", "quality", quality)
        }

        // Try partial match (e.g. user says "1080" and button says "1080p")
        val screen2 = svc.readScreen()
        val availableQualities = listOf("Auto", "144p", "240p", "360p", "480p", "720p", "1080p", "1440p", "2160p")
        val match = availableQualities.firstOrNull { it.contains(quality, ignoreCase = true) }
        if (match != null && svc.clickByText(match)) {
            return result("status", "quality_set", "quality", match)
        }

        return error("Quality '$quality' not found. Available: ${availableQualities.joinToString(", ")}")
    }

    /**
     * Toggles fullscreen mode by clicking the fullscreen button.
     */
    fun toggleFullscreen(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        // Show player controls first
        val screen = context.resources.displayMetrics
        svc.tapAt(screen.widthPixels / 2f, screen.heightPixels / 2f)
        Thread.sleep(300)

        return clickOneOf(svc, FULLSCREEN_LABELS, "Fullscreen button")
    }

    /**
     * Toggles closed captions / subtitles.
     */
    fun toggleCaptions(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        // Show player controls first
        val screen = context.resources.displayMetrics
        svc.tapAt(screen.widthPixels / 2f, screen.heightPixels / 2f)
        Thread.sleep(300)

        return clickOneOf(svc, CAPTIONS_LABELS, "Captions button")
    }

    /**
     * Mutes / unmutes using the device volume key media intent.
     */
    fun toggleMute(): JSONObject {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val isMuted = am.isStreamMute(AudioManager.STREAM_MUSIC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (isMuted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE,
                0
            )
        }
        val newState = if (isMuted) "unmuted" else "muted"
        return result("status", newState)
    }

    /**
     * Reads the current screen to find video title, channel, and other metadata
     * visible on the YouTube player or home screen.
     */
    fun readYouTubeScreen(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        val content = svc.readScreen()
        return JSONObject().apply {
            put("status", "ok")
            put("youtube_screen", content)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Dispatches a media key press DOWN + UP to the AudioManager.
     * Works across all media apps including YouTube background playback.
     */
    private fun dispatchMediaKey(keyCode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventTime = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
        Log.i(TAG, "Dispatched media key: $keyCode")
    }

    private fun clickOneOf(
        svc: SoltiniAccessibilityService,
        labels: List<String>,
        elementName: String
    ): JSONObject {
        for (label in labels) {
            if (svc.clickByText(label)) {
                Log.i(TAG, "Clicked: $label")
                return result("status", "clicked", "element", elementName)
            }
        }
        return error("$elementName not found on screen — make sure YouTube is open and the video is playing")
    }

    private fun result(vararg pairs: Any): JSONObject = JSONObject().apply {
        var i = 0
        while (i < pairs.size - 1) {
            put(pairs[i].toString(), pairs[i + 1])
            i += 2
        }
    }

    private fun error(msg: String): JSONObject = JSONObject().apply { put("error", msg) }
}
