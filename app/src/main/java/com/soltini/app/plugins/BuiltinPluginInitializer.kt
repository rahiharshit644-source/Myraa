package com.soltini.app.plugins

import android.content.Context
import android.util.Log
import com.soltini.app.agent.*
import com.soltini.app.apis.PublicApiExecutor
import com.soltini.app.apis.PublicApiRegistry
import com.soltini.app.companion.ScreenCompanionManager
import com.soltini.app.homeautomation.control.DeviceController
import com.soltini.app.settings.AppSettings
import com.soltini.app.telephony.CallNotificationManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONObject

/**
 * BuiltinPluginInitializer
 *
 * Populates MYRA's dynamic Plugin & Tool Registry with all built-in capabilities,
 * device actions, specialized agents, public APIs, and automations.
 */
object BuiltinPluginInitializer {

    private const val TAG = "BuiltinPluginInit"

    fun initializeAll(
        context: Context,
        registry: PluginRegistry,
        agentToolExecutor: AgentToolExecutor,
        appSettings: AppSettings
    ) {
        val publicApiExecutor = PublicApiExecutor(context)
        val publicApiRegistry = PublicApiRegistry.getInstance(context)
        val deviceHardware = DeviceHardwareController(context)
        val screenCompanion = ScreenCompanionManager.getInstance(context)
        val deviceController = DeviceController.create(context)
        val callManager = CallNotificationManager.getInstance(context)
        val youtubeAutomator = YouTubeAutomator(context)
        val smsSender = com.soltini.app.telephony.SmsSender(context)

        // 0. Agent Reach Capabilities (Internet Search, Web Reader, YouTube, GitHub, Reddit, Doctor)
        com.soltini.app.reach.integration.AgentReachPluginInitializer.registerReachPlugins(context, registry)

        // 1. Weather Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "weather_service",
                name = "Weather Service",
                description = "Retrieves live weather conditions and forecasts for any city or location.",
                category = PluginCategory.INFORMATION,
                tags = listOf("weather", "mausam", "temperature", "rain", "forecast", "climate"),
                riskLevel = RiskLevel.LOW,
                fallbackPluginId = "wttr_weather_fallback"
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val city = context.optString("city", context.optString("location", "Delhi"))
                val start = System.currentTimeMillis()
                val resp = publicApiExecutor.fetchWeather(city)
                val latency = System.currentTimeMillis() - start
                return if (resp.optString("status") != "error") {
                    PluginResult.success(metadata.id, "Weather fetched for $city", resp, latency)
                } else {
                    PluginResult.failure(metadata.id, resp.optString("message", "Failed to get weather"), latency)
                }
            }
        })

        // 2. Crypto Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "crypto_price_service",
                name = "Cryptocurrency Market Service",
                description = "Fetches real-time market prices for Bitcoin, Ethereum, Solana, and 500+ cryptocurrencies.",
                category = PluginCategory.INFORMATION,
                tags = listOf("crypto", "bitcoin", "btc", "ethereum", "eth", "coin", "market", "price"),
                riskLevel = RiskLevel.LOW
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val coin = context.optString("coin", context.optString("crypto", "bitcoin"))
                val curr = context.optString("currency", "usd")
                val start = System.currentTimeMillis()
                val resp = publicApiExecutor.fetchCryptoPrice(coin, curr)
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, "Crypto price for $coin in $curr", resp, latency)
            }
        })

        // 3. Dictionary Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "dictionary_service",
                name = "English Dictionary & Lexicon",
                description = "Looks up word definitions, phonetics, origins, and sentence examples.",
                category = PluginCategory.INFORMATION,
                tags = listOf("dictionary", "meaning", "definition", "word", "english", "vocab"),
                riskLevel = RiskLevel.LOW
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val word = context.optString("word")
                val start = System.currentTimeMillis()
                val resp = publicApiExecutor.lookupDictionaryWord(word)
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, "Definition of $word", resp, latency)
            }
        })

        // 4. Currency Exchange Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "currency_exchange_service",
                name = "Foreign Currency Exchange Rates",
                description = "Fetches live foreign exchange rates between fiat currencies (USD, INR, EUR, GBP, etc.).",
                category = PluginCategory.INFORMATION,
                tags = listOf("currency", "exchange", "dollar", "rupee", "inr", "usd", "forex"),
                riskLevel = RiskLevel.LOW
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val from = context.optString("from", "USD")
                val to = context.optString("to", "INR")
                val start = System.currentTimeMillis()
                val resp = publicApiExecutor.fetchCurrencyRate(from, to)
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, "Rate from $from to $to", resp, latency)
            }
        })

        // 5. Flashlight / Torch Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "device_torch_controller",
                name = "Flashlight / Torch Controller",
                description = "Toggles or sets the hardware camera flash / torch state on the device.",
                category = PluginCategory.ANDROID_DEVICE,
                tags = listOf("torch", "flashlight", "flash", "light", "roshni"),
                riskLevel = RiskLevel.MEDIUM,
                requiresInternet = false
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val start = System.currentTimeMillis()
                val res = agentToolExecutor.execute("toggle_torch", context.parameters)
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, res.optString("message", "Torch toggled"), res, latency)
            }
        })

        // 6. Volume Controller Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "device_volume_controller",
                name = "Device Volume & Ringer Controller",
                description = "Adjusts media volume, ringer mode (silent, vibrate, normal), and mute state.",
                category = PluginCategory.ANDROID_DEVICE,
                tags = listOf("volume", "sound", "aawaz", "mute", "silent", "vibrate", "ringer"),
                riskLevel = RiskLevel.MEDIUM,
                requiresInternet = false
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val start = System.currentTimeMillis()
                val res = if (context.parameters.has("percent")) {
                    agentToolExecutor.execute("set_volume", JSONObject().put("percent", context.optInt("percent", 50)))
                } else if (context.parameters.has("ringer_mode")) {
                    agentToolExecutor.execute("set_ringer_mode", JSONObject().put("mode", context.optString("ringer_mode", "NORMAL")))
                } else {
                    agentToolExecutor.execute("mute_volume", JSONObject().put("mute", true))
                }
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, res.optString("message", "Volume adjusted"), res, latency)
            }
        })

        // 7. Screenshot Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "device_screenshot_tool",
                name = "Screen Capture & Screenshot Tool",
                description = "Captures the current active screen of the phone.",
                category = PluginCategory.ANDROID_DEVICE,
                tags = listOf("screenshot", "screen", "capture", "photo", "screen shot"),
                riskLevel = RiskLevel.LOW,
                requiresAccessibility = true,
                requiresInternet = false
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val start = System.currentTimeMillis()
                val res = agentToolExecutor.execute("take_screenshot", JSONObject())
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, res.optString("message", "Screenshot taken"), res, latency)
            }
        })

        // 8. Vision & Screen Observer Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "screen_vision_observer",
                name = "Live Screen Vision & Reader",
                description = "Reads, observes, and visually analyzes whatever is currently visible on the user's screen.",
                category = PluginCategory.VISION,
                tags = listOf("screen", "dekho", "padho", "read", "observe", "vision", "post", "chat", "kya hai"),
                riskLevel = RiskLevel.LOW,
                requiresAccessibility = true
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val start = System.currentTimeMillis()
                val res = agentToolExecutor.execute("observe_screen", context.parameters)
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, res.optString("message", "Screen observed"), res, latency)
            }
        })

        // 9. Instagram Automation Plugin
        registry.registerPlugin(object : Plugin {
            private val insta = InstagramAutomator(context)
            override val metadata = PluginMetadata(
                id = "instagram_automation_agent",
                name = "Instagram Direct & Profile Agent",
                description = "Automates Instagram tasks: sends DMs to users, searches profiles, opens chats.",
                category = PluginCategory.SOCIAL,
                tags = listOf("instagram", "insta", "dm", "message", "chat", "profile"),
                riskLevel = RiskLevel.HIGH,
                requiresAccessibility = true
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val user = context.optString("username", context.optString("user", ""))
                val msg = context.optString("message", "")
                val start = System.currentTimeMillis()
                val res = if (msg.isNotBlank()) {
                    insta.sendMessage(user, msg)
                } else {
                    insta.openProfile(user)
                }
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, res.optString("message", "Instagram action completed"), res, latency)
            }
        })

        // 10. WhatsApp Automation Plugin
        registry.registerPlugin(object : Plugin {
            private val wa = WhatsAppAutomator(context)
            override val metadata = PluginMetadata(
                id = "whatsapp_automation_agent",
                name = "WhatsApp Message & Calling Agent",
                description = "Automates WhatsApp messaging, voice calls, and video calls to contacts.",
                category = PluginCategory.MESSAGING,
                tags = listOf("whatsapp", "chat", "message", "call", "bhejo", "msg"),
                riskLevel = RiskLevel.HIGH,
                requiresAccessibility = true
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val contact = context.optString("contact_name", context.optString("contact", ""))
                val msg = context.optString("message", "")
                val start = System.currentTimeMillis()
                val res = if (msg.isNotBlank()) {
                    wa.sendMessage(contact, msg)
                } else {
                    wa.makeCall(contact, false)
                }
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, res.optString("message", "WhatsApp action completed"), res, latency)
            }
        })

        // 11. Autonomous Screen Operator Agent
        registry.registerPlugin(object : Plugin {
            private val operator = ScreenOperator(context)
            private val controller = AgentPhoneController(context, agentToolExecutor, operator, appSettings)
            override val metadata = PluginMetadata(
                id = "screen_operator_agent",
                name = "Autonomous Phone Screen Operator",
                description = "Autonomous multi-step human phone operator for filling forms, browsing apps, tapping buttons, and shopping.",
                category = PluginCategory.AGENT,
                tags = listOf("operator", "form", "fill", "browse", "click", "tap", "saree", "shopping", "autonomous", "control"),
                riskLevel = RiskLevel.HIGH,
                requiresAccessibility = true
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val goal = context.optString("goal", context.optString("request", context.optString("task", "")))
                val start = System.currentTimeMillis()
                val outcome = controller.executeOperatorGoal(goal)
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, outcome, JSONObject().apply { put("outcome", outcome) }, latency)
            }
        })

        // 12. Public APIs Directory & Catalog Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "public_apis_catalog_searcher",
                name = "Public APIs Catalog Searcher",
                description = "Searches, filters, and recommends from 1,756+ curated public APIs across 51 categories.",
                category = PluginCategory.EXTERNAL_API,
                tags = listOf("api", "public api", "catalog", "directory", "recommend api", "endpoints"),
                riskLevel = RiskLevel.LOW
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val query = context.optString("query", context.optString("task", ""))
                val cat = context.optString("category", "").ifBlank { null }
                val start = System.currentTimeMillis()
                val matches = publicApiRegistry.searchApis(query, cat, false, 10)
                val latency = System.currentTimeMillis() - start
                val arr = org.json.JSONArray()
                matches.forEach { arr.put(it.toJsonObject()) }
                return PluginResult.success(metadata.id, "Found ${matches.size} APIs for $query", JSONObject().apply { put("apis", arr) }, latency)
            }
        })

        // 13. Generic REST API Executor Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "public_rest_executor",
                name = "Public REST Endpoint Invoker",
                description = "Executes arbitrary HTTP GET or POST requests against public web APIs.",
                category = PluginCategory.EXTERNAL_API,
                tags = listOf("rest", "http", "curl", "fetch", "endpoint", "url", "post", "get"),
                riskLevel = RiskLevel.MEDIUM
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val url = context.optString("url")
                val method = context.optString("method", "GET")
                val body = context.optString("body", "").ifBlank { null }
                val start = System.currentTimeMillis()
                val res = publicApiExecutor.executeApi(url, method, body = body)
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, "REST call to $url executed", res, latency)
            }
        })

        // 14. App Launcher Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "app_launcher_service",
                name = "Android Application Launcher",
                description = "Launches any installed application on the user's phone by name.",
                category = PluginCategory.ANDROID_DEVICE,
                tags = listOf("open app", "launch", "kholo", "open", "application"),
                riskLevel = RiskLevel.MEDIUM,
                requiresInternet = false
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val appName = context.optString("app_name", context.optString("name", ""))
                val start = System.currentTimeMillis()
                val res = agentToolExecutor.execute("open_app", JSONObject().apply { put("app_name", appName) })
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, res.optString("message", "App launched"), res, latency)
            }
        })

        // 15. Web Search Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "google_search_service",
                name = "Google & Chrome Web Search",
                description = "Searches queries on Google or opens search results in Chrome.",
                category = PluginCategory.WEB,
                tags = listOf("search", "google", "chrome", "dhoondo", "web", "lookup"),
                riskLevel = RiskLevel.LOW
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val raw = context.optString("query", context.optString("q", ""))
                val clean = com.soltini.app.orchestrator.MyraCommandParser.cleanWebSearchQuery(raw).ifBlank { raw.trim() }
                val start = System.currentTimeMillis()
                val res = agentToolExecutor.execute("search_google", JSONObject().apply { put("query", clean) })
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, "Google par '$clean' search kar diya gaya hai", res, latency)
            }
        })

        // 16. Smart Home Controller Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "smart_home_controller",
                name = "Smart Home IoT Controller",
                description = "Controls lights, fans, plugs, and appliances via MQTT or local hub. Also supports geofence location triggers (action='set_location_trigger').",
                category = PluginCategory.AUTOMATION,
                tags = listOf("home", "light", "fan", "switch", "lamp", "iot", "smart home", "turn on", "turn off", "geofence", "location trigger"),
                riskLevel = RiskLevel.MEDIUM
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val action = context.optString("action", "TOGGLE")
                if (action.equals("set_location_trigger", ignoreCase = true) ||
                    action.equals("location_trigger", ignoreCase = true) ||
                    context.parameters.has("location_trigger") ||
                    context.parameters.has("geofence")
                ) {
                    return handleLocationTrigger(metadata.id, deviceController, context)
                }

                val deviceName = context.optString("device_name", context.optString("device", ""))
                val start = System.currentTimeMillis()
                val res = deviceController.executeControl(
                    callId = "plugin_${System.currentTimeMillis()}",
                    deviceName = deviceName,
                    actionRequested = action
                )
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, res.optString("message", "Smart home device controlled"), res, latency)
            }
        })

        // 16b. Location Trigger Automation Plugin (Geofencing)
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "set_location_trigger",
                name = "Location Trigger & Geofence Automation",
                description = "Sets up automated location-based triggers (geofences) using current GPS location to automatically run smart home or device actions when arriving at or leaving places (e.g. 'Ghar pahunchte hi Wi-Fi on karo', 'Office chhodne pe lights off karo').",
                category = PluginCategory.AUTOMATION,
                tags = listOf("location", "geofence", "trigger", "ghar", "office", "pahunchte", "chhodne", "aane", "nikalne", "wifi", "light", "automation"),
                riskLevel = RiskLevel.MEDIUM
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                return handleLocationTrigger(metadata.id, deviceController, context)
            }
        })

        // 17. Device Status & Battery Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "device_status_service",
                name = "Device Telemetry & Status",
                description = "Reports battery percentage, charging state, WiFi, cellular, and storage status.",
                category = PluginCategory.ANDROID_DEVICE,
                tags = listOf("battery", "charge", "storage", "status", "phone health", "specs"),
                riskLevel = RiskLevel.LOW,
                requiresInternet = false
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val start = System.currentTimeMillis()
                val res = deviceHardware.getDeviceStatus()
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, "Device status retrieved", res, latency)
            }
        })

        // 18. Alarm & Timer Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "clock_timer_service",
                name = "Alarm & Timer Scheduler",
                description = "Sets clocks, alarms, and timers on Android.",
                category = PluginCategory.ANDROID_DEVICE,
                tags = listOf("alarm", "timer", "remind", "wake up", "clock", "countdown"),
                riskLevel = RiskLevel.MEDIUM,
                requiresInternet = false
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val start = System.currentTimeMillis()
                val res = if (context.parameters.has("seconds")) {
                    deviceHardware.setTimer(
                        context.optInt("seconds", 60),
                        context.optString("label", "Timer")
                    )
                } else {
                    deviceHardware.setAlarm(
                        context.optInt("hour", 7),
                        context.optInt("minute", 0),
                        context.optString("message", "Alarm")
                    )
                }
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, res.optString("message", "Clock set"), res, latency)
            }
        })

        // 19. Clipboard Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "device_clipboard_service",
                name = "Clipboard Reader & Writer",
                description = "Reads copied text or copies new text to the device clipboard.",
                category = PluginCategory.ANDROID_DEVICE,
                tags = listOf("clipboard", "copy", "paste", "copied text"),
                riskLevel = RiskLevel.LOW,
                requiresInternet = false
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val start = System.currentTimeMillis()
                val res = if (context.parameters.has("text")) {
                    deviceHardware.copyToClipboard(context.optString("text"))
                } else {
                    deviceHardware.getClipboard()
                }
                val latency = System.currentTimeMillis() - start
                return PluginResult.success(metadata.id, res.optString("message", "Clipboard accessed"), res, latency)
            }
        })

        // 20. Phone Call Controller Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "phone_call_controller",
                name = "Hands-free Phone Call Controller",
                description = "Answers or rejects incoming phone calls hands-free, or queries the current incoming caller identity.",
                category = PluginCategory.ANDROID_DEVICE,
                tags = listOf("call", "answer call", "reject call", "phone call", "pick up", "cut call", "kaun call kar raha hai", "kiska call hai", "who is calling", "call status", "caller info"),
                riskLevel = RiskLevel.HIGH,
                requiresInternet = false
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val action = context.optString("action", "answer").lowercase()
                val start = System.currentTimeMillis()
                val res = when {
                    action.contains("query") || action.contains("info") || action.contains("caller") || action.contains("who") || action.contains("status") -> {
                        val info = callManager.getCurrentCallInfo()
                        val json = info.toJsonObject()
                        val msg = if (info.isIncoming) {
                            "Call aa raha hai ${info.callerName ?: "Unknown"} (${info.phoneNumber ?: "Unknown number"})."
                        } else {
                            "Koi active call nahi aa raha hai (Call State: ${info.callState})."
                        }
                        json.put("message", msg)
                        return PluginResult.success(metadata.id, msg, json, System.currentTimeMillis() - start)
                    }
                    action.contains("reject") || action.contains("cut") -> {
                        val detailed = callManager.rejectCallDetailed()
                        val json = JSONObject().apply {
                            put("success", detailed.success)
                            put("method", detailed.method)
                            put("message", detailed.message)
                        }
                        if (detailed.success) {
                            PluginResult.success(metadata.id, "Call cut kar diya: ${detailed.message}", json, System.currentTimeMillis() - start)
                        } else {
                            PluginResult.failure(metadata.id, "Call reject karne me samasya: ${detailed.message}", System.currentTimeMillis() - start)
                        }
                    }
                    else -> {
                        val detailed = callManager.answerCallDetailed()
                        val json = JSONObject().apply {
                            put("success", detailed.success)
                            put("method", detailed.method)
                            put("message", detailed.message)
                        }
                        if (detailed.success) {
                            PluginResult.success(metadata.id, "Call receive kar liya: ${detailed.message}", json, System.currentTimeMillis() - start)
                        } else {
                            PluginResult.failure(metadata.id, "Call receive karne me samasya: ${detailed.message}", System.currentTimeMillis() - start)
                        }
                    }
                }
                return res
            }
        })

        // 20b. YouTube Controller & Playback Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "youtube_controller",
                name = "YouTube Music & Video Player",
                description = "Searches or directly plays songs, videos, and playlists on YouTube.",
                category = PluginCategory.ANDROID_DEVICE,
                tags = listOf("youtube", "play video", "play song", "gaana chalao", "song chalao", "video chalao", "youtube search", "chalao", "bajao", "sunao"),
                riskLevel = RiskLevel.LOW,
                requiresInternet = true
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val start = System.currentTimeMillis()
                val rawQuery = context.optString("query", context.optString("song", context.optString("video", "")))
                val cleanQuery = com.soltini.app.orchestrator.MyraCommandParser.cleanYouTubeQuery(rawQuery).ifBlank { rawQuery.trim() }
                val action = context.optString("action", "play").lowercase()

                if (cleanQuery.isBlank()) {
                    return PluginResult.failure(metadata.id, "Song ya video ka naam batayein", System.currentTimeMillis() - start)
                }

                val res = if (action.contains("search")) {
                    youtubeAutomator.search(cleanQuery)
                } else {
                    youtubeAutomator.playSong(cleanQuery)
                }
                val latency = System.currentTimeMillis() - start
                val status = res.optString("status", "").lowercase()
                val isSuccess = status == "playing_video" || status == "playing" ||
                        status == "searching" || status == "searching_web_fallback" ||
                        status == "success"
                val defaultMsg = when (status) {
                    "playing_video", "playing" -> "YouTube par '$cleanQuery' play ho raha hai"
                    "searching", "searching_web_fallback" -> "YouTube par '$cleanQuery' search kar diya gaya hai"
                    else -> "YouTube par '$cleanQuery' ke liye request process hui"
                }
                return if (isSuccess) {
                    PluginResult.success(metadata.id, res.optString("message", defaultMsg), res, latency)
                } else {
                    PluginResult.failure(metadata.id, res.optString("message", "YouTube action failed for '$cleanQuery'"), latency)
                }
            }
        })

        // 20c. SMS & Text Messaging Plugin
        registry.registerPlugin(object : Plugin {
            override val metadata = PluginMetadata(
                id = "phone_sms_controller",
                name = "SMS & Text Message Dispatcher",
                description = "Sends SMS text messages to phone contacts or numbers after resolving contact details.",
                category = PluginCategory.ANDROID_DEVICE,
                tags = listOf("sms", "send sms", "message bhejo", "text message", "sms karo", "bhejo"),
                riskLevel = RiskLevel.HIGH,
                requiresInternet = false
            )
            override suspend fun execute(context: PluginContext): PluginResult {
                val start = System.currentTimeMillis()
                val recipientRaw = context.optString("recipient", context.optString("contact", context.optString("to", "")))
                val messageRaw = context.optString("message", context.optString("text", context.optString("body", "")))

                val recipient = com.soltini.app.orchestrator.MyraCommandParser.cleanRecipient(recipientRaw).ifBlank { recipientRaw.trim() }
                val messageText = com.soltini.app.orchestrator.MyraCommandParser.cleanMessageBody(messageRaw).ifBlank { messageRaw.trim() }

                if (recipient.isBlank()) {
                    return PluginResult.failure(metadata.id, "Kisko message bhejna hai, naam ya number batayein", System.currentTimeMillis() - start)
                }
                if (messageText.isBlank()) {
                    return PluginResult.failure(metadata.id, "Message me kya likhna hai batayein", System.currentTimeMillis() - start)
                }

                val matches = callManager.searchContacts(recipient)
                if (matches.isEmpty()) {
                    return PluginResult.failure(metadata.id, "Contacts me '$recipient' naam ka koi contact nahi mila. Kripya phone number batayein.", System.currentTimeMillis() - start)
                }

                // If multiple contacts found and none is an exact case-insensitive match
                val exactMatch = matches.find { it.name.equals(recipient, ignoreCase = true) }
                if (exactMatch == null && matches.size > 1) {
                    val listStr = matches.take(3).joinToString(", ") { "${it.name} (${it.phoneNumber})" }
                    val json = JSONObject().apply {
                        put("clarification_needed", true)
                        put("matches_count", matches.size)
                    }
                    return PluginResult.failure(metadata.id, "'$recipient' naam ke ${matches.size} contacts mile: $listStr. Kisko message bhejna hai?", System.currentTimeMillis() - start)
                }

                val target = exactMatch ?: matches.first()
                val sendResult = smsSender.sendSms(target.phoneNumber, messageText)
                val latency = System.currentTimeMillis() - start
                val json = JSONObject().apply {
                    put("success", sendResult.success)
                    put("recipient_name", target.name)
                    put("phone_number", target.phoneNumber)
                    put("method", sendResult.method)
                    put("message", sendResult.message)
                }

                return if (sendResult.success) {
                    PluginResult.success(metadata.id, "${target.name} ko message bhej diya gaya: '$messageText'", json, latency)
                } else {
                    PluginResult.failure(metadata.id, "Message nahi bheja ja saka: ${sendResult.message}", latency)
                }
            }
        })

        // 21. Storage Intelligence, File Manager & RAG Knowledge Plugin
        val storageManager = com.soltini.app.storage.SafStorageManager.getInstance(context)
        val fileSafetyManager = com.soltini.app.storage.FileSafetyManager(storageManager)
        val ragEngine = com.soltini.app.rag.RagKnowledgeEngine.getInstance(context, appSettings)
        val storagePlugin = StorageManagerPlugin(context, storageManager, fileSafetyManager, ragEngine, appSettings)
        registry.registerPlugin(storagePlugin)

        Log.i(TAG, "Built-in capabilities registered: ${registry.getAllPlugins().size} plugins ready.")
    }

    private suspend fun fetchCurrentCoordinates(context: Context): Pair<Double, Double> {
        return suspendCancellableCoroutine { continuation ->
            try {
                val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val coarseGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                if (!fineGranted && !coarseGranted) {
                    continuation.resume(Pair(28.6139, 77.2090))
                    return@suspendCancellableCoroutine
                }

                val fusedClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
                fusedClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        continuation.resume(Pair(loc.latitude, loc.longitude))
                    } else {
                        val tokenSource = com.google.android.gms.tasks.CancellationTokenSource()
                        fusedClient.getCurrentLocation(
                            com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                            tokenSource.token
                        ).addOnSuccessListener { curLoc ->
                            if (curLoc != null) {
                                continuation.resume(Pair(curLoc.latitude, curLoc.longitude))
                            } else {
                                continuation.resume(Pair(28.6139, 77.2090))
                            }
                        }.addOnFailureListener {
                            continuation.resume(Pair(28.6139, 77.2090))
                        }
                    }
                }.addOnFailureListener {
                    continuation.resume(Pair(28.6139, 77.2090))
                }
            } catch (e: Exception) {
                continuation.resume(Pair(28.6139, 77.2090))
            }
        }
    }

    private suspend fun handleLocationTrigger(
        pluginId: String,
        deviceController: DeviceController,
        context: PluginContext
    ): PluginResult {
        val start = System.currentTimeMillis()
        val rawActionDesc = context.optString(
            "action_description",
            context.optString(
                "action",
                context.optString("trigger_action", "Wi-Fi on kar dena")
            )
        )
        val label = context.optString(
            "label",
            context.optString(
                "place",
                if (rawActionDesc.contains("office", ignoreCase = true)) "Office" else "Ghar"
            )
        )

        val latParam = context.parameters.optDouble("latitude", Double.NaN)
        val lngParam = context.parameters.optDouble("longitude", Double.NaN)

        val (lat, lng) = if (!latParam.isNaN() && !lngParam.isNaN()) {
            Pair(latParam, lngParam)
        } else {
            fetchCurrentCoordinates(context.context)
        }

        val isEnter = !rawActionDesc.contains("leave", ignoreCase = true) &&
                !rawActionDesc.contains("chhod", ignoreCase = true) &&
                !rawActionDesc.contains("nikal", ignoreCase = true)

        val targetDevice = context.parameters.optString("device_name", context.parameters.optString("device", "")).ifBlank { null }
        val targetAction = context.parameters.optString("target_action", "").ifBlank { null }

        deviceController.setupLocationTrigger(
            context = context.context,
            label = label,
            latitude = lat,
            longitude = lng,
            actionDescription = rawActionDesc,
            targetDevice = targetDevice,
            targetAction = targetAction,
            isEnterTrigger = isEnter
        )

        val latency = System.currentTimeMillis() - start
        val transitionWord = if (isEnter) "pahunchne par" else "chhodne par"
        val summary = "Location trigger set: '$label' $transitionWord '$rawActionDesc' automatically execute hoga. (Coordinates: %.4f, %.4f)".format(lat, lng)

        val resJson = JSONObject().apply {
            put("status", "SUCCESS")
            put("label", label)
            put("latitude", lat)
            put("longitude", lng)
            put("action_description", rawActionDesc)
            put("is_enter_trigger", isEnter)
        }
        return PluginResult.success(pluginId, summary, resJson, latency)
    }
}
