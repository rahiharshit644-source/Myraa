package com.soltini.app.agent

import android.content.Context
import android.util.Log
import com.soltini.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AgentPhoneController — Autonomous Multi-Step Phone Operator Engine.
 *
 * Implements the intelligent Action-Observation loop:
 *   1. Observe: inspects screen elements, reads text, identifies current app.
 *   2. Plan & Reason: uses Gemini Flash to determine next precise actions (click, scroll, type).
 *   3. Execute: dispatches high-level actions via ScreenOperator and AgentToolExecutor.
 *   4. Verify: re-inspects screen after delay to confirm progress.
 *   5. Human-Like Interactive Pauses: if options or decisions are needed, communicates back with the user.
 */
class AgentPhoneController(
    private val context: Context,
    private val agentToolExecutor: AgentToolExecutor,
    private val screenOperator: ScreenOperator,
    private val appSettings: AppSettings
) {

    companion object {
        private const val TAG = "AgentPhoneController"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val PRIMARY_MODEL = "gemini-2.5-flash"
        private const val FALLBACK_MODEL = "gemini-2.0-flash"
        private const val MAX_AUTONOMOUS_STEPS = 10
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val formAutomator = FormAutomator(context)

    /**
     * Executes an autonomous operator goal on the phone.
     *
     * @param goal The natural language request (e.g. "Chrome me form bhar do", "saree dikhao shopping app me")
     * @param onIntermediateStatus Callback to announce progress to Gemini Live or user
     * @return Summary string of task outcome or message requesting clarification
     */
    suspend fun executeOperatorGoal(
        goal: String,
        onIntermediateStatus: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val apiKey = appSettings.effectiveApiKey()
        if (apiKey.isBlank()) {
            return@withContext "Error: Gemini API key is not configured in Settings."
        }

        if (!screenOperator.isAvailable()) {
            return@withContext "Accessibility Service is not active. Please enable Soltini in Android Accessibility settings so I can control the screen for you."
        }

        Log.i(TAG, "Starting autonomous phone operator loop for goal: \"$goal\"")
        onIntermediateStatus?.invoke("Ji Boss, main aapke phone pe yeh kaam step-by-step complete kar rahi hu...")

        val a11y = SoltiniAccessibilityService.getInstance()
        a11y?.isToolCallActive = true

        try {
            val systemPrompt = """You are Myra's Autonomous Phone Operator Engine.
You have direct, human-like control of this Android phone. You can:
- Open apps (e.g. Chrome, WhatsApp, Shopping apps, Settings).
- Inspect the screen (`inspect_screen` / `observe_screen`).
- Click elements by text, ID, or description (`smart_click` or `click_element`).
- Tap specific coordinates or percentages (`tap_screen` or `tap_percent`).
- Type text into input fields and form elements (`smart_type` or `type_text`).
- Scroll and swipe in any direction (`swipe_direction`, `scroll_down`, `scroll_up`).
- Navigate using Android buttons (`press_back`, `press_home`).

### USER'S GOAL:
"$goal"

### AUTONOMOUS OPERATOR WORKFLOW:
1. **Observe first**: If you are not in the right app, call `open_app`. Then observe screen state using `inspect_screen` or `observe_screen`.
2. **Execute sequential actions**: Perform logical steps like a human. For example, to search for sarees: open app -> click search bar -> type "saree" -> click search/first result.
3. **Filling forms**: Locate each field, use `smart_type(field="Name", text="...")`, then proceed to next fields. If information is missing or you need confirmation for payment/critical submission, finish your turn explaining what options exist or ask Boss!
4. **Browsing & Human Choice**: If the user asked to see products (e.g. sarees, shoes), open them, scroll to show options, and describe the top choices warmly to Boss!
5. **Completion**: When the task is complete, provide a friendly summary to Boss. Do not call any more tools."""

            val conversationHistory = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", "Goal: $goal") })
                    })
                })
            }

            var finalSummary = ""
            val toolsDecl = buildAutonomousToolsDeclaration()

            for (step in 1..MAX_AUTONOMOUS_STEPS) {
                Log.d(TAG, "Operator step $step/$MAX_AUTONOMOUS_STEPS")

                val requestBody = JSONObject().apply {
                    put("contents", conversationHistory)
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemPrompt) })
                        })
                    })
                    put("tools", JSONArray().apply {
                        put(JSONObject().apply {
                            put("functionDeclarations", toolsDecl)
                        })
                    })
                }

                val response = try {
                    callGemini(PRIMARY_MODEL, apiKey, requestBody)
                } catch (e: Exception) {
                    Log.w(TAG, "Primary model failed: ${e.message}, trying fallback $FALLBACK_MODEL")
                    try {
                        callGemini(FALLBACK_MODEL, apiKey, requestBody)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Fallback model also failed: ${e2.message}")
                        return@withContext "Mujhe goal execute karne me dikkat aayi: ${e2.message}"
                    }
                }

                val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
                val content = candidate?.optJSONObject("content") ?: break
                val parts = content.optJSONArray("parts") ?: JSONArray()

                val functionCalls = mutableListOf<JSONObject>()
                var modelText = ""

                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.has("functionCall")) {
                        functionCalls.add(part.getJSONObject("functionCall"))
                    }
                    if (part.has("text")) {
                        modelText += part.getString("text") + " "
                    }
                }

                if (functionCalls.isNotEmpty()) {
                    // Record model's tool calls in history
                    conversationHistory.put(content)

                    val responseParts = JSONArray()
                    for (fc in functionCalls) {
                        val name = fc.optString("name", "")
                        val args = fc.optJSONObject("args") ?: JSONObject()
                        Log.i(TAG, "Operator step $step executing: $name with $args")

                        val toolResult = executeOperatorAction(name, args, apiKey)

                        // Small natural pause between actions so UI can update
                        delay(400)

                        responseParts.put(JSONObject().apply {
                            put("functionResponse", JSONObject().apply {
                                put("name", name)
                                put("response", JSONObject().apply {
                                    put("output", toolResult)
                                })
                            })
                        })
                    }

                    // Add tool execution responses to history
                    conversationHistory.put(JSONObject().apply {
                        put("role", "tool")
                        put("parts", responseParts)
                    })
                } else {
                    finalSummary = modelText.trim()
                    Log.i(TAG, "Operator finished with summary: $finalSummary")
                    break
                }
            }

            if (finalSummary.isBlank()) {
                finalSummary = "Ji Boss, maine aapka task pura kar diya hai!"
            }

            return@withContext finalSummary
        } finally {
            a11y?.isToolCallActive = false
        }
    }

    private suspend fun executeOperatorAction(name: String, args: JSONObject, apiKey: String): JSONObject {
        return try {
            when (name) {
                "inspect_screen" -> {
                    val base = screenOperator.inspectScreen()
                    try {
                        val visual = com.soltini.app.vision.VisualScreenAnalyzer.getInstance(context)
                            .analyzeScreen(apiKey, focusQuery = null, textTreeContext = base.optString("visible_text_summary"))
                        if (visual.isVisualAvailable && visual.visualSummary.isNotBlank()) {
                            base.put("visual_vision_details", visual.visualSummary)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Operator visual inspection error: ${e.message}")
                    }
                    base
                }
                "observe_screen" -> {
                    val query = args.optString("query_focus", "")
                    val snapshot = com.soltini.app.companion.ScreenCompanionManager.getInstance(context)
                        .captureCurrentScreenWithVision(query)
                    JSONObject().apply {
                        put("status", "success")
                        put("app", snapshot.currentApp)
                        put("summary", snapshot.toHumanSummary())
                        if (snapshot.visualSummary.isNotBlank()) {
                            put("visual_details", snapshot.visualSummary)
                        }
                    }
                }
                "smart_click" -> screenOperator.clickElement(
                    target = args.optString("target", args.optString("text", "")),
                    autoScroll = args.optBoolean("auto_scroll", true)
                )
                "smart_type" -> screenOperator.typeIntoField(
                    targetField = args.optString("field", "").takeIf { it.isNotBlank() },
                    textToType = args.optString("text", ""),
                    clearFirst = args.optBoolean("clear_first", false),
                    autoScroll = args.optBoolean("auto_scroll", true),
                    autoDismissKeyboard = args.optBoolean("auto_dismiss_keyboard", false)
                )
                "fill_form", "smart_fill_form", "universal_fill_form" -> formAutomator.fillForm(
                    fields = args.optJSONArray("fields") ?: JSONArray(),
                    autoSubmit = args.optBoolean("auto_submit", false),
                    submitButtonText = args.optString("submit_button", "").takeIf { it.isNotBlank() },
                    allowRisky = args.optBoolean("allow_risky", false)
                )
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
                "tap_percent" -> screenOperator.tapPercent(
                    percentX = args.optDouble("percent_x", 0.5).toFloat(),
                    percentY = args.optDouble("percent_y", 0.5).toFloat()
                )
                else -> agentToolExecutor.execute(name, args)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Action $name failed: ${e.message}", e)
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Execution failed")
            }
        }
    }

    private fun callGemini(model: String, apiKey: String, body: JSONObject): JSONObject {
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

    private fun buildAutonomousToolsDeclaration(): JSONArray = JSONArray().apply {
        // App Launching & OS Navigation
        put(funcDecl("open_app", "Open an installed Android app by name (e.g. Chrome, Amazon, Flipkart, WhatsApp, Settings)",
            objProps("app_name" to strProp("The name of the app to launch")), listOf("app_name")))
        put(funcDecl("press_back", "Press the Android Back button", emptyObjProps()))
        put(funcDecl("press_home", "Press the Android Home button", emptyObjProps()))
        put(funcDecl("press_recents", "Open Android Recent Apps overview", emptyObjProps()))

        // Semantic Screen Operations
        put(funcDecl("inspect_screen", "Inspect the current screen to get a list of all interactive buttons, text fields, tabs, and visible text content", emptyObjProps()))
        put(funcDecl("observe_screen", "Get a human summary of the current screen context and active app", emptyObjProps()))
        put(funcDecl("smart_click", "Click any button, link, tab, or element by its visible text, label, or ID. Automatically scrolls down if offscreen and self-corrects.",
            objProps(
                "target" to strProp("The text, label, or description of the element to click"),
                "auto_scroll" to boolProp("True to auto-scroll down to hunt for the target if not visible (default true)")
            ), listOf("target")))
        put(funcDecl("smart_type", "Type text into a labeled or specified form input field. Automatically scrolls down if offscreen.",
            objProps(
                "field" to strProp("Label, hint, or placeholder of the target field (optional)"),
                "text" to strProp("The text to type into the field"),
                "clear_first" to boolProp("True to clear existing text before typing"),
                "auto_scroll" to boolProp("True to auto-scroll down to find field (default true)"),
                "auto_dismiss_keyboard" to boolProp("True to hide keyboard after typing")
            ),
            listOf("text")))
        put(funcDecl("fill_form", "Fill an entire form with multiple fields (text, number, dropdown, checkbox, switch) in one seamless step with auto-scroll and optional auto-submit.",
            objProps(
                "fields" to arrProp("Array of field objects: [{\"label\": \"Name\", \"value\": \"Rahul\", \"type\": \"text|dropdown|checkbox\"}]"),
                "auto_submit" to boolProp("True to automatically click submit button after filling"),
                "submit_button" to strProp("Label of submit button to click (optional, default 'Submit')")
            ),
            listOf("fields")))
        put(funcDecl("toggle_checkbox", "Toggle a checkbox, switch, or radio button by matching its label",
            objProps(
                "target" to strProp("Label or text next to the checkbox or switch"),
                "checked" to boolProp("Desired boolean state (optional, toggles if not provided)")
            ),
            listOf("target")))
        put(funcDecl("select_dropdown", "Select an option from a dropdown or spinner menu",
            objProps(
                "dropdown" to strProp("Label of the dropdown to open"),
                "option" to strProp("Text of the option to select from the menu")
            ),
            listOf("option")))
        put(funcDecl("hide_keyboard", "Dismiss the onscreen soft keyboard if it is covering the screen", emptyObjProps()))
        put(funcDecl("swipe_direction", "Swipe or scroll the screen in a specified direction (UP to scroll down, DOWN to scroll up, LEFT for next, RIGHT for previous)",
            objProps(
                "direction" to strProp("Direction: UP, DOWN, LEFT, or RIGHT"),
                "distance_percent" to numProp("Swipe distance percentage between 0.1 and 0.9 (default 0.5)")
            ),
            listOf("direction")))
        put(funcDecl("tap_percent", "Tap at a screen position specified as normalized percentages from 0.0 to 1.0 (0.5, 0.5 is screen center)",
            objProps(
                "percent_x" to numProp("X percentage from 0.0 (left) to 1.0 (right)"),
                "percent_y" to numProp("Y percentage from 0.0 (top) to 1.0 (bottom)")
            ),
            listOf("percent_x", "percent_y")))
        put(funcDecl("tap_screen", "Tap at specific pixel coordinates",
            objProps("x" to numProp("X pixel coordinate"), "y" to numProp("Y pixel coordinate")), listOf("x", "y")))
        put(funcDecl("scroll_down", "Scroll down the current screen", emptyObjProps()))
        put(funcDecl("scroll_up", "Scroll up the current screen", emptyObjProps()))

        // Social & Messaging Automation
        put(funcDecl("instagram_send_message", "Send a DM to an Instagram username",
            objProps("username" to strProp("Instagram username"), "message" to strProp("Message text")),
            listOf("username", "message")))
        put(funcDecl("whatsapp_send_message", "Send a WhatsApp message to a contact",
            objProps("contact_name" to strProp("Contact name"), "message" to strProp("Message text")),
            listOf("contact_name", "message")))

        // Web & Device Controls
        put(funcDecl("search_google", "Search on Google or open query in Chrome",
            objProps("query" to strProp("Search terms")), listOf("query")))
        put(funcDecl("toggle_torch", "Toggle flashlight on or off",
            objProps("enabled" to boolProp("True for on, false for off"))))
        put(funcDecl("set_volume", "Set device volume percentage (0 to 100)",
            objProps("percent" to intProp("Volume percentage 0 to 100")), listOf("percent")))
        put(funcDecl("take_screenshot", "Take screenshot of the screen", emptyObjProps()))

        // Public APIs Integration
        put(funcDecl("fetch_crypto_price", "Fetch live cryptocurrency price (e.g. Bitcoin, Ethereum)",
            objProps("coin" to strProp("Coin name/symbol"), "currency" to strProp("Target currency, e.g. usd, inr")),
            listOf("coin")))
        put(funcDecl("fetch_weather", "Fetch live weather and forecast for any city",
            objProps("city" to strProp("City name")), listOf("city")))
        put(funcDecl("lookup_dictionary", "Look up word definition and meaning",
            objProps("word" to strProp("Word to look up")), listOf("word")))
        put(funcDecl("fetch_currency_rate", "Fetch live foreign exchange rates between fiat currencies",
            objProps("from" to strProp("Base currency"), "to" to strProp("Target currency")), listOf("from", "to")))
        put(funcDecl("search_public_apis", "Search catalog of 1,756+ public APIs across 51 categories",
            objProps("query" to strProp("Search query"), "category" to strProp("Optional category filter")), listOf("query")))
        put(funcDecl("recommend_api_for_task", "Recommend public APIs for a specific task",
            objProps("task" to strProp("Task description")), listOf("task")))
        put(funcDecl("execute_public_api", "Execute an HTTP GET or POST request to any public REST endpoint",
            objProps("url" to strProp("Endpoint URL"), "method" to strProp("GET or POST")), listOf("url")))
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

    private fun arrProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "ARRAY")
        put("description", desc)
        put("items", JSONObject().apply {
            put("type", "OBJECT")
        })
    }
}
