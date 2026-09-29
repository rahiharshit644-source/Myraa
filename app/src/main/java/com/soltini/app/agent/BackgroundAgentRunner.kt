package com.soltini.app.agent

import android.content.Context
import android.util.Log
import com.soltini.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * BackgroundAgentRunner — Tier 2 Silent Background Automation Agent
 *
 * Receives delegated automation tasks from the Tier 1 Live Voice Agent and executes
 * them autonomously using Android accessibility, app control, screen automation,
 * YouTube, WhatsApp, and Reels automations.
 *
 * Runs up to 6 agentic tool steps using gemini-3-flash-preview (with automatic fallback).
 */
class BackgroundAgentRunner(
    private val context: Context,
    private val agentToolExecutor: AgentToolExecutor,
    private val appSettings: AppSettings
) {

    companion object {
        private const val TAG = "BackgroundAgentRunner"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val PRIMARY_MODEL = "gemini-3-flash-preview"
        private const val FALLBACK_MODEL = "gemini-2.5-flash"
        private const val LITE_MODEL = "gemini-3.1-flash-lite"
        private const val MAX_STEPS = 10
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Executes the requested automation command autonomously in the background.
     * Returns a concise human-readable summary of what was accomplished.
     */
    suspend fun execute(requestText: String): String = withContext(Dispatchers.IO) {
        val apiKey = appSettings.effectiveApiKey()
        if (apiKey.isBlank()) {
            Log.e(TAG, "Cannot execute background task — API key is missing")
            return@withContext "Error: API key is not configured in Settings."
        }

        Log.i(TAG, "Starting background agent for task: \"$requestText\"")

        val systemPrompt = """You are Myra's Autonomous Android Automation Agent.
You are the intelligent execution engine running directly on this Android device.
Your job is to execute the user's requested phone automation task cleanly, autonomously, and swiftly using your tools.

### USER REQUEST:
"$requestText"

## EXECUTION RULES:
1. **Direct Execution**: Output tool calls immediately. If asked to open an app (e.g. Chrome, Shopping app, YouTube), call `open_app` in your very first turn.
2. **Autonomous Multi-Step Tasks**:
   - For Browsing & Form Filling: Use `inspect_screen` or `read_screen` to see interactive elements.
   - Use `smart_click` or `click_element` to click buttons, tabs, links, or search bars.
   - Use `smart_type` to fill form fields by their label/placeholder (e.g. name, address, search query).
   - Use `swipe_direction` ("UP", "DOWN", "LEFT", "RIGHT") or `scroll_down`/`scroll_up` to navigate long pages.
   - For WhatsApp: call `whatsapp_send_message` or `open_app("WhatsApp")` -> `smart_type` -> `smart_click`.
   - For YouTube: call `youtube_search`, `youtube_open_video`, `youtube_play_pause`, etc.
3. **Completion**: When the entire task is finished, return a brief, friendly summary for Boss of what was done. Do NOT call any more tools once finished."""

        val contents = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", "Execute task: $requestText") })
                })
            })
        }

        var currentModel = PRIMARY_MODEL
        val toolsDeclaration = buildToolsDeclaration()
        var finalResult = ""

        for (step in 1..MAX_STEPS) {
            Log.d(TAG, "Execution step $step/$MAX_STEPS using model $currentModel")

            val requestBody = JSONObject().apply {
                put("contents", contents)
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemPrompt) })
                    })
                })
                put("tools", JSONArray().apply {
                    put(JSONObject().apply {
                        put("functionDeclarations", toolsDeclaration)
                    })
                })
            }

            var responseJson: JSONObject? = null
            val modelsToTry = if (currentModel == PRIMARY_MODEL) {
                listOf(PRIMARY_MODEL, FALLBACK_MODEL, LITE_MODEL)
            } else {
                listOf(currentModel, FALLBACK_MODEL, LITE_MODEL).distinct()
            }

            for (m in modelsToTry) {
                try {
                    responseJson = callGeminiGenerateContent(m, apiKey, requestBody)
                    currentModel = m
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Model $m failed at step $step: ${e.message}")
                }
            }

            if (responseJson == null) {
                Log.e(TAG, "Null response from Gemini API at step $step")
                break
            }

            val candidates = responseJson.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val candidateContent = candidate?.optJSONObject("content")
            val parts = candidateContent?.optJSONArray("parts")

            if (parts == null || parts.length() == 0) {
                Log.w(TAG, "No parts returned by model at step $step")
                break
            }

            // Check if there are function calls
            val functionCalls = mutableListOf<JSONObject>()
            var textContent = ""

            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.has("functionCall")) {
                    functionCalls.add(part.getJSONObject("functionCall"))
                }
                if (part.has("text")) {
                    textContent += part.getString("text") + " "
                }
            }

            if (functionCalls.isNotEmpty()) {
                // Append model turn with tool calls to conversation history
                contents.put(candidateContent)

                // Execute each tool and build tool response turn
                val responseParts = JSONArray()
                for (fc in functionCalls) {
                    val name = fc.optString("name", "")
                    val args = fc.optJSONObject("args") ?: JSONObject()
                    Log.i(TAG, "Step $step: Tool call -> $name with args $args")

                    val execResult = try {
                        agentToolExecutor.execute(name, args)
                    } catch (e: Exception) {
                        Log.e(TAG, "Tool execution exception for $name: ${e.message}", e)
                        JSONObject().apply { put("error", e.message ?: "Execution failed") }
                    }

                    responseParts.put(JSONObject().apply {
                        put("functionResponse", JSONObject().apply {
                            put("name", name)
                            put("response", JSONObject().apply {
                                put("output", execResult)
                            })
                        })
                    })
                }

                // Append tool results to conversation history
                contents.put(JSONObject().apply {
                    put("role", "tool")
                    put("parts", responseParts)
                })

            } else {
                // Model has completed and provided final text output
                finalResult = textContent.trim()
                Log.i(TAG, "Background agent completed in $step step(s): $finalResult")
                break
            }
        }

        if (finalResult.isBlank()) {
            finalResult = "Done! Successfully completed the task: $requestText"
        }

        return@withContext finalResult
    }

    private fun callGeminiGenerateContent(model: String, apiKey: String, body: JSONObject): JSONObject {
        val url = "$BASE_URL/$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw RuntimeException("Gemini HTTP ${response.code}: $responseBody")
            }
            return JSONObject(responseBody)
        }
    }

    private fun buildToolsDeclaration(): JSONArray = JSONArray().apply {
        // Device Control
        put(funcDecl("open_app", "Launch any installed app by name",
            objProps("app_name" to strProp("The name of the app to launch (e.g. WhatsApp, YouTube, Chrome, Settings)")),
            listOf("app_name")
        ))
        put(funcDecl("lock_device", "Lock the device screen immediately", emptyObjProps()))
        put(funcDecl("wake_device", "Turn the screen on", emptyObjProps()))
        put(funcDecl("press_back", "Simulate the Android Back button", emptyObjProps()))
        put(funcDecl("press_home", "Simulate the Android Home button", emptyObjProps()))
        put(funcDecl("press_recents", "Open Android Recent Apps overview", emptyObjProps()))

        // Screen Automation & Semantic Operator
        put(funcDecl("inspect_screen", "Inspect the current screen to list interactive buttons, input fields, checkboxes, tabs, and visible text", emptyObjProps()))
        put(funcDecl("read_screen", "Reads all visible UI text and buttons currently displayed on the phone screen", emptyObjProps()))
        put(funcDecl("smart_click", "Click any UI button, tab, link, or item by visible text or label",
            objProps("target" to strProp("Label or text of the item to click")),
            listOf("target")
        ))
        put(funcDecl("smart_type", "Type text into a form input field specified by its label or hint",
            objProps(
                "text" to strProp("Text to type"),
                "field" to strProp("Label or hint of target field (optional)"),
                "clear_first" to boolProp("True to clear existing text before typing")
            ),
            listOf("text")
        ))
        put(funcDecl("swipe_direction", "Swipe or scroll the screen in a direction (UP, DOWN, LEFT, RIGHT)",
            objProps(
                "direction" to strProp("Direction: UP, DOWN, LEFT, or RIGHT"),
                "distance_percent" to numProp("Percentage of screen distance (0.1 to 0.9, default 0.5)")
            ),
            listOf("direction")
        ))
        put(funcDecl("tap_percent", "Tap at normalized percentage screen coordinates (0.0 to 1.0)",
            objProps("percent_x" to numProp("X percent (0.0 to 1.0)"), "percent_y" to numProp("Y percent (0.0 to 1.0)")),
            listOf("percent_x", "percent_y")
        ))
        put(funcDecl("click_element", "Click any UI element or button matching the given text",
            objProps("text" to strProp("Exact or partial text of the button or item to click")),
            listOf("text")
        ))
        put(funcDecl("tap_screen", "Tap at specific pixel coordinates on the screen",
            objProps("x" to numProp("X pixel coordinate"), "y" to numProp("Y pixel coordinate")),
            listOf("x", "y")
        ))
        put(funcDecl("swipe_screen", "Perform a swipe gesture across the screen",
            objProps(
                "x1" to numProp("Start X"), "y1" to numProp("Start Y"),
                "x2" to numProp("End X"), "y2" to numProp("End Y"),
                "duration_ms" to intProp("Swipe duration in milliseconds")
            ),
            listOf("x1", "y1", "x2", "y2")
        ))
        put(funcDecl("type_text", "Type text into the currently focused text field",
            objProps("text" to strProp("Text to type")),
            listOf("text")
        ))
        put(funcDecl("clear_text", "Clear the focused text input field", emptyObjProps()))
        put(funcDecl("scroll_down", "Scroll down the current screen", emptyObjProps()))
        put(funcDecl("scroll_up", "Scroll up the current screen", emptyObjProps()))

        // YouTube Automation
        put(funcDecl("youtube_search", "Search for videos on YouTube",
            objProps("query" to strProp("Search terms")),
            listOf("query")
        ))
        put(funcDecl("youtube_open_video", "Open and play a YouTube video",
            objProps("video_id" to strProp("Title or video ID to open")),
            listOf("video_id")
        ))
        put(funcDecl("youtube_play_pause", "Toggle Play/Pause on current YouTube video", emptyObjProps()))
        put(funcDecl("youtube_seek", "Seek forward or backward by seconds",
            objProps("seconds" to intProp("Seconds to seek (+ for forward, - for backward)")),
            listOf("seconds")
        ))
        put(funcDecl("youtube_next", "Play next video in YouTube", emptyObjProps()))
        put(funcDecl("youtube_previous", "Play previous video in YouTube", emptyObjProps()))
        put(funcDecl("youtube_like", "Like the current YouTube video", emptyObjProps()))
        put(funcDecl("youtube_dislike", "Dislike the current YouTube video", emptyObjProps()))
        put(funcDecl("youtube_subscribe", "Subscribe to the current YouTube channel", emptyObjProps()))
        put(funcDecl("youtube_skip_ad", "Skip YouTube ad if available", emptyObjProps()))
        put(funcDecl("youtube_set_quality", "Set video quality (e.g. 1080p, 720p, 480p)",
            objProps("quality" to strProp("Quality string")),
            listOf("quality")
        ))
        put(funcDecl("youtube_fullscreen", "Toggle fullscreen mode in YouTube", emptyObjProps()))
        put(funcDecl("youtube_captions", "Toggle captions in YouTube", emptyObjProps()))
        put(funcDecl("youtube_mute", "Toggle mute in YouTube", emptyObjProps()))
        put(funcDecl("read_youtube_screen", "Read YouTube UI state and visible video titles", emptyObjProps()))

        // WhatsApp Automation
        put(funcDecl("whatsapp_send_message", "Open WhatsApp, search contact, type and send message",
            objProps(
                "contact_name" to strProp("Name of the contact"),
                "message" to strProp("Message to send")
            ),
            listOf("contact_name", "message")
        ))
        put(funcDecl("whatsapp_call", "Place a voice or video call on WhatsApp",
            objProps(
                "contact_name" to strProp("Name of the contact"),
                "is_video" to boolProp("True for video call, False for voice call")
            ),
            listOf("contact_name")
        ))

        // Reels Automation
        put(funcDecl("next_reel", "Swipe to next Short or Reel", emptyObjProps()))
        put(funcDecl("previous_reel", "Swipe to previous Short or Reel", emptyObjProps()))
    }

    private fun funcDecl(name: String, description: String, parameters: JSONObject, required: List<String> = emptyList()): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", parameters.apply {
                if (required.isNotEmpty()) {
                    put("required", JSONArray(required))
                }
            })
        }

    private fun emptyObjProps(): JSONObject = JSONObject().apply {
        put("type", "OBJECT")
        put("properties", JSONObject())
    }

    private fun objProps(vararg props: Pair<String, JSONObject>): JSONObject = JSONObject().apply {
        put("type", "OBJECT")
        put("properties", JSONObject().apply {
            for ((key, value) in props) {
                put(key, value)
            }
        })
    }

    private fun strProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "STRING")
        put("description", desc)
    }

    private fun numProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "NUMBER")
        put("description", desc)
    }

    private fun intProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "INTEGER")
        put("description", desc)
    }

    private fun boolProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "BOOLEAN")
        put("description", desc)
    }
}
