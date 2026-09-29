package com.soltini.app.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * AgentToolExecutor
 *
 * Central dispatcher for all Gemini tool calls. Receives (toolName, args) from
 * BackgroundVoiceService.onToolCall() and routes to the appropriate Android API.
 *
 * ┌── Device Control ─────────────────────────────────────────────────────────┐
 * │  open_app(app_name)           — launch any installed app by name         │
 * │  lock_device()                — lock screen via Accessibility             │
 * │  wake_device()                — turn screen on via WakeLock              │
 * │  sleep_agent()                — handled by BackgroundVoiceService        │
 * │  press_back()                 — back button                              │
 * │  press_home()                 — home button                              │
 * │  press_recents()              — recent apps                              │
 * └───────────────────────────────────────────────────────────────────────────┘
 * ┌── Screen Automation ───────────────────────────────────────────────────────┐
 * │  read_screen()                — returns all visible UI text              │
 * │  click_element(text)          — click the UI element with matching text  │
 * │  tap_screen(x, y)             — tap at pixel coordinates                 │
 * │  swipe_screen(x1,y1,x2,y2)   — swipe/drag gesture                       │
 * │  type_text(text)              — type into focused input field            │
 * │  clear_text()                 — clear the focused input field            │
 * │  scroll_down()                — scroll the main view down                │
 * │  scroll_up()                  — scroll the main view up                  │
 * └───────────────────────────────────────────────────────────────────────────┘
 * ┌── YouTube Automation ──────────────────────────────────────────────────────┐
 * │  youtube_search, youtube_open_video, youtube_play_pause, youtube_seek,   │
 * │  youtube_next, youtube_previous, youtube_like, youtube_dislike,          │
 * │  youtube_subscribe, youtube_skip_ad, youtube_set_quality,                │
 * │  youtube_fullscreen, youtube_mute, youtube_captions, read_youtube_screen │
 * └───────────────────────────────────────────────────────────────────────────┘
 * ┌── WhatsApp & Reels Automation ─────────────────────────────────────────────┐
 * │  whatsapp_send_message, whatsapp_call, next_reel, previous_reel          │
 * └───────────────────────────────────────────────────────────────────────────┘
 * ┌── Maps & Email Automation ─────────────────────────────────────────────────┐
 * │  maps_navigate(destination, mode)  — turn-by-turn navigation (Intent-based)│
 * │  maps_search(query)                — show a place on the map              │
 * │  send_email(to, subject, body, auto_send) — compose via Gmail; auto_send  │
 * │  defaults to false so Boss reviews before sending (irreversible action)   │
 * └───────────────────────────────────────────────────────────────────────────┘
 */
class AgentToolExecutor(private val context: Context) {

    companion object {
        private const val TAG = "AgentToolExecutor"

        @Volatile
        var lastDiscoveredUrl: String? = null
        @Volatile
        var lastDiscoveredTitle: String? = null
        @Volatile
        var lastDiscoveredSources: List<com.soltini.app.reach.model.ResearchSource> = emptyList()
    }

    private val youtubeAutomator = YouTubeAutomator(context)
    private val whatsappAutomator = WhatsAppAutomator(context)
    private val instagramAutomator = InstagramAutomator(context)
    private val reelsAutomator = ReelsAutomator(context)
    private val mapsAutomator = MapsAutomator(context)
    private val gmailAutomator = GmailAutomator(context)
    private val deviceHardware = DeviceHardwareController(context)
    private val publicApiRegistry = com.soltini.app.apis.PublicApiRegistry.getInstance(context)
    private val publicApiExecutor = com.soltini.app.apis.PublicApiExecutor(context)
    private val agentReachGateway = com.soltini.app.reach.gateway.AgentReachGatewayImpl.getInstance(context)
    private val appSettings = com.soltini.app.settings.AppSettings(context)
    val screenOperator = ScreenOperator(context)
    val formAutomator = FormAutomator(context)
    val screenAnalyzer = ScreenAnalyzer(context)

    /**
     * Main dispatch function. Returns a JSONObject result sent back to Gemini
     * as a toolResponse so it can verbally confirm what happened.
     */
    fun execute(toolName: String, args: JSONObject): JSONObject {
        Log.i(TAG, "Executing tool: $toolName | args: $args")
        return when (toolName) {
            // ── Agent Reach: Background Internet Information Engine ───────
            "agent_reach.status", "agent_reach_status" -> executeAgentReachStatus()
            "agent_reach.health", "agent_reach_health" -> executeAgentReachHealth()
            "agent_reach.test", "agent_reach_test"     -> executeAgentReachTest()

            "agent_reach.web.search", "agent_reach_web_search", "reach_web_search" -> executeAgentReachWebSearch(
                query = args.optString("query", args.optString("q", args.optString("search", args.optString("text", "")))),
                maxResults = args.optInt("max_results", args.optInt("limit", 5))
            )

            "agent_reach.web.read", "agent_reach_web_read", "reach_web_read" -> executeAgentReachWebRead(
                url = args.optString("url", args.optString("link", ""))
            )

            "agent_reach.youtube", "agent_reach_youtube", "reach_youtube" -> executeAgentReachYouTube(
                queryOrUrl = args.optString("url", args.optString("url_or_id", args.optString("video_id", args.optString("query", ""))))
            )

            "agent_reach.github", "agent_reach_github", "reach_github" -> executeAgentReachGitHub(
                repoOrQuery = args.optString("repo", args.optString("owner_repo", args.optString("query", "")))
            )

            "agent_reach.reddit", "agent_reach_reddit", "reach_reddit" -> executeAgentReachReddit(
                query = args.optString("query", args.optString("topic", "")),
                subreddit = args.optString("subreddit", "").ifBlank { null },
                url = args.optString("url", "").ifBlank { null }
            )

            "agent_reach.rss", "agent_reach_rss", "reach_rss" -> executeAgentReachRss(
                url = args.optString("url", args.optString("feed_url", ""))
            )

            "agent_reach.research", "agent_reach_research", "reach_multi_research" -> executeAgentReachResearch(
                query = args.optString("query", args.optString("topic", "")),
                mode = args.optString("mode", appSettings.deepResearchLevel)
            )

            // ── Visual Mode (Browser Agent / Chrome) ─────────────────────
            "chrome_search" -> openChromeSearch(
                rawQuery = args.optString("query", args.optString("q", args.optString("search", "")))
            )
            "chrome_open_url" -> openUrlInChrome(
                url = args.optString("url", args.optString("link", ""))
            )
            "open_last_result_in_chrome" -> openLastResultInChrome()

            // ── Device Control & Web / Search ────────────────────────────
            "open_app"      -> openApp(args.optString("app_name", ""))
            "search_google", "google_search" -> {
                val raw = args.optString("query", args.optString("q", args.optString("search", args.optString("text", ""))))
                val forceChrome = args.optBoolean("open_in_browser", false) ||
                        args.optBoolean("force_chrome", false) ||
                        raw.lowercase().contains("chrome") ||
                        appSettings.internetRetrievalMode == com.soltini.app.settings.AppSettings.MODE_BROWSER_ONLY

                if (forceChrome) {
                    openChromeSearch(raw)
                } else {
                    executeAgentReachWebSearch(raw, maxResults = 5)
                }
            }
            "open_url", "browse_url" -> {
                val url = args.optString("url", args.optString("link", ""))
                val forceChrome = args.optBoolean("open_in_browser", false) ||
                        args.optBoolean("open_in_chrome", false) ||
                        appSettings.internetRetrievalMode == com.soltini.app.settings.AppSettings.MODE_BROWSER_ONLY
                if (forceChrome) {
                    openUrlInChrome(url)
                } else {
                    executeAgentReachWebRead(url)
                }
            }
            "lock_device"   -> accessibilityAction { it.lockScreen() } success "Device locked"
            "wake_device"   -> wakeDevice()
            "sleep_agent"   -> result("status", "sleep_requested") // handled in BackgroundVoiceService
            "press_back"    -> accessibilityAction { it.pressBack() } success "Back pressed"
            "press_home"    -> accessibilityAction { it.pressHome() } success "Home pressed"
            "press_recents" -> accessibilityAction { it.pressRecents() } success "Recents opened"
            "press_enter", "submit_search", "ime_enter" -> screenOperator.pressEnter()

            // ── Hardware Controls ─────────────────────────────────────────
            "toggle_torch", "set_torch", "torch_on", "torch_off" -> {
                val enable = if (toolName == "torch_off") false else if (toolName == "torch_on") true else args.optBoolean("enabled", true)
                deviceHardware.setTorch(enable)
            }
            "set_volume" -> deviceHardware.setVolume(args.optInt("percent", args.optInt("volume", 50)))
            "increase_volume", "volume_up" -> deviceHardware.adjustVolume("UP")
            "decrease_volume", "volume_down" -> deviceHardware.adjustVolume("DOWN")
            "mute_volume", "mute_phone" -> deviceHardware.setMute(args.optBoolean("mute", true))
            "set_ringer_mode" -> deviceHardware.setRingerMode(args.optString("mode", args.optString("ringer_mode", "NORMAL")))
            "take_screenshot" -> accessibilityAction { it.takeScreenshot() } success "Screenshot taken"
            "open_notifications", "show_notifications" -> accessibilityAction { it.openNotifications() } success "Notifications panel opened"
            "open_quick_settings", "show_quick_settings" -> accessibilityAction { it.openQuickSettings() } success "Quick settings opened"
            "open_power_dialog", "power_menu" -> accessibilityAction { it.openPowerDialog() } success "Power menu opened"
            "toggle_split_screen", "split_screen" -> accessibilityAction { it.toggleSplitScreen() } success "Split screen toggled"
            "media_play_pause" -> deviceHardware.dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "media_next" -> deviceHardware.dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            "media_previous" -> deviceHardware.dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "get_device_status", "check_battery", "device_info" -> deviceHardware.getDeviceStatus()
            "set_alarm" -> deviceHardware.setAlarm(args.optInt("hour", 7), args.optInt("minute", 0), args.optString("message", "Alarm"))
            "set_timer" -> deviceHardware.setTimer(args.optInt("seconds", args.optInt("duration_seconds", 300)), args.optString("message", "Timer"))
            "get_clipboard", "read_clipboard" -> deviceHardware.getClipboard()
            "copy_to_clipboard" -> deviceHardware.copyToClipboard(args.optString("text", ""))

            // ── Screen Automation & Semantic Operator ─────────────────────
            "read_screen", "observe_screen" -> observeScreen()
            "inspect_screen" -> screenOperator.inspectScreen()
            "smart_click", "click_element" -> screenOperator.clickElement(
                target = args.optString("target", args.optString("text", args.optString("label", ""))),
                autoScroll = args.optBoolean("auto_scroll", true)
            )
            "smart_type"    -> screenOperator.typeIntoField(
                targetField = args.optString("field", args.optString("target", "")).takeIf { it.isNotBlank() },
                textToType = args.optString("text", args.optString("query", "")),
                clearFirst = args.optBoolean("clear_first", false),
                autoScroll = args.optBoolean("auto_scroll", true),
                autoDismissKeyboard = args.optBoolean("auto_dismiss_keyboard", false),
                pressEnterAfter = args.optBoolean("press_enter", args.optBoolean("submit", false))
            )
            "fill_form", "smart_fill_form", "universal_fill_form" -> formAutomator.fillForm(
                fields = args.optJSONArray("fields") ?: JSONArray(),
                autoSubmit = args.optBoolean("auto_submit", false),
                submitButtonText = args.optString("submit_button", "").takeIf { it.isNotBlank() },
                allowRisky = args.optBoolean("allow_risky", false)
            )
            "scan_form", "scan_form_fields", "inspect_form" -> formAutomator.scanForm()
            "fill_single_field" -> {
                val field = args.optString("field", args.optString("label", ""))
                val value = args.optString("value", args.optString("text", ""))
                val fieldsArr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("label", field)
                        put("value", value)
                    })
                }
                formAutomator.fillForm(fieldsArr, autoSubmit = false)
            }
            "toggle_checkbox", "smart_toggle" -> screenOperator.toggleCheckable(
                target = args.optString("target", args.optString("label", "")),
                desiredState = if (args.has("checked")) args.optBoolean("checked") else null
            )
            "select_dropdown", "smart_select_dropdown" -> screenOperator.selectDropdownOption(
                dropdownLabel = args.optString("dropdown", args.optString("label", "")),
                optionText = args.optString("option", args.optString("value", ""))
            )
            "hide_keyboard" -> screenOperator.hideKeyboard()
            "swipe_direction" -> screenOperator.swipeDirection(
                direction = args.optString("direction", "UP"),
                distancePercent = args.optDouble("distance_percent", 0.5).toFloat()
            )
            "tap_percent"   -> screenOperator.tapPercent(
                percentX = args.optDouble("percent_x", 0.5).toFloat(),
                percentY = args.optDouble("percent_y", 0.5).toFloat()
            )
            "tap_screen"    -> tapScreen(
                x = args.optDouble("x", -1.0).toFloat(),
                y = args.optDouble("y", -1.0).toFloat()
            )
            "swipe_screen"  -> swipeScreen(
                x1 = args.optDouble("x1", 0.0).toFloat(),
                y1 = args.optDouble("y1", 0.0).toFloat(),
                x2 = args.optDouble("x2", 0.0).toFloat(),
                y2 = args.optDouble("y2", 0.0).toFloat(),
                durationMs = args.optLong("duration_ms", 300)
            )
            "type_text"     -> {
                val text = args.optString("text", args.optString("query", ""))
                val field = args.optString("field", args.optString("target", "")).takeIf { it.isNotBlank() }
                val pressEnter = args.optBoolean("press_enter", args.optBoolean("submit", false))
                val smartTypeRes = screenOperator.typeIntoField(field, text, pressEnterAfter = pressEnter)
                if (smartTypeRes.optString("status") == "success") smartTypeRes else typeText(text)
            }
            "clear_text"    -> accessibilityAction { it.clearText() } success "Text cleared"
            "scroll_down"   -> accessibilityAction { it.scrollDown() } success "Scrolled down"
            "scroll_up"     -> accessibilityAction { it.scrollUp() } success "Scrolled up"

            // ── YouTube Automation ────────────────────────────────────────
            "youtube_search"      -> {
                val raw = args.optString("query", args.optString("q", ""))
                val clean = com.soltini.app.orchestrator.MyraCommandParser.cleanYouTubeQuery(raw).ifBlank { raw.trim() }
                youtubeAutomator.search(clean)
            }
            "youtube_play"        -> {
                val raw = args.optString("query", args.optString("song", args.optString("title", "")))
                val clean = com.soltini.app.orchestrator.MyraCommandParser.cleanYouTubeQuery(raw).ifBlank { raw.trim() }
                youtubeAutomator.playSong(clean)
            }
            "youtube_open_video"  -> {
                val target = args.optString("video_id", "")
                    .ifBlank { args.optString("query", "") }
                    .ifBlank { args.optString("title", "") }
                    .ifBlank { args.optString("url", "") }
                youtubeAutomator.openVideo(target)
            }
            "youtube_play_pause"  -> youtubeAutomator.playPause()
            "youtube_seek"        -> youtubeAutomator.seek(args.optInt("seconds", 10))
            "youtube_next"        -> youtubeAutomator.nextVideo()
            "youtube_previous"    -> youtubeAutomator.previousVideo()
            "youtube_like"        -> youtubeAutomator.likeVideo()
            "youtube_dislike"     -> youtubeAutomator.dislikeVideo()
            "youtube_subscribe"   -> youtubeAutomator.subscribe()
            "youtube_skip_ad"     -> youtubeAutomator.skipAd()
            "youtube_set_quality" -> youtubeAutomator.setQuality(args.optString("quality", "1080p"))
            "youtube_fullscreen"  -> youtubeAutomator.toggleFullscreen()
            "youtube_captions"    -> youtubeAutomator.toggleCaptions()
            "youtube_mute"        -> youtubeAutomator.toggleMute()
            "read_youtube_screen" -> youtubeAutomator.readYouTubeScreen()

            // ── WhatsApp Automation ───────────────────────────────────────
            "whatsapp_send_message" -> whatsappAutomator.sendMessage(args.optString("contact_name", ""), args.optString("message", ""))
            "maps_navigate" -> mapsAutomator.navigate(args.optString("destination", ""), args.optString("mode", "driving"))
            "maps_search" -> mapsAutomator.searchPlace(args.optString("query", ""))
            "send_email" -> gmailAutomator.composeEmail(
                args.optString("to", ""),
                args.optString("subject", ""),
                args.optString("body", args.optString("message", "")),
                args.optBoolean("auto_send", false)
            )
            "whatsapp_call"         -> whatsappAutomator.makeCall(args.optString("contact_name", ""), args.optBoolean("is_video", false))

            // ── Instagram Automation ──────────────────────────────────────
            "instagram_send_message" -> kotlinx.coroutines.runBlocking {
                instagramAutomator.sendMessage(
                    username = args.optString("username", args.optString("contact_name", "")),
                    message = args.optString("message", "")
                )
            }
            "instagram_open_profile" -> instagramAutomator.openProfile(args.optString("username", ""))

            // ── Autonomous Messaging / Busy Mode ───────────────────────────
            "set_busy_mode" -> {
                val enable = args.optBoolean("enable", true)
                val reason = args.optString("reason", "busy")
                val busyMgr = com.soltini.app.messaging.BusyModeManager.getInstance(context)
                if (enable) {
                    val sanitized = busyMgr.enableBusyMode(reason)
                    JSONObject().apply {
                        put("status", "success")
                        put("busy_mode_active", true)
                        put("reason", sanitized)
                        put("message", "Busy mode enabled. Auto-replying to WhatsApp, Instagram, Telegram, SMS messages contextually.")
                    }
                } else {
                    val summary = busyMgr.disableBusyMode()
                    JSONObject().apply {
                        put("status", "success")
                        put("busy_mode_active", false)
                        put("replied_count", summary.totalRepliedCount)
                        put("unique_senders", org.json.JSONArray(summary.uniqueSenders))
                        put("message", "Busy mode disabled. Replied to ${summary.totalRepliedCount} messages.")
                    }
                }
            }
            "get_busy_mode_status" -> {
                val busyMgr = com.soltini.app.messaging.BusyModeManager.getInstance(context)
                val active = busyMgr.isBusyModeActive()
                val reason = busyMgr.getBusyReason()
                val history = busyMgr.getRecentHistory(10)
                val arr = org.json.JSONArray()
                history.forEach { arr.put(it.toJsonObject()) }
                JSONObject().apply {
                    put("status", "success")
                    put("busy_mode_active", active)
                    put("reason", reason)
                    put("total_replied", history.size)
                    put("history", arr)
                }
            }

            // ── Reels Automation ──────────────────────────────────────────
            "next_reel"             -> reelsAutomator.nextReel()
            "previous_reel"         -> reelsAutomator.previousReel()

            // ── Public APIs Integration (1,756+ APIs across 51 categories) ──
            "search_public_apis" -> {
                val query = args.optString("query", "")
                val category = args.optString("category", "").ifBlank { null }
                val noAuth = args.optBoolean("require_no_auth", false)
                val limit = args.optInt("limit", 10)
                val results = publicApiRegistry.searchApis(query, category, noAuth, limit)
                val arr = org.json.JSONArray()
                results.forEach { arr.put(it.toJsonObject()) }
                JSONObject().apply {
                    put("status", "success")
                    put("query", query)
                    put("total_found", results.size)
                    put("apis", arr)
                }
            }
            "list_public_api_categories" -> {
                val cats = publicApiRegistry.getCategories()
                val arr = org.json.JSONArray()
                cats.forEach { arr.put(it) }
                JSONObject().apply {
                    put("status", "success")
                    put("total_categories", cats.size)
                    put("total_apis", publicApiRegistry.getTotalApisCount())
                    put("categories", arr)
                }
            }
            "get_apis_by_category" -> {
                val category = args.optString("category", "")
                val results = publicApiRegistry.getApisByCategory(category)
                val arr = org.json.JSONArray()
                results.forEach { arr.put(it.toJsonObject()) }
                JSONObject().apply {
                    put("status", "success")
                    put("category", category)
                    put("total_apis", results.size)
                    put("apis", arr)
                }
            }
            "recommend_api_for_task" -> {
                val task = args.optString("task", args.optString("task_description", args.optString("query", "")))
                publicApiRegistry.recommendApisForTask(task)
            }
            "execute_public_api" -> kotlinx.coroutines.runBlocking {
                val url = args.optString("url", "")
                val method = args.optString("method", "GET")
                val body = args.optString("body", "").ifBlank { null }
                publicApiExecutor.executeApi(url = url, method = method, body = body)
            }
            "fetch_crypto_price" -> kotlinx.coroutines.runBlocking {
                val coin = args.optString("coin", args.optString("crypto", "bitcoin"))
                val currency = args.optString("currency", "usd")
                publicApiExecutor.fetchCryptoPrice(coin, currency)
            }
            "fetch_weather" -> kotlinx.coroutines.runBlocking {
                val city = args.optString("city", args.optString("location", "Delhi"))
                publicApiExecutor.fetchWeather(city)
            }
            "lookup_dictionary" -> kotlinx.coroutines.runBlocking {
                val word = args.optString("word", "")
                publicApiExecutor.lookupDictionaryWord(word)
            }
            "fetch_currency_rate" -> kotlinx.coroutines.runBlocking {
                val from = args.optString("from", "USD")
                val to = args.optString("to", "INR")
                publicApiExecutor.fetchCurrencyRate(from, to)
            }
            "lookup_ip" -> kotlinx.coroutines.runBlocking {
                val ip = args.optString("ip", "").ifBlank { null }
                publicApiExecutor.lookupIp(ip)
            }
            "fetch_random_joke" -> kotlinx.coroutines.runBlocking {
                publicApiExecutor.fetchRandomJoke()
            }
            "fetch_random_advice" -> kotlinx.coroutines.runBlocking {
                publicApiExecutor.fetchRandomAdvice()
            }

            // ── Phone Call Control & SMS ───────────────────────────────────
            "get_call_info", "get_incoming_call", "who_is_calling" -> {
                val callMgr = com.soltini.app.telephony.CallNotificationManager.getInstance(context)
                val info = callMgr.getCurrentCallInfo()
                val res = info.toJsonObject()
                if (info.isIncoming) {
                    val caller = info.callerName ?: "Unknown"
                    res.put("message", "Incoming call from $caller (${info.phoneNumber ?: "Unknown number"}).")
                } else {
                    res.put("message", "No incoming call right now. Current call state is ${info.callState}.")
                }
                res
            }
            "answer_call"           -> {
                val callMgr = com.soltini.app.telephony.CallNotificationManager.getInstance(context)
                val res = callMgr.answerCallDetailed()
                result("status", if (res.success) "success" else "failed", "method", res.method, "message", res.message)
            }
            "reject_call"           -> {
                val callMgr = com.soltini.app.telephony.CallNotificationManager.getInstance(context)
                val res = callMgr.rejectCallDetailed()
                result("status", if (res.success) "success" else "failed", "method", res.method, "message", res.message)
            }
            "make_phone_call"       -> {
                val target = args.optString("contact_or_number", "")
                val success = com.soltini.app.telephony.CallNotificationManager.getInstance(context).makeCall(target)
                result("status", if (success) "success" else "error")
            }
            "send_sms"              -> {
                val recipient = args.optString("recipient", args.optString("contact", args.optString("phone_number", "")))
                val message = args.optString("message", args.optString("text", ""))
                val cleanMsg = com.soltini.app.orchestrator.MyraCommandParser.cleanMessageBody(message).ifBlank { message.trim() }
                val callMgr = com.soltini.app.telephony.CallNotificationManager.getInstance(context)
                val matches = callMgr.searchContacts(recipient)
                if (matches.isEmpty()) {
                    error("No contact found for '$recipient'. Please specify a valid contact name or phone number.")
                } else if (matches.size > 1 && !matches.any { it.name.equals(recipient, ignoreCase = true) }) {
                    val listStr = matches.take(3).joinToString(", ") { "${it.name} (${it.phoneNumber})" }
                    result("status", "clarification_needed", "message", "Multiple contacts found for '$recipient': $listStr. Which one should I message?")
                } else {
                    val targetMatch = matches.find { it.name.equals(recipient, ignoreCase = true) } ?: matches.first()
                    val smsSender = com.soltini.app.telephony.SmsSender(context)
                    val sendRes = smsSender.sendSms(targetMatch.phoneNumber, cleanMsg)
                    result(
                        "status", if (sendRes.success) "success" else "failed",
                        "recipient", targetMatch.name,
                        "phone", targetMatch.phoneNumber,
                        "method", sendRes.method,
                        "message", sendRes.message
                    )
                }
            }
            "search_contacts"       -> {
                val query = args.optString("query", args.optString("name", ""))
                val callMgr = com.soltini.app.telephony.CallNotificationManager.getInstance(context)
                val matches = callMgr.searchContacts(query)
                val arr = JSONArray()
                matches.forEach { arr.put(JSONObject().apply { put("name", it.name); put("phoneNumber", it.phoneNumber) }) }
                result("status", "success", "total", matches.size, "contacts", arr)
            }

            else -> error("Unknown tool: $toolName")
        }
    }

    // ─── Device Control ───────────────────────────────────────────────────────

    private fun openApp(appName: String): JSONObject {
        if (appName.isBlank()) return error("app_name is required")

        val pm: PackageManager = context.packageManager
        val query = appName.lowercase().trim()
        val match = pm.getInstalledApplications(PackageManager.GET_META_DATA).firstOrNull {
            val label = pm.getApplicationLabel(it).toString().lowercase()
            label.contains(query) || query.contains(label)
        } ?: return error("No app found matching: $appName")

        val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
            ?: return error("App found (${match.packageName}) but cannot be launched")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return result("status", "opened", "app", pm.getApplicationLabel(match).toString())
    }

    private fun wakeDevice(): JSONObject {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Soltini::WakeDevice"
            )
            wl.acquire(3000L)
            wl.release()
            result("status", "screen_on")
        } catch (e: Exception) {
            error(e.message ?: "Failed to wake device")
        }
    }

    // ─── Screen Automation ────────────────────────────────────────────────────

    private fun observeScreen(): JSONObject {
        val companion = com.soltini.app.companion.ScreenCompanionManager.getInstance(context)
        val snapshot = try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(2500L) {
                    companion.captureCurrentScreenWithVision()
                }
            } ?: companion.captureCurrentScreenContext()
        } catch (_: Exception) {
            companion.captureCurrentScreenContext()
        }
        return if (!snapshot.isAvailable) {
            readScreen()
        } else if (snapshot.isSecure) {
            JSONObject().apply {
                put("status", "secure_screen")
                put("message", "Screen is protected (banking or secure screen).")
            }
        } else {
            JSONObject().apply {
                put("status", "ok")
                put("app", snapshot.currentApp)
                put("screen_content", snapshot.toHumanSummary())
            }
        }
    }

    private fun readScreen(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        val text = svc.readScreen()
        return JSONObject().apply {
            put("status", "ok")
            put("screen_content", text)
        }
    }

    private fun clickElement(text: String): JSONObject {
        if (text.isBlank()) return error("text is required")
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        return if (svc.clickByText(text)) {
            result("status", "clicked", "element", text)
        } else {
            error("No clickable element found matching: $text")
        }
    }

    private fun tapScreen(x: Float, y: Float): JSONObject {
        if (x < 0 || y < 0) return error("x and y coordinates are required")
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        return if (svc.tapAt(x, y)) {
            result("status", "tapped", "x", x.toString(), "y", y.toString())
        } else {
            error("Tap gesture failed — requires Android 7+ and gesture permission")
        }
    }

    private fun swipeScreen(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        return if (svc.swipe(x1, y1, x2, y2, durationMs)) {
            result("status", "swiped")
        } else {
            error("Swipe gesture failed")
        }
    }

    private fun typeText(text: String): JSONObject {
        if (text.isBlank()) return error("text is required")
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        return if (svc.typeText(text)) {
            result("status", "typed", "text", text)
        } else {
            error("No editable text field found on screen. Please tap a text field first.")
        }
    }

    // ─── Visual Mode (Chrome / Browser Agent) ─────────────────────────────────

    fun openChromeSearch(rawQuery: String): JSONObject {
        val query = com.soltini.app.orchestrator.MyraCommandParser.cleanWebSearchQuery(rawQuery).ifBlank { rawQuery.trim() }
        if (query.isBlank()) return error("query is required for chrome search")
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://www.google.com/search?q=$encoded"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                setPackage("com.android.chrome")
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(fallbackIntent)
            }
            result("status", "success", "action", "chrome_search", "query", query, "url", url, "message", "Chrome me '$query' search khol diya hai.")
        } catch (e: Exception) {
            Log.e(TAG, "openChromeSearch failed: ${e.message}")
            error("Failed to open Chrome: ${e.message}")
        }
    }

    fun openUrlInChrome(url: String): JSONObject {
        if (url.isBlank()) return error("url is required")
        return try {
            val fullUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                setPackage("com.android.chrome")
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(fallbackIntent)
            }
            result("status", "success", "action", "chrome_open_url", "url", fullUrl, "message", "Chrome me website khol di hai: $fullUrl")
        } catch (e: Exception) {
            Log.e(TAG, "openUrlInChrome failed: ${e.message}")
            error("Failed to open URL in Chrome: ${e.message}")
        }
    }

    fun openLastResultInChrome(): JSONObject {
        val lastUrl = lastDiscoveredUrl ?: com.soltini.app.reach.data.ReachSessionState.lastResultUrl
        if (lastUrl.isNullOrBlank()) {
            return JSONObject().apply {
                put("status", "not_found")
                put("message", "Boss, abhi koi recent internet research URL save nahi hai jise Chrome me khola ja sake.")
            }
        }
        return openUrlInChrome(lastUrl)
    }

    // ─── Background Mode (Agent Reach Information Engine) ─────────────────────

    private fun executeAgentReachStatus(): JSONObject = kotlinx.coroutines.runBlocking {
        try {
            val report = agentReachGateway.getHealth(forceRefresh = false)
            val ready = report.readyCapabilities
            val total = report.totalCapabilities
            val statusSummary = report.toAgentReachStatusSummary()
            val msg = "Agent Reach Internet Engine: $ready/$total capabilities online and active."
            JSONObject().apply {
                put("status", "success")
                put("connected", ready > 0)
                put("ready_capabilities", ready)
                put("total_capabilities", total)
                put("network_available", report.networkAvailable)
                put("summary", statusSummary)
                put("message", msg)
                put("instruction", "Inform Boss clearly: 'Agent Reach Internet Engine active hai ($ready/$total capabilities online). Background internet research ready hai.'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeAgentReachStatus failed: ${e.message}", e)
            JSONObject().apply {
                put("status", "error")
                put("connected", false)
                put("message", "Status check failed: ${e.message}")
            }
        }
    }

    private fun executeAgentReachHealth(): JSONObject = kotlinx.coroutines.runBlocking {
        try {
            val report = agentReachGateway.getHealth(forceRefresh = true)
            JSONObject().apply {
                put("status", "success")
                put("connected", report.readyCapabilities > 0)
                put("ready_capabilities", report.readyCapabilities)
                put("total_capabilities", report.totalCapabilities)
                put("report", report.toFormattedReport())
                put("summary", report.toAgentReachStatusSummary())
                put("instruction", "Share the Agent Reach Doctor health diagnostics summary with Boss.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeAgentReachHealth failed: ${e.message}", e)
            JSONObject().apply {
                put("status", "error")
                put("message", "Doctor health check failed: ${e.message}")
            }
        }
    }

    private fun executeAgentReachTest(): JSONObject = kotlinx.coroutines.runBlocking {
        try {
            val testResults = agentReachGateway.runTestCheck()
            testResults.put("instruction", "Report test results to Boss: core internet channels tested successfully.")
            testResults
        } catch (e: Exception) {
            Log.e(TAG, "executeAgentReachTest failed: ${e.message}", e)
            JSONObject().apply {
                put("status", "error")
                put("message", "Test execution failed: ${e.message}")
            }
        }
    }

    private fun executeAgentReachWebSearch(query: String, maxResults: Int): JSONObject = kotlinx.coroutines.runBlocking {
        val cleanQuery = com.soltini.app.orchestrator.MyraCommandParser.cleanWebSearchQuery(query).ifBlank { query.trim() }
        if (cleanQuery.isBlank()) return@runBlocking error("query is required for web search")
        try {
            val sources = agentReachGateway.searchWeb(cleanQuery, maxResults.coerceIn(1, 10))
            if (sources.isNotEmpty()) {
                val topSource = sources.first()
                lastDiscoveredUrl = topSource.url
                lastDiscoveredTitle = topSource.title
                lastDiscoveredSources = sources
                com.soltini.app.reach.data.ReachSessionState.lastResultUrl = topSource.url
                com.soltini.app.reach.data.ReachSessionState.lastResultTitle = topSource.title
                com.soltini.app.reach.data.ReachSessionState.lastResearchSummary = topSource.excerpt
            }
            val arr = JSONArray()
            sources.forEach { arr.put(it.toJsonObject()) }

            JSONObject().apply {
                put("status", "success")
                put("query", cleanQuery)
                put("total_found", sources.size)
                put("sources", arr)
                put("top_result", sources.firstOrNull()?.snippet ?: "")
                put("message", "Retrieved ${sources.size} verified web sources for '$cleanQuery'")
                put("instruction", "Speak the verified findings conversationally to Boss in Hindi/Hinglish. Synthesize the key facts from sources. Do NOT open Chrome.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeAgentReachWebSearch failed: ${e.message}", e)
            error("Agent Reach web search failed: ${e.message}")
        }
    }

    private fun executeAgentReachWebRead(url: String): JSONObject = kotlinx.coroutines.runBlocking {
        if (url.isBlank()) return@runBlocking error("url is required to read webpage")
        try {
            val source = agentReachGateway.readWebPage(url)
            lastDiscoveredUrl = source.url
            lastDiscoveredTitle = source.title
            lastDiscoveredSources = listOf(source)
            com.soltini.app.reach.data.ReachSessionState.lastResultUrl = source.url
            com.soltini.app.reach.data.ReachSessionState.lastResultTitle = source.title
            com.soltini.app.reach.data.ReachSessionState.lastResearchSummary = source.content.take(500)

            JSONObject().apply {
                put("status", "success")
                put("title", source.title)
                put("url", source.url)
                put("content", source.content.take(4000))
                put("excerpt", source.excerpt)
                put("source", source.toJsonObject())
                put("instruction", "Summarize the webpage content clearly for Boss in natural Hindi/Hinglish. Do NOT open Chrome.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeAgentReachWebRead failed: ${e.message}", e)
            error("Agent Reach web read failed: ${e.message}")
        }
    }

    private fun executeAgentReachYouTube(queryOrUrl: String): JSONObject = kotlinx.coroutines.runBlocking {
        if (queryOrUrl.isBlank()) return@runBlocking error("query or video URL is required")
        try {
            val isUrlOrId = queryOrUrl.contains("youtube.com") ||
                    queryOrUrl.contains("youtu.be") ||
                    (queryOrUrl.length == 11 && !queryOrUrl.contains(" "))

            if (isUrlOrId) {
                val source = agentReachGateway.getYouTubeContent(queryOrUrl)
                lastDiscoveredUrl = source.url
                lastDiscoveredTitle = source.title
                lastDiscoveredSources = listOf(source)
                com.soltini.app.reach.data.ReachSessionState.lastResultUrl = source.url
                com.soltini.app.reach.data.ReachSessionState.lastResultTitle = source.title
                com.soltini.app.reach.data.ReachSessionState.lastResearchSummary = source.content.take(500)

                JSONObject().apply {
                    put("status", "success")
                    put("title", source.title)
                    put("url", source.url)
                    put("author", source.author ?: "")
                    put("content", source.content.take(4000))
                    put("source", source.toJsonObject())
                    put("instruction", "Explain and summarize this YouTube video transcript/content clearly to Boss in natural conversational Hindi/Hinglish. Do NOT open YouTube or Chrome.")
                }
            } else {
                val sources = agentReachGateway.searchYouTube(queryOrUrl, maxResults = 3)
                if (sources.isNotEmpty()) {
                    lastDiscoveredUrl = sources.first().url
                    lastDiscoveredTitle = sources.first().title
                    lastDiscoveredSources = sources
                    com.soltini.app.reach.data.ReachSessionState.lastResultUrl = sources.first().url
                    com.soltini.app.reach.data.ReachSessionState.lastResultTitle = sources.first().title
                }
                val arr = JSONArray()
                sources.forEach { arr.put(it.toJsonObject()) }

                JSONObject().apply {
                    put("status", "success")
                    put("query", queryOrUrl)
                    put("total_found", sources.size)
                    put("videos", arr)
                    put("instruction", "Tell Boss what videos were found on YouTube for '$queryOrUrl' with key details. Do NOT open the YouTube app.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeAgentReachYouTube failed: ${e.message}", e)
            error("Agent Reach YouTube extraction failed: ${e.message}")
        }
    }

    private fun executeAgentReachGitHub(repoOrQuery: String): JSONObject = kotlinx.coroutines.runBlocking {
        if (repoOrQuery.isBlank()) return@runBlocking error("repo or search query is required")
        try {
            val isRepoSlug = (repoOrQuery.contains("/") && !repoOrQuery.contains(" ")) ||
                    repoOrQuery.contains("github.com")

            if (isRepoSlug) {
                val slug = repoOrQuery.removePrefix("https://github.com/").removePrefix("http://github.com/").trim('/')
                val source = agentReachGateway.inspectGitHubRepository(slug)
                lastDiscoveredUrl = source.url
                lastDiscoveredTitle = source.title
                lastDiscoveredSources = listOf(source)
                com.soltini.app.reach.data.ReachSessionState.lastResultUrl = source.url
                com.soltini.app.reach.data.ReachSessionState.lastResultTitle = source.title
                com.soltini.app.reach.data.ReachSessionState.lastResearchSummary = source.content.take(500)

                JSONObject().apply {
                    put("status", "success")
                    put("repo", slug)
                    put("title", source.title)
                    put("url", source.url)
                    put("content", source.content.take(4000))
                    put("source", source.toJsonObject())
                    put("instruction", "Explain the GitHub repository architecture, purpose, and key features to Boss in natural Hindi/Hinglish. Do NOT open Chrome.")
                }
            } else {
                val sources = agentReachGateway.searchGitHub(repoOrQuery, maxResults = 3)
                if (sources.isNotEmpty()) {
                    lastDiscoveredUrl = sources.first().url
                    lastDiscoveredTitle = sources.first().title
                    lastDiscoveredSources = sources
                    com.soltini.app.reach.data.ReachSessionState.lastResultUrl = sources.first().url
                    com.soltini.app.reach.data.ReachSessionState.lastResultTitle = sources.first().title
                }
                val arr = JSONArray()
                sources.forEach { arr.put(it.toJsonObject()) }

                JSONObject().apply {
                    put("status", "success")
                    put("query", repoOrQuery)
                    put("total_found", sources.size)
                    put("repositories", arr)
                    put("instruction", "Summarize the top GitHub repositories found for '$repoOrQuery' to Boss.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeAgentReachGitHub failed: ${e.message}", e)
            error("Agent Reach GitHub inspection failed: ${e.message}")
        }
    }

    private fun executeAgentReachReddit(query: String, subreddit: String?, url: String?): JSONObject = kotlinx.coroutines.runBlocking {
        try {
            if (!url.isNullOrBlank()) {
                val source = agentReachGateway.readRedditContent(url)
                lastDiscoveredUrl = source.url
                lastDiscoveredTitle = source.title
                lastDiscoveredSources = listOf(source)
                com.soltini.app.reach.data.ReachSessionState.lastResultUrl = source.url
                com.soltini.app.reach.data.ReachSessionState.lastResultTitle = source.title

                JSONObject().apply {
                    put("status", "success")
                    put("title", source.title)
                    put("url", source.url)
                    put("content", source.content.take(4000))
                    put("source", source.toJsonObject())
                    put("instruction", "Summarize the Reddit discussion and community comments to Boss in natural Hindi/Hinglish. Do NOT open Chrome.")
                }
            } else {
                val cleanQ = query.ifBlank { "discussion" }
                val sources = agentReachGateway.searchReddit(cleanQ, subreddit, maxResults = 4)
                if (sources.isNotEmpty()) {
                    lastDiscoveredUrl = sources.first().url
                    lastDiscoveredTitle = sources.first().title
                    lastDiscoveredSources = sources
                    com.soltini.app.reach.data.ReachSessionState.lastResultUrl = sources.first().url
                    com.soltini.app.reach.data.ReachSessionState.lastResultTitle = sources.first().title
                }
                val arr = JSONArray()
                sources.forEach { arr.put(it.toJsonObject()) }

                JSONObject().apply {
                    put("status", "success")
                    put("query", cleanQ)
                    put("total_found", sources.size)
                    put("discussions", arr)
                    put("instruction", "Summarize what people on Reddit are discussing about '$cleanQ' to Boss in natural Hindi/Hinglish.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeAgentReachReddit failed: ${e.message}", e)
            error("Agent Reach Reddit retrieval failed: ${e.message}")
        }
    }

    private fun executeAgentReachRss(url: String): JSONObject = kotlinx.coroutines.runBlocking {
        if (url.isBlank()) return@runBlocking error("feed url is required")
        try {
            val sources = agentReachGateway.readRSS(url, maxItems = 5)
            if (sources.isNotEmpty()) {
                lastDiscoveredUrl = sources.first().url
                lastDiscoveredTitle = sources.first().title
                lastDiscoveredSources = sources
                com.soltini.app.reach.data.ReachSessionState.lastResultUrl = sources.first().url
                com.soltini.app.reach.data.ReachSessionState.lastResultTitle = sources.first().title
            }
            val arr = JSONArray()
            sources.forEach { arr.put(it.toJsonObject()) }

            JSONObject().apply {
                put("status", "success")
                put("feed_url", url)
                put("total_items", sources.size)
                put("items", arr)
                put("instruction", "Summarize the RSS news items for Boss.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeAgentReachRss failed: ${e.message}", e)
            error("Agent Reach RSS retrieval failed: ${e.message}")
        }
    }

    private fun executeAgentReachResearch(query: String, mode: String): JSONObject = kotlinx.coroutines.runBlocking {
        if (query.isBlank()) return@runBlocking error("query is required for deep research")
        try {
            val researchMode = when (mode.uppercase()) {
                "QUICK" -> com.soltini.app.reach.model.ResearchMode.QUICK
                "DEEP" -> com.soltini.app.reach.model.ResearchMode.DEEP
                else -> com.soltini.app.reach.model.ResearchMode.NORMAL
            }
            val plan = com.soltini.app.reach.planner.ResearchPlanner.createPlan(query).copy(mode = researchMode)
            val result = agentReachGateway.executeResearch(plan)

            if (result.sources.isNotEmpty()) {
                val top = result.sources.first()
                lastDiscoveredUrl = top.url
                lastDiscoveredTitle = top.title
                lastDiscoveredSources = result.sources
                com.soltini.app.reach.data.ReachSessionState.lastResultUrl = top.url
                com.soltini.app.reach.data.ReachSessionState.lastResultTitle = top.title
            }

            // Synthesize grounded answer
            val synthesizer = com.soltini.app.reach.integration.MyraReachSynthesizer(context, appSettings)
            val synthesizedAnswer = synthesizer.synthesizeResearch(query, result)
            com.soltini.app.reach.data.ReachSessionState.lastResearchSummary = synthesizedAnswer

            // Save in history store
            val historyStore = com.soltini.app.reach.data.ReachHistoryStore.getInstance(context)
            historyStore.saveHistory(
                com.soltini.app.reach.model.ResearchHistoryItem(
                    query = query,
                    mode = researchMode,
                    summary = synthesizedAnswer,
                    sourceCount = result.sources.size,
                    sourcesJson = JSONArray().apply { result.sources.forEach { put(it.toJsonObject()) } }.toString(),
                    durationMs = result.durationMs,
                    isSuccess = result.isSuccess
                )
            )

            val arr = JSONArray()
            result.sources.forEach { arr.put(it.toJsonObject()) }

            JSONObject().apply {
                put("status", if (result.isSuccess) "success" else "partial")
                put("query", query)
                put("total_sources", result.sources.size)
                put("synthesized_answer", synthesizedAnswer)
                put("source_tracking", result.toSourceTrackingSummary())
                put("sources", arr)
                put("message", "Completed multi-source research for '$query'")
                put("instruction", "Speak this synthesized research answer conversationally to Boss. Mention the sources used (e.g. Web, GitHub, Reddit, YouTube). Do NOT open Chrome.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeAgentReachResearch failed: ${e.message}", e)
            error("Agent Reach research failed: ${e.message}")
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Runs a lambda against the active AccessibilityService instance.
     * Returns an error JSONObject if the service is not running.
     */
    private fun accessibilityAction(action: (SoltiniAccessibilityService) -> Boolean): AccessibilityResult {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return AccessibilityResult.NoService
        val ok = action(svc)
        return if (ok) AccessibilityResult.Success else AccessibilityResult.Failed
    }

    private sealed class AccessibilityResult {
        object NoService : AccessibilityResult()
        object Success : AccessibilityResult()
        object Failed : AccessibilityResult()
    }

    private infix fun AccessibilityResult.success(successMsg: String): JSONObject = when (this) {
        is AccessibilityResult.NoService -> error("Accessibility Service not active. Enable it in Settings → Accessibility → Soltini")
        is AccessibilityResult.Success   -> result("status", successMsg)
        is AccessibilityResult.Failed    -> error("Action failed")
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
