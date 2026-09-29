package com.soltini.app.companion

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.soltini.app.agent.SoltiniAccessibilityService
import com.soltini.app.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * ScreenCompanionManager
 *
 * Real-time Human Screen Companion Engine:
 * Acts like a real human friend sitting right next to the user watching their screen.
 *
 * Capabilities:
 *  1. On-Demand Observation: Gemini or user says "Dekh screen pe kya chal raha hai",
 *     "Check my code", "Yeh email kaisa hai", "What is on my screen?", Soltini reads
 *     the full screen context and gives real-time suggestions, humor, help, or advice.
 *
 *  2. Proactive Live Copilot Mode:
 *     When enabled (via voice "Companion mode on karo" or UI toggle), it passively observes
 *     screen text and typing changes. When the user pauses after typing or stays on a screen
 *     with important context (e.g. drafting a message, reading a post, shopping, facing an error),
 *     it automatically generates contextual advice or tips and shares them naturally.
 *
 *  3. Privacy & Banking Guard:
 *     Instantly suppresses and clears screen data when banking, UPI, or password fields are detected.
 */
class ScreenCompanionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ScreenCompanion"

        @Volatile
        private var instance: ScreenCompanionManager? = null

        fun getInstance(context: Context): ScreenCompanionManager {
            return instance ?: synchronized(this) {
                instance ?: ScreenCompanionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appSettings by lazy { AppSettings(context) }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _isLiveCompanionEnabled = MutableStateFlow(appSettings.isLiveScreenCompanionEnabled)
    val isLiveCompanionEnabled: StateFlow<Boolean> = _isLiveCompanionEnabled.asStateFlow()

    private val _lastObservedApp = MutableStateFlow("")
    val lastObservedApp: StateFlow<String> = _lastObservedApp.asStateFlow()

    private var proactiveDebounceJob: Job? = null
    private var lastScreenHash: Int = 0
    private var lastObservedTimestamp: Long = 0L

    // Callback when proactive thought/insight is ready to send to Gemini Live
    var onProactiveInsightReady: ((String) -> Unit)? = null

    /**
     * Toggles live proactive screen companion mode.
     */
    fun setLiveCompanionEnabled(enabled: Boolean) {
        _isLiveCompanionEnabled.value = enabled
        appSettings.isLiveScreenCompanionEnabled = enabled
        Log.i(TAG, "Live Screen Companion mode set to: $enabled")
    }

    /**
     * Captures a comprehensive human-friendly description of what is currently on the screen.
     */
    fun captureCurrentScreenContext(): ScreenSnapshot {
        val a11y = SoltiniAccessibilityService.getInstance()
        if (a11y == null) {
            return ScreenSnapshot(
                isAvailable = false,
                errorMessage = "Accessibility Service is not enabled. Please enable it in Settings so I can see your screen."
            )
        }

        if (a11y.isPausedLite) {
            return ScreenSnapshot(
                isAvailable = false,
                errorMessage = "Screen context is paused for privacy."
            )
        }

        try {
            val textBlocks = mutableListOf<String>()
            val editableTexts = mutableListOf<String>()
            val clickableActions = mutableListOf<String>()
            var currentPackage = ""
            var isSecure = false

            a11y.withRootNodes { roots ->
                for (root in roots) {
                    val pkg = root.packageName?.toString() ?: ""
                    if (pkg.isNotBlank() && currentPackage.isBlank()) {
                        currentPackage = pkg
                    }

                    // Privacy check on package name
                    if (appSettings.isBankingProtectionEnabled && appSettings.isBankingApp(pkg)) {
                        isSecure = true
                        return@withRootNodes
                    }

                    extractNodeContent(root, textBlocks, editableTexts, clickableActions)
                }
            }

            if (isSecure) {
                return ScreenSnapshot(
                    isAvailable = false,
                    isSecure = true,
                    errorMessage = "Screen viewing is safely paused because a banking or payment screen is open."
                )
            }

            val appFriendlyName = getAppLabel(currentPackage)
            _lastObservedApp.value = appFriendlyName

            return ScreenSnapshot(
                isAvailable = true,
                currentApp = appFriendlyName,
                packageName = currentPackage,
                visibleTexts = textBlocks.distinct(),
                userTypingTexts = editableTexts.distinct(),
                interactiveOptions = clickableActions.take(15)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screen context: ${e.message}", e)
            return ScreenSnapshot(
                isAvailable = false,
                errorMessage = "Could not read screen: ${e.message}"
            )
        }
    }

    private fun extractNodeContent(
        node: AccessibilityNodeInfo,
        textBlocks: MutableList<String>,
        editableTexts: MutableList<String>,
        clickableActions: MutableList<String>
    ) {
        // Password fields are strictly ignored
        if (node.isPassword) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val content = text ?: desc

        if (!content.isNullOrBlank()) {
            if (node.isEditable) {
                editableTexts.add(content)
            } else if (node.isClickable) {
                clickableActions.add(content)
            } else {
                textBlocks.add(content)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractNodeContent(child, textBlocks, editableTexts, clickableActions)
            child.recycle()
        }
    }

    /**
     * Called by SoltiniAccessibilityService when a window state changes or text is typed.
     * Evaluates whether to proactively provide a companion suggestion.
     */
    fun onScreenEventDetected(event: AccessibilityEvent) {
        if (!_isLiveCompanionEnabled.value) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            return
        }

        val pkg = event.packageName?.toString() ?: ""
        if (appSettings.isBankingProtectionEnabled && appSettings.isBankingApp(pkg)) {
            proactiveDebounceJob?.cancel()
            return
        }

        // Debounce: Wait 3.5 seconds of silence/user pause before analyzing so we don't disturb typing
        proactiveDebounceJob?.cancel()
        proactiveDebounceJob = scope.launch {
            delay(3500)
            checkAndTriggerProactiveInsight()
        }
    }

    private fun checkAndTriggerProactiveInsight() {
        val now = System.currentTimeMillis()
        // Ensure at least 15 seconds between unsolicited proactive suggestions
        if (now - lastObservedTimestamp < 15_000) return

        val snapshot = captureCurrentScreenContext()
        if (!snapshot.isAvailable || snapshot.isSecure) return

        val currentHash = snapshot.calculateContentHash()
        if (currentHash == lastScreenHash) {
            // Screen content hasn't changed significantly, skip
            return
        }

        val summary = snapshot.toHumanSummary()
        if (summary.isBlank() || summary.length < 15) return

        lastScreenHash = currentHash
        lastObservedTimestamp = now

        Log.i(TAG, "Triggering proactive companion insight for: ${snapshot.currentApp}")
        val prompt = "[PROACTIVE HUMAN COMPANION EYE: You notice the user is on ${snapshot.currentApp}. Here is what they are currently looking at or typing:\n$summary\nAct like a real human friend observing their screen. If there is a natural, helpful, funny, or smart suggestion (e.g. improving what they're typing, pointing out an interesting detail, spotting a good deal, fixing an error), speak up in 1-2 sweet, warm, natural conversational sentences in Hinglish. If nothing requires comment, stay silent.]"
        
        onProactiveInsightReady?.invoke(prompt)
    }

    private fun getAppLabel(packageName: String): String {
        if (packageName.isBlank()) return "Current App"
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    data class ScreenSnapshot(
        val isAvailable: Boolean,
        val isSecure: Boolean = false,
        val currentApp: String = "",
        val packageName: String = "",
        val visibleTexts: List<String> = emptyList(),
        val userTypingTexts: List<String> = emptyList(),
        val interactiveOptions: List<String> = emptyList(),
        val visualSummary: String = "",
        val base64Jpeg: String? = null,
        val errorMessage: String? = null
    ) {
        fun calculateContentHash(): Int {
            return (visibleTexts.take(8).joinToString("") + userTypingTexts.joinToString("")).hashCode()
        }

        fun toHumanSummary(): String {
            val sb = StringBuilder()
            if (currentApp.isNotBlank()) sb.appendLine("Active App: $currentApp")
            if (userTypingTexts.isNotEmpty()) {
                sb.appendLine("User is typing/editing: \"${userTypingTexts.joinToString(" | ")}\"")
            }
            if (visibleTexts.isNotEmpty()) {
                sb.appendLine("Visible Content on Screen: ${visibleTexts.take(12).joinToString(" • ")}")
            }
            if (interactiveOptions.isNotEmpty()) {
                sb.appendLine("Visible Buttons/Actions: [${interactiveOptions.take(6).joinToString(", ")}]")
            }
            if (visualSummary.isNotBlank()) {
                sb.appendLine("Visual Appearance & Photos (Gemini Vision): $visualSummary")
            }
            return sb.toString().trim()
        }
    }

    /**
     * Captures the screen with dual-modal Hybrid Vision:
     * Combines accessibility text hierarchy with actual visual screen screenshot analysis from Gemini Vision.
     */
    suspend fun captureCurrentScreenWithVision(focusQuery: String? = null): ScreenSnapshot {
        val textSnapshot = captureCurrentScreenContext()
        if (!textSnapshot.isAvailable || textSnapshot.isSecure) {
            return textSnapshot
        }

        return try {
            val visualAnalyzer = com.soltini.app.vision.VisualScreenAnalyzer.getInstance(context)
            val apiKey = appSettings.effectiveApiKey()
            if (apiKey.isBlank()) {
                return textSnapshot
            }

            val visionResult = visualAnalyzer.analyzeScreen(
                apiKey = apiKey,
                focusQuery = focusQuery,
                textTreeContext = textSnapshot.toHumanSummary()
            )

            if (visionResult.isVisualAvailable) {
                textSnapshot.copy(
                    visualSummary = visionResult.visualSummary,
                    base64Jpeg = visionResult.base64Jpeg
                )
            } else {
                textSnapshot
            }
        } catch (e: Exception) {
            Log.w(TAG, "Visual inspection failed, falling back to text snapshot: ${e.message}")
            textSnapshot
        }
    }
}
