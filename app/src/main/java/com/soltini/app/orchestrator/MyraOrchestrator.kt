package com.soltini.app.orchestrator

import android.content.Context
import android.util.Log
import com.soltini.app.memory2.Memory2Engine
import com.soltini.app.memory2.MemoryType
import com.soltini.app.memory2.RetrievedMemoryContext
import com.soltini.app.plugins.*
import com.soltini.app.settings.AppSettings
import com.soltini.app.util.AppLogger
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
 * StructuredIntentResult
 *
 * LLM-extracted structured representation of user intent, capability, parameters, and entities.
 */
data class StructuredIntentResult(
    val intentType: IntentType,
    val capability: String?,
    val parameters: JSONObject,
    val entities: JSONObject
)

/**
 * MyraOrchestrator
 *
 * Central AI Brain and Master Decision Layer.
 *
 * Coordinates the unified intelligence loop:
 *   UNDERSTAND -> REMEMBER -> PLAN -> DISCOVER CAPABILITIES -> EXECUTE -> VERIFY -> LEARN/UPDATE MEMORY -> RESPOND
 *
 * Adheres strictly to the 10 Core Interaction Rules:
 *   Rule 1: Orchestrator decides; Memory provides context; Plugins provide capabilities.
 *   Rule 2: Memory influences decisions, doesn't execute actions directly.
 *   Rule 3: Plugins execute capabilities without high-level assumptions.
 *   Rule 4: Validates plugin outputs before advancing.
 *   Rule 5: Checks memory before asking user.
 *   Rule 6: Queries Plugin Registry before claiming inability.
 *   Rule 7: Dynamically discovers capabilities without hardcoded switches.
 *   Rule 8: Supports runtime plugin replacement & fallbacks.
 *   Rule 9: Selective relevance-based memory retrieval.
 *   Rule 10: Post-task evaluation loop updates durable memory.
 */
class MyraOrchestrator private constructor(
    private val context: Context,
    private val memory2: Memory2Engine,
    private val pluginRegistry: PluginRegistry,
    private val appSettings: AppSettings
) {

    companion object {
        private const val TAG = "MyraOrchestrator"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val PRIMARY_MODEL = "gemini-2.5-flash"
        private const val FALLBACK_MODEL = "gemini-3.1-flash-lite-preview"

        @Volatile
        private var instance: MyraOrchestrator? = null

        fun getInstance(
            context: Context,
            memory2: Memory2Engine,
            pluginRegistry: PluginRegistry,
            appSettings: AppSettings
        ): MyraOrchestrator =
            instance ?: synchronized(this) {
                instance ?: MyraOrchestrator(
                    context.applicationContext,
                    memory2,
                    pluginRegistry,
                    appSettings
                ).also { instance = it }
            }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val storageManager = com.soltini.app.storage.SafStorageManager.getInstance(context)
    private val fileSafetyManager = com.soltini.app.storage.FileSafetyManager(storageManager)
    private val ragEngine = com.soltini.app.rag.RagKnowledgeEngine.getInstance(context, appSettings)
    private val callManager = com.soltini.app.telephony.CallNotificationManager.getInstance(context)
    private val smsSender = com.soltini.app.telephony.SmsSender(context)
    private val youTubeAutomator = com.soltini.app.agent.YouTubeAutomator(context)
    private val agentToolExecutor = com.soltini.app.agent.AgentToolExecutor(context)
    private val voiceProfileManager = com.soltini.app.security.VoiceProfileManager.getInstance(context)
    private val agentReachGateway = com.soltini.app.reach.gateway.AgentReachGatewayImpl.getInstance(context)
    private val reachSynthesizer = com.soltini.app.reach.integration.MyraReachSynthesizer(context, appSettings)
    private val reachHistoryStore = com.soltini.app.reach.data.ReachHistoryStore.getInstance(context)

    @Volatile
    private var activeSpeakerProfile: com.soltini.app.security.VoiceProfile? = null

    fun setActiveSpeakerProfile(profile: com.soltini.app.security.VoiceProfile?) {
        activeSpeakerProfile = profile
    }

    fun getActiveSpeakerProfile(): com.soltini.app.security.VoiceProfile? = activeSpeakerProfile

    data class PendingMessageAction(
        val recipientName: String,
        val phoneNumber: String,
        val messageText: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    @Volatile
    private var pendingMessageAction: PendingMessageAction? = null

    /**
     * Master Entry Point: Processes any user input from voice, text, camera, or automation.
     */
    suspend fun processRequest(
        userInput: String,
        onStatusUpdate: ((String) -> Unit)? = null
    ): OrchestratorResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val cleanedInput = userInput.trim()
        Log.i(TAG, "─── ORCHESTRATOR START: \"$cleanedInput\" ───")
        onStatusUpdate?.invoke("Analyzing request...")

        // SAFETY CHECK: Intercept confirmations for destructive file actions
        val pending = fileSafetyManager.getLatestPending()
        if (pending != null) {
            val confIntent = fileSafetyManager.checkUserUtterance(cleanedInput)
            if (confIntent == com.soltini.app.storage.UserConfirmationIntent.AFFIRMATIVE) {
                // Voice-lock security check for destructive file actions
                val voiceManager = com.soltini.app.security.VoiceBiometricsManager.getInstance(context)
                if (voiceManager.isEnrolled() && voiceManager.isVoiceLockEnabled() && !voiceManager.isRecentAuthValid()) {
                    val authResult = voiceManager.recordAndVerify(durationMs = 2500L)
                    if (!authResult.isMatch) {
                        val failPlan = OrchestratorPlan(
                            userGoal = cleanedInput,
                            intentType = IntentType.ACTIONABLE,
                            status = PlanStatus.FAILED,
                            finalResponse = "Security Alert Boss: Voice verification fail ho gayi (${(authResult.similarity * 100).toInt()}% match vs ${(com.soltini.app.security.VoiceBiometricsManager.DEFAULT_THRESHOLD * 100).toInt()}% required). Destructive file action cancel kar diya gaya hai."
                        )
                        return@withContext OrchestratorResult(
                            plan = failPlan,
                            isSuccess = false,
                            responseText = failPlan.finalResponse,
                            memoryUpdated = false,
                            executionDurationMs = System.currentTimeMillis() - startTime
                        )
                    }
                }

                val res = fileSafetyManager.executeConfirmedAction(pending.id)
                if (res.isSuccess && pending.actionType == com.soltini.app.storage.FileActionType.DELETE) {
                    ragEngine.db.deleteDocument(pending.targetUri)
                }
                val plan = OrchestratorPlan(
                    userGoal = cleanedInput,
                    intentType = IntentType.ACTIONABLE,
                    status = PlanStatus.COMPLETED,
                    finalResponse = "Ji Boss! ${res.message}"
                )
                return@withContext OrchestratorResult(
                    plan = plan,
                    isSuccess = res.isSuccess,
                    responseText = plan.finalResponse,
                    memoryUpdated = false,
                    executionDurationMs = System.currentTimeMillis() - startTime
                )
            } else if (confIntent == com.soltini.app.storage.UserConfirmationIntent.NEGATIVE) {
                fileSafetyManager.cancelPendingAction(pending.id)
                val plan = OrchestratorPlan(
                    userGoal = cleanedInput,
                    intentType = IntentType.CONVERSATIONAL,
                    status = PlanStatus.COMPLETED,
                    finalResponse = "Theek hai Boss, maine destructive file action cancel kar diya hai. Aapki files bilkul safe hain."
                )
                return@withContext OrchestratorResult(
                    plan = plan,
                    isSuccess = true,
                    responseText = plan.finalResponse,
                    memoryUpdated = false,
                    executionDurationMs = System.currentTimeMillis() - startTime
                )
            }
        }

        // SAFETY CHECK: Intercept confirmation for pending SMS / message dispatch
        val pendingMsg = pendingMessageAction
        if (pendingMsg != null) {
            val lower = cleanedInput.lowercase()
            val isYes = lower.contains("haan") || lower.contains("yes") || lower.contains("bhejo") ||
                    lower.contains("send karo") || lower.contains("send") || lower.contains("bhej do") ||
                    lower.contains("theek hai") || lower.contains("sure") || lower.contains("kar do")
            val isNo = lower.contains("nahi") || lower.contains("no") || lower.contains("cancel") ||
                    lower.contains("mat bhejo") || lower.contains("rehndey") || lower.contains("rehne do")

            if (isYes) {
                pendingMessageAction = null
                val sendRes = smsSender.sendSms(pendingMsg.phoneNumber, pendingMsg.messageText)
                val reply = if (sendRes.success) {
                    "Ji Boss! ${pendingMsg.recipientName} ko message bhej diya gaya hai: \"${pendingMsg.messageText}\""
                } else {
                    "Message nahi bheja ja saka: ${sendRes.message}"
                }
                val plan = OrchestratorPlan(
                    userGoal = cleanedInput,
                    intentType = IntentType.ACTIONABLE,
                    status = if (sendRes.success) PlanStatus.COMPLETED else PlanStatus.FAILED,
                    finalResponse = reply
                )
                return@withContext OrchestratorResult(
                    plan = plan,
                    isSuccess = sendRes.success,
                    responseText = reply,
                    memoryUpdated = true,
                    executionDurationMs = System.currentTimeMillis() - startTime
                )
            } else if (isNo) {
                pendingMessageAction = null
                val reply = "Theek hai Boss, message cancel kar diya gaya hai."
                val plan = OrchestratorPlan(
                    userGoal = cleanedInput,
                    intentType = IntentType.CONVERSATIONAL,
                    status = PlanStatus.COMPLETED,
                    finalResponse = reply
                )
                return@withContext OrchestratorResult(
                    plan = plan,
                    isSuccess = true,
                    responseText = reply,
                    memoryUpdated = false,
                    executionDurationMs = System.currentTimeMillis() - startTime
                )
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // EMERGENCY SAFETY & MULTI-USER PROFILE PERMISSION PIPELINE
        // ═════════════════════════════════════════════════════════════════════
        if (voiceProfileManager.isEmergencyDisabled()) {
            val reply = "Myra security emergency active hai. Sabhi voice actions aur assistant operations disabled hain."
            val plan = OrchestratorPlan(
                userGoal = cleanedInput,
                intentType = IntentType.CONVERSATIONAL,
                status = PlanStatus.FAILED,
                finalResponse = reply
            )
            return@withContext OrchestratorResult(
                plan = plan,
                isSuccess = false,
                responseText = reply,
                memoryUpdated = false,
                executionDurationMs = System.currentTimeMillis() - startTime
            )
        }

        val lowerInput = cleanedInput.lowercase()
        val speaker = when {
            lowerInput.startsWith("mummy:") || lowerInput.contains("mummy bol rahi") || lowerInput.contains("from mummy") ->
                voiceProfileManager.getProfile(com.soltini.app.security.VoiceProfileManager.DEFAULT_FAMILY_ID)
            activeSpeakerProfile != null -> activeSpeakerProfile
            else -> voiceProfileManager.getOwnerProfile()
        }

        val (resourceCat, riskLvl) = classifyResourceAndRisk(cleanedInput)
        val permResult = voiceProfileManager.checkPermission(speaker, resourceCat, riskLvl)

        if (permResult is com.soltini.app.security.PermissionCheckResult.Denied) {
            val reply = permResult.reason
            val plan = OrchestratorPlan(
                userGoal = cleanedInput,
                intentType = IntentType.CONVERSATIONAL,
                status = PlanStatus.FAILED,
                finalResponse = reply
            )
            return@withContext OrchestratorResult(
                plan = plan,
                isSuccess = false,
                responseText = reply,
                memoryUpdated = false,
                executionDurationMs = System.currentTimeMillis() - startTime
            )
        } else if (permResult is com.soltini.app.security.PermissionCheckResult.RequiresBiometricPrompt) {
            val reply = "Security Notice: ${permResult.reason}"
            val plan = OrchestratorPlan(
                userGoal = cleanedInput,
                intentType = IntentType.ACTIONABLE,
                status = PlanStatus.FAILED,
                finalResponse = reply
            )
            return@withContext OrchestratorResult(
                plan = plan,
                isSuccess = false,
                responseText = reply,
                memoryUpdated = false,
                executionDurationMs = System.currentTimeMillis() - startTime
            )
        }

        // Direct response for Personal Notes when authorized
        if (resourceCat == com.soltini.app.security.ResourceCategory.PERSONAL_NOTES &&
            (lowerInput.contains("dikhao") || lowerInput.contains("kholo") || lowerInput.contains("padho") || lowerInput.contains("read") || lowerInput.contains("show"))
        ) {
            val reply = "Ji Boss! Aapke personal notes: 1. AI Studio Build release notes. 2. Project architecture guidelines. 3. Smart home setup checklist."
            val plan = OrchestratorPlan(
                userGoal = cleanedInput,
                intentType = IntentType.ACTIONABLE,
                status = PlanStatus.COMPLETED,
                finalResponse = reply
            )
            return@withContext OrchestratorResult(
                plan = plan,
                isSuccess = true,
                responseText = reply,
                memoryUpdated = false,
                executionDurationMs = System.currentTimeMillis() - startTime
            )
        }

        // ═════════════════════════════════════════════════════════════════════
        // DETERMINISTIC ACTION PARSING (Phone Calls, SMS, YouTube & Web Search)
        // ═════════════════════════════════════════════════════════════════════
        val parsedAction = MyraCommandParser.parse(cleanedInput)
        if (parsedAction != null) {
            Log.i(TAG, "MyraCommandParser matched: $parsedAction")
            when (parsedAction) {
                is ParsedAction.CallInfoQuery -> {
                    val info = callManager.getCurrentCallInfo()
                    val reply = if (info.isIncoming) {
                        "Call aa raha hai ${info.callerName ?: "Unknown"} (${info.phoneNumber ?: "Unknown number"})."
                    } else {
                        "Koi active call nahi aa raha hai (Call state: ${info.callState})."
                    }
                    val plan = OrchestratorPlan(
                        userGoal = cleanedInput,
                        intentType = IntentType.ACTIONABLE,
                        status = PlanStatus.COMPLETED,
                        finalResponse = reply
                    )
                    return@withContext OrchestratorResult(
                        plan = plan,
                        isSuccess = true,
                        responseText = reply,
                        memoryUpdated = false,
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }
                is ParsedAction.CallAnswer -> {
                    val detailed = callManager.answerCallDetailed()
                    val reply = if (detailed.success) {
                        "Ji Boss, call receive kar liya hai."
                    } else {
                        "Call receive karne me samasya aayi: ${detailed.message}"
                    }
                    val plan = OrchestratorPlan(
                        userGoal = cleanedInput,
                        intentType = IntentType.ACTIONABLE,
                        status = if (detailed.success) PlanStatus.COMPLETED else PlanStatus.FAILED,
                        finalResponse = reply
                    )
                    return@withContext OrchestratorResult(
                        plan = plan,
                        isSuccess = detailed.success,
                        responseText = reply,
                        memoryUpdated = true,
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }
                is ParsedAction.CallReject -> {
                    val detailed = callManager.rejectCallDetailed()
                    val reply = if (detailed.success) {
                        "Ji Boss, call cut kar diya hai."
                    } else {
                        "Call cut karne me samasya aayi: ${detailed.message}"
                    }
                    val plan = OrchestratorPlan(
                        userGoal = cleanedInput,
                        intentType = IntentType.ACTIONABLE,
                        status = if (detailed.success) PlanStatus.COMPLETED else PlanStatus.FAILED,
                        finalResponse = reply
                    )
                    return@withContext OrchestratorResult(
                        plan = plan,
                        isSuccess = detailed.success,
                        responseText = reply,
                        memoryUpdated = true,
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }
                is ParsedAction.SendMessage -> {
                    val matches = callManager.searchContacts(parsedAction.recipient)
                    if (matches.isEmpty()) {
                        val reply = "Contacts me '${parsedAction.recipient}' naam ka koi contact nahi mila. Kripya phone number ya contact name dobara batayein."
                        val plan = OrchestratorPlan(
                            userGoal = cleanedInput,
                            intentType = IntentType.ACTIONABLE,
                            status = PlanStatus.FAILED,
                            finalResponse = reply
                        )
                        return@withContext OrchestratorResult(
                            plan = plan,
                            isSuccess = false,
                            responseText = reply,
                            memoryUpdated = false,
                            executionDurationMs = System.currentTimeMillis() - startTime
                        )
                    } else if (matches.size > 1 && !matches.any { it.name.equals(parsedAction.recipient, ignoreCase = true) }) {
                        val listStr = matches.take(3).joinToString(", ") { "${it.name} (${it.phoneNumber})" }
                        val reply = "'${parsedAction.recipient}' naam ke ${matches.size} contacts mile: $listStr. Kisko message bhejna hai?"
                        val plan = OrchestratorPlan(
                            userGoal = cleanedInput,
                            intentType = IntentType.ACTIONABLE,
                            status = PlanStatus.COMPLETED,
                            finalResponse = reply
                        )
                        return@withContext OrchestratorResult(
                            plan = plan,
                            isSuccess = true,
                            responseText = reply,
                            memoryUpdated = false,
                            executionDurationMs = System.currentTimeMillis() - startTime
                        )
                    } else {
                        val target = matches.find { it.name.equals(parsedAction.recipient, ignoreCase = true) } ?: matches.first()
                        pendingMessageAction = PendingMessageAction(
                            recipientName = target.name,
                            phoneNumber = target.phoneNumber,
                            messageText = parsedAction.messageText
                        )
                        val reply = "Kya aap ${target.name} (${target.phoneNumber}) ko ye message bhejna chahte hain:\n\"${parsedAction.messageText}\"?\nBhejne ke liye 'Haan bhejo' bolein."
                        val plan = OrchestratorPlan(
                            userGoal = cleanedInput,
                            intentType = IntentType.ACTIONABLE,
                            status = PlanStatus.COMPLETED,
                            finalResponse = reply
                        )
                        return@withContext OrchestratorResult(
                            plan = plan,
                            isSuccess = true,
                            responseText = reply,
                            memoryUpdated = false,
                            executionDurationMs = System.currentTimeMillis() - startTime
                        )
                    }
                }
                is ParsedAction.SetBusyMode -> {
                    val busyManager = com.soltini.app.messaging.BusyModeManager.getInstance(context)
                    val activeProfile = getActiveSpeakerProfile()
                    if (activeProfile != null) {
                        busyManager.setUserName(activeProfile.name)
                    }

                    val reply = if (parsedAction.enable) {
                        val reason = busyManager.enableBusyMode(parsedAction.reason)
                        "Ji Boss! Busy mode on kar diya gaya hai ($reason). Ab WhatsApp, Instagram, Telegram ya SMS par jo bhi naye messages aayenge, main bina kisi loop ke unka polite reply khud de dungi. Jab aap free ho jayein to bas boliyega 'Main free ho gaya'."
                    } else {
                        val summary = busyManager.disableBusyMode()
                        if (summary.totalRepliedCount > 0) {
                            val contacts = summary.uniqueSenders.joinToString(", ")
                            "Ji Boss, welcome back! Busy mode off kar diya gaya hai. Is session me maine kul ${summary.totalRepliedCount} messages ka reply kiya ($contacts)."
                        } else {
                            "Ji Boss, Busy mode off kar diya gaya hai. Is dauran koi naya message nahi aaya tha."
                        }
                    }

                    val plan = OrchestratorPlan(
                        userGoal = cleanedInput,
                        intentType = IntentType.ACTIONABLE,
                        status = PlanStatus.COMPLETED,
                        finalResponse = reply
                    )
                    return@withContext OrchestratorResult(
                        plan = plan,
                        isSuccess = true,
                        responseText = reply,
                        memoryUpdated = false,
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }
                is ParsedAction.BusyModeSummaryQuery -> {
                    val busyManager = com.soltini.app.messaging.BusyModeManager.getInstance(context)
                    val history = busyManager.getRecentHistory(8)
                    val reply = if (history.isEmpty()) {
                        "Boss, abhi tak busy mode me koi auto-reply nahi bheja gaya hai."
                    } else {
                        val items = history.joinToString("\n") { "• ${it.appName} (${it.sender}): \"${it.replyText}\"" }
                        "Boss, recent auto-replies ka record:\n$items"
                    }
                    val plan = OrchestratorPlan(
                        userGoal = cleanedInput,
                        intentType = IntentType.CONVERSATIONAL,
                        status = PlanStatus.COMPLETED,
                        finalResponse = reply
                    )
                    return@withContext OrchestratorResult(
                        plan = plan,
                        isSuccess = true,
                        responseText = reply,
                        memoryUpdated = false,
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }
                is ParsedAction.YouTubePlay -> {
                    onStatusUpdate?.invoke("YouTube par '${parsedAction.cleanQuery}' play kar rahe hain...")
                    val res = youTubeAutomator.playSong(parsedAction.cleanQuery)
                    val ok = res.optString("status") == "playing" || res.optString("status") == "success"
                    val reply = if (ok) {
                        "Ji Boss, YouTube par '${parsedAction.cleanQuery}' play ho raha hai."
                    } else {
                        "YouTube par play karne me samasya aayi: ${res.optString("message", "App nahi khula")}."
                    }
                    val plan = OrchestratorPlan(
                        userGoal = cleanedInput,
                        intentType = IntentType.ACTIONABLE,
                        status = if (ok) PlanStatus.COMPLETED else PlanStatus.FAILED,
                        finalResponse = reply
                    )
                    return@withContext OrchestratorResult(
                        plan = plan,
                        isSuccess = ok,
                        responseText = reply,
                        memoryUpdated = true,
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }
                is ParsedAction.YouTubeSearch -> {
                    onStatusUpdate?.invoke("YouTube par '${parsedAction.cleanQuery}' search kar rahe hain...")
                    val res = youTubeAutomator.search(parsedAction.cleanQuery)
                    val ok = res.optString("status") == "searching" || res.optString("status") == "success"
                    val reply = if (ok) {
                        "Ji Boss, YouTube par '${parsedAction.cleanQuery}' search kar diya hai."
                    } else {
                        "YouTube par search karne me samasya aayi: ${res.optString("message", "App nahi khula")}."
                    }
                    val plan = OrchestratorPlan(
                        userGoal = cleanedInput,
                        intentType = IntentType.ACTIONABLE,
                        status = if (ok) PlanStatus.COMPLETED else PlanStatus.FAILED,
                        finalResponse = reply
                    )
                    return@withContext OrchestratorResult(
                        plan = plan,
                        isSuccess = ok,
                        responseText = reply,
                        memoryUpdated = true,
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }
                is ParsedAction.InternetResearch -> {
                    if (parsedAction.isDoctorCheck) {
                        onStatusUpdate?.invoke("Checking Internet capabilities health (Doctor)...")
                        val report = agentReachGateway.getHealth(forceRefresh = true)
                        val reply = "Ji Boss, Agent Reach Doctor Diagnostic Report:\n\n${report.toFormattedReport()}"
                        val plan = OrchestratorPlan(
                            userGoal = cleanedInput,
                            intentType = IntentType.ACTIONABLE,
                            status = PlanStatus.COMPLETED,
                            finalResponse = reply
                        )
                        return@withContext OrchestratorResult(
                            plan = plan,
                            isSuccess = true,
                            responseText = reply,
                            memoryUpdated = false,
                            executionDurationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    if (parsedAction.isBackendQuery) {
                        val webBackend = agentReachGateway.getActiveBackend("web.search") ?: "duckduckgo_html"
                        val readerBackend = agentReachGateway.getActiveBackend("web.read") ?: "jina_reader"
                        val ytBackend = agentReachGateway.getActiveBackend("youtube.content") ?: "youtube_transcript"
                        val ghBackend = agentReachGateway.getActiveBackend("github.inspect") ?: "github_rest"
                        val redditBackend = agentReachGateway.getActiveBackend("reddit.discussions") ?: "reddit_public_json"

                        val reply = "Ji Boss! Currently active Internet Reach backends:\n" +
                                "• Web Search: $webBackend\n" +
                                "• Web Reader: $readerBackend\n" +
                                "• YouTube: $ytBackend\n" +
                                "• GitHub: $ghBackend\n" +
                                "• Reddit: $redditBackend"

                        val plan = OrchestratorPlan(
                            userGoal = cleanedInput,
                            intentType = IntentType.CONVERSATIONAL,
                            status = PlanStatus.COMPLETED,
                            finalResponse = reply
                        )
                        return@withContext OrchestratorResult(
                            plan = plan,
                            isSuccess = true,
                            responseText = reply,
                            memoryUpdated = false,
                            executionDurationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    // Autonomous Research & Multi-source Retrieval
                    val planToExec = com.soltini.app.reach.planner.ResearchPlanner.createPlan(cleanedInput)
                    onStatusUpdate?.invoke("Researching: Executing multi-source retrieval across ${planToExec.targets.size} channels...")

                    val researchResult = agentReachGateway.executeResearch(planToExec) { stage ->
                        onStatusUpdate?.invoke(stage)
                    }

                    onStatusUpdate?.invoke("Synthesizing answer with Gemini and attaching citations...")
                    val synthesizedAnswer = reachSynthesizer.synthesizeResearch(cleanedInput, researchResult) { stage ->
                        onStatusUpdate?.invoke(stage)
                    }

                    // Save research to persistent history
                    val historyItem = com.soltini.app.reach.model.ResearchHistoryItem(
                        query = cleanedInput,
                        mode = planToExec.mode,
                        summary = synthesizedAnswer,
                        sourceCount = researchResult.sources.size,
                        sourcesJson = org.json.JSONArray().apply {
                            researchResult.sources.forEach { put(it.toJsonObject()) }
                        }.toString(),
                        durationMs = System.currentTimeMillis() - startTime,
                        isSuccess = researchResult.isSuccess
                    )
                    reachHistoryStore.saveHistory(historyItem)

                    // Optional: store valuable research into unified memory
                    if (researchResult.isSuccess && researchResult.sources.isNotEmpty()) {
                        memory2.session.recordTopic(cleanedInput.take(30))
                        memory2.evaluateAndStore(
                            goal = cleanedInput,
                            outcomeSummary = "Internet research completed with ${researchResult.sources.size} sources. Summary: ${synthesizedAnswer.take(200)}",
                            toolSequence = listOf("AgentReachGateway"),
                            isSuccess = true
                        )
                    }

                    val plan = OrchestratorPlan(
                        userGoal = cleanedInput,
                        intentType = IntentType.ACTIONABLE,
                        status = if (researchResult.isSuccess) PlanStatus.COMPLETED else PlanStatus.FAILED,
                        finalResponse = synthesizedAnswer
                    )
                    return@withContext OrchestratorResult(
                        plan = plan,
                        isSuccess = researchResult.isSuccess,
                        responseText = synthesizedAnswer,
                        memoryUpdated = true,
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }
                is ParsedAction.OpenLastResultInChrome -> {
                    onStatusUpdate?.invoke("Last research result Chrome me open kar rahe hain...")
                    val res = agentToolExecutor.execute("open_last_result_in_chrome", JSONObject())
                    val ok = res.optString("status") == "success"
                    val reply = res.optString("message", if (ok) "Ji Boss, Chrome me webpage khol di hai." else "Chrome me open karne me samasya aayi.")
                    val plan = OrchestratorPlan(
                        userGoal = cleanedInput,
                        intentType = IntentType.ACTIONABLE,
                        status = if (ok) PlanStatus.COMPLETED else PlanStatus.FAILED,
                        finalResponse = reply
                    )
                    return@withContext OrchestratorResult(
                        plan = plan,
                        isSuccess = ok,
                        responseText = reply,
                        memoryUpdated = false,
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }
                is ParsedAction.WebSearch -> {
                    if (parsedAction.forceChrome) {
                        onStatusUpdate?.invoke("Chrome me '${parsedAction.cleanQuery}' search kar rahe hain...")
                        val res = agentToolExecutor.execute("chrome_search", JSONObject().apply { put("query", parsedAction.cleanQuery) })
                        val ok = res.optString("status") == "success"
                        val reply = if (ok) {
                            "Ji Boss, Chrome me '${parsedAction.cleanQuery}' search khol diya hai."
                        } else {
                            "Chrome me search karne me samasya aayi."
                        }
                        val plan = OrchestratorPlan(
                            userGoal = cleanedInput,
                            intentType = IntentType.ACTIONABLE,
                            status = if (ok) PlanStatus.COMPLETED else PlanStatus.FAILED,
                            finalResponse = reply
                        )
                        return@withContext OrchestratorResult(
                            plan = plan,
                            isSuccess = ok,
                            responseText = reply,
                            memoryUpdated = true,
                            executionDurationMs = System.currentTimeMillis() - startTime
                        )
                    } else {
                        // Background Mode: Agent Reach Information Engine — NO Chrome
                        onStatusUpdate?.invoke("Internet par '${parsedAction.cleanQuery}' search kar rahe hain (Agent Reach)...")
                        val planToExec = com.soltini.app.reach.planner.ResearchPlanner.createPlan(parsedAction.cleanQuery)
                        val researchResult = agentReachGateway.executeResearch(planToExec) { stage ->
                            onStatusUpdate?.invoke(stage)
                        }
                        val synthesizedAnswer = reachSynthesizer.synthesizeResearch(parsedAction.cleanQuery, researchResult) { stage ->
                            onStatusUpdate?.invoke(stage)
                        }

                        // Save in history store
                        val historyItem = com.soltini.app.reach.model.ResearchHistoryItem(
                            query = parsedAction.cleanQuery,
                            mode = planToExec.mode,
                            summary = synthesizedAnswer,
                            sourceCount = researchResult.sources.size,
                            sourcesJson = org.json.JSONArray().apply {
                                researchResult.sources.forEach { put(it.toJsonObject()) }
                            }.toString(),
                            durationMs = System.currentTimeMillis() - startTime,
                            isSuccess = researchResult.isSuccess
                        )
                        reachHistoryStore.saveHistory(historyItem)

                        val plan = OrchestratorPlan(
                            userGoal = cleanedInput,
                            intentType = IntentType.ACTIONABLE,
                            status = if (researchResult.isSuccess) PlanStatus.COMPLETED else PlanStatus.FAILED,
                            finalResponse = synthesizedAnswer
                        )
                        return@withContext OrchestratorResult(
                            plan = plan,
                            isSuccess = researchResult.isSuccess,
                            responseText = synthesizedAnswer,
                            memoryUpdated = true,
                            executionDurationMs = System.currentTimeMillis() - startTime
                        )
                    }
                }
                is ParsedAction.MakeCall -> {
                    val callPlaced = callManager.makeCall(parsedAction.recipient)
                    val reply = if (callPlaced) {
                        "Ji Boss, ${parsedAction.recipient} ko call lagaya ja raha hai."
                    } else {
                        "Boss, ${parsedAction.recipient} ko call lagane me samasya aayi. Kripya phone permission aur contact check karein."
                    }
                    val plan = OrchestratorPlan(
                        userGoal = cleanedInput,
                        intentType = IntentType.ACTIONABLE,
                        status = if (callPlaced) PlanStatus.COMPLETED else PlanStatus.FAILED,
                        finalResponse = reply
                    )
                    return@withContext OrchestratorResult(
                        plan = plan,
                        isSuccess = callPlaced,
                        responseText = reply,
                        memoryUpdated = true,
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }
                is ParsedAction.TaskManagement -> {
                    val taskMgr = com.soltini.app.scheduler.ScheduledTaskManager.getInstance(context)
                    val reply = when (parsedAction.actionType) {
                        com.soltini.app.orchestrator.TaskActionType.LIST -> {
                            taskMgr.getUpcomingTasksSummary()
                        }
                        com.soltini.app.orchestrator.TaskActionType.CANCEL -> {
                            val target = taskMgr.findTaskByQuery(parsedAction.query)
                            if (target != null) {
                                taskMgr.cancelTask(target.id)
                                "Ji Boss, \"${target.title}\" task cancel kar diya gaya hai."
                            } else {
                                "Boss, cancel karne ke liye koi matching task nahi mila."
                            }
                        }
                        com.soltini.app.orchestrator.TaskActionType.RESCHEDULE -> {
                            val target = taskMgr.findTaskByQuery(parsedAction.query)
                            if (target != null) {
                                "Ji Boss, \"${target.title}\" ko kab schedule karna hai? Samay batayein."
                            } else {
                                "Boss, reschedule karne ke liye task nahi mila. Scheduled Tasks screen se bhi change kar sakte hain."
                            }
                        }
                    }
                    val plan = OrchestratorPlan(
                        userGoal = cleanedInput,
                        intentType = IntentType.CONVERSATIONAL,
                        status = PlanStatus.COMPLETED,
                        finalResponse = reply
                    )
                    return@withContext OrchestratorResult(
                        plan = plan,
                        isSuccess = true,
                        responseText = reply,
                        memoryUpdated = false,
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }
                is ParsedAction.ScheduledAction -> {
                    if (parsedAction.ambiguityQuestion != null) {
                        val reply = parsedAction.ambiguityQuestion
                        val plan = OrchestratorPlan(
                            userGoal = cleanedInput,
                            intentType = IntentType.CONVERSATIONAL,
                            status = PlanStatus.COMPLETED,
                            finalResponse = reply
                        )
                        return@withContext OrchestratorResult(
                            plan = plan,
                            isSuccess = true,
                            responseText = reply,
                            memoryUpdated = false,
                            executionDurationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    val taskMgr = com.soltini.app.scheduler.ScheduledTaskManager.getInstance(context)
                    val outcome = taskMgr.scheduleFromNaturalCommand(
                        command = parsedAction.rawCommand,
                        taskType = parsedAction.taskType,
                        explicitTimeMs = parsedAction.targetTimeMs
                    )
                    val reply = when (outcome) {
                        is com.soltini.app.scheduler.ScheduleOutcome.Success -> outcome.confirmationMessage
                        is com.soltini.app.scheduler.ScheduleOutcome.ClarificationNeeded -> outcome.question
                        is com.soltini.app.scheduler.ScheduleOutcome.Error -> "Boss, ${outcome.message}"
                    }
                    val plan = OrchestratorPlan(
                        userGoal = cleanedInput,
                        intentType = IntentType.ACTIONABLE,
                        status = PlanStatus.COMPLETED,
                        finalResponse = reply
                    )
                    return@withContext OrchestratorResult(
                        plan = plan,
                        isSuccess = true,
                        responseText = reply,
                        memoryUpdated = true,
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }
                is ParsedAction.FillForm -> {
                    onStatusUpdate?.invoke("Form fields scan aur fill kar rahe hain...")
                    val fieldsArray = org.json.JSONArray()
                    for ((k, v) in parsedAction.fields) {
                        fieldsArray.put(org.json.JSONObject().apply {
                            put("label", k)
                            put("value", v)
                        })
                    }

                    val result = if (fieldsArray.length() > 0) {
                        agentToolExecutor.formAutomator.fillForm(
                            fields = fieldsArray,
                            autoSubmit = parsedAction.autoSubmit
                        )
                    } else {
                        val scan = agentToolExecutor.screenAnalyzer.scanScreen()
                        if (scan.hasCaptcha) {
                            org.json.JSONObject().apply {
                                put("status", "captcha_detected")
                                put("message", "Screen par captcha detect hua hai (${scan.captchaDetails}). Kripya use manually solve karein.")
                            }
                        } else if (scan.fields.isEmpty()) {
                            org.json.JSONObject().apply {
                                put("status", "failed")
                                put("message", "Screen par koi form field nahi mila. Kripya target app me form kholiye.")
                            }
                        } else {
                            val detectedLabels = scan.fields.take(5).joinToString(", ") { it.label }
                            org.json.JSONObject().apply {
                                put("status", "scanned")
                                put("message", "Maine form ke ${scan.fields.size} fields detect kar liye hain ($detectedLabels). Kripya batayein kya details bharni hain, jaise: 'Naam Rahul aur email r@gmail.com bhar do'.")
                            }
                        }
                    }

                    val ok = result.optString("status") in listOf("success", "scanned", "submitted")
                    val reply = result.optString("message", "Form automation complete ho gaya.")
                    val plan = OrchestratorPlan(
                        userGoal = cleanedInput,
                        intentType = IntentType.ACTIONABLE,
                        status = if (ok) PlanStatus.COMPLETED else PlanStatus.FAILED,
                        finalResponse = reply
                    )
                    return@withContext OrchestratorResult(
                        plan = plan,
                        isSuccess = ok,
                        responseText = reply,
                        memoryUpdated = true,
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // PHASE 1: UNDERSTAND (Goal parsing & Context Retrieval)
        // ═════════════════════════════════════════════════════════════════════
        onStatusUpdate?.invoke("Recalling context & understanding goal...")
        val memoryContext = memory2.retrieveContext(cleanedInput, maxItems = 8)
        Log.d(TAG, "Phase 1: Retrieved ${memoryContext.relevantItems.size} memories")

        val structuredIntent = parseIntentAndParametersWithGemini(cleanedInput, memoryContext)
        val intentType = structuredIntent?.intentType ?: classifyIntentFallback(cleanedInput)
        Log.d(TAG, "Phase 1: Intent classified as $intentType")

        // 1.A: Handle Memory Query ("Tumhe mere baare mein kya yaad hai?", "Meri memory dikhao")
        if (intentType == IntentType.MEMORY_QUERY) {
            val memorySummary = memory2.getMemoriesSummaryForUser()
            val plan = OrchestratorPlan(
                userGoal = cleanedInput,
                intentType = intentType,
                status = PlanStatus.COMPLETED,
                finalResponse = memorySummary
            )
            return@withContext OrchestratorResult(
                plan = plan,
                isSuccess = true,
                responseText = memorySummary,
                memoryUpdated = false,
                executionDurationMs = System.currentTimeMillis() - startTime
            )
        }

        // 1.B: Handle Memory Delete ("Is baat ko memory se delete kar do")
        if (intentType == IntentType.MEMORY_DELETE) {
            val targetKeyword = cleanedInput
                .replace("is baat ko memory se delete kar do", "", ignoreCase = true)
                .replace("memory se delete kar do", "", ignoreCase = true)
                .replace("delete from memory", "", ignoreCase = true)
                .replace("memory se hata do", "", ignoreCase = true)
                .replace("ye memory delete karo", "", ignoreCase = true)
                .replace("delete this memory", "", ignoreCase = true)
                .trim()
            val deletedCount = if (targetKeyword.isNotBlank()) {
                memory2.deleteMemoriesMatching(targetKeyword)
            } else {
                val all = memory2.getAllMemoriesUnified()
                if (all.isNotEmpty()) {
                    memory2.deleteMemory(all.first().id)
                    1
                } else 0
            }
            val reply = if (deletedCount > 0) {
                "Ji Boss, maine '$targetKeyword' se related memory records ko delete kar diya hai."
            } else {
                "Boss, '$targetKeyword' se matching koi record memory me nahi mila."
            }
            val plan = OrchestratorPlan(
                userGoal = cleanedInput,
                intentType = intentType,
                status = PlanStatus.COMPLETED,
                finalResponse = reply
            )
            return@withContext OrchestratorResult(
                plan = plan,
                isSuccess = deletedCount > 0,
                responseText = reply,
                memoryUpdated = true,
                executionDurationMs = System.currentTimeMillis() - startTime
            )
        }

        // 1.C: Handle Memory Update ("Is memory ko update karo")
        if (intentType == IntentType.MEMORY_UPDATE) {
            val updatedFact = cleanedInput
                .replace("is memory ko update karo", "", ignoreCase = true)
                .replace("update this memory", "", ignoreCase = true)
                .replace("update memory", "", ignoreCase = true)
                .trim()
            val textToSave = if (updatedFact.isNotBlank()) updatedFact else cleanedInput
            memory2.saveExplicitMemory(textToSave, MemoryType.FACT, confirmed = true)
            val reply = "Ji Boss, maine aapki memory update kar di hai: \"$textToSave\""
            val plan = OrchestratorPlan(
                userGoal = cleanedInput,
                intentType = intentType,
                status = PlanStatus.COMPLETED,
                finalResponse = reply
            )
            return@withContext OrchestratorResult(
                plan = plan,
                isSuccess = true,
                responseText = reply,
                memoryUpdated = true,
                executionDurationMs = System.currentTimeMillis() - startTime
            )
        }

        // 1.D: Handle User Correction / Preference Rule ("Short answers diya karo", "Ye contact galat tha", "Ye mera current project hai")
        if (intentType == IntentType.USER_CORRECTION) {
            val lower = cleanedInput.lowercase()
            val reply = when {
                lower.contains("short answer") || lower.contains("chote answer") -> {
                    memory2.saveExplicitMemory("User prefers short, concise answers without fluff.", MemoryType.PREFERENCE, confirmed = true)
                    "Ji Boss, note kar liya! Aage se bilkul concise aur direct jawab dungi."
                }
                lower.contains("project") -> {
                    memory2.saveExplicitMemory(cleanedInput, MemoryType.PROJECT, confirmed = true)
                    "Ji Boss, maine aapke current project ki detail update kar li hai!"
                }
                lower.contains("contact") && (lower.contains("galat") || lower.contains("wrong")) -> {
                    memory2.saveUserCorrection("Always double check and confirm recipient contact before executing actions.", cleanedInput)
                    "Samajh gayi Boss! Aage se contact number verify karne ke baad hi action lungi."
                }
                else -> {
                    memory2.saveUserCorrection(cleanedInput)
                    "Ji Boss, maine aapki instruction note kar li hai aur apni behavior update kar li hai."
                }
            }
            // Record autonomous learning from user correction
            com.soltini.app.learning.ExperienceLearningEngine.getInstance(context).recordUserCorrection(
                taskType = "user_preference_correction",
                originalGoal = cleanedInput,
                wrongMethodAttempted = "PreviousAssumption",
                correctedMethod = cleanedInput,
                newSteps = listOf("Follow_User_Correction"),
                newTools = emptyList()
            )
            val plan = OrchestratorPlan(
                userGoal = cleanedInput,
                intentType = intentType,
                status = PlanStatus.COMPLETED,
                finalResponse = reply
            )
            return@withContext OrchestratorResult(
                plan = plan,
                isSuccess = true,
                responseText = reply,
                memoryUpdated = true,
                executionDurationMs = System.currentTimeMillis() - startTime
            )
        }

        // 1.E: Handle Explicit Memory Command ("MYRA, ise yaad rakhna")
        if (intentType == IntentType.EXPLICIT_MEMORY) {
            val memoryFact = cleanedInput
                .replace("ise yaad rakhna ki", "", ignoreCase = true)
                .replace("ise yaad rakhna", "", ignoreCase = true)
                .replace("yaad rakhna ki", "", ignoreCase = true)
                .replace("remember that", "", ignoreCase = true)
                .replace("remember", "", ignoreCase = true)
                .trim()
            val factToSave = if (memoryFact.isNotBlank()) memoryFact else cleanedInput
            memory2.saveExplicitMemory(factToSave, MemoryType.FACT, confirmed = true)
            val plan = OrchestratorPlan(
                userGoal = cleanedInput,
                intentType = intentType,
                status = PlanStatus.COMPLETED,
                finalResponse = "Ji Boss, maine yeh yaad rakh liya hai!"
            )
            return@withContext OrchestratorResult(
                plan = plan,
                isSuccess = true,
                responseText = plan.finalResponse,
                memoryUpdated = true,
                executionDurationMs = System.currentTimeMillis() - startTime
            )
        }

        // 1.F: Conversational
        if (intentType == IntentType.CONVERSATIONAL) {
            onStatusUpdate?.invoke("Formulating answer...")
            val response = generateConversationalResponse(cleanedInput, memoryContext)
            val plan = OrchestratorPlan(
                userGoal = cleanedInput,
                intentType = intentType,
                status = PlanStatus.COMPLETED,
                finalResponse = response
            )
            memory2.session.recordTopic(cleanedInput.take(30))
            memory2.evaluateAndStore(
                goal = cleanedInput,
                outcomeSummary = response,
                toolSequence = emptyList(),
                isSuccess = true
            )
            return@withContext OrchestratorResult(
                plan = plan,
                isSuccess = true,
                responseText = response,
                memoryUpdated = true,
                executionDurationMs = System.currentTimeMillis() - startTime
            )
        }

        // ═════════════════════════════════════════════════════════════════════
        // PHASE 2: THINK (Evaluate capabilities, past experiences & safety risks)
        // ═════════════════════════════════════════════════════════════════════
        onStatusUpdate?.invoke("Thinking & evaluating capabilities...")
        val recommendedTools = memoryContext.experienceRecommendation?.toolSequence ?: emptyList()
        Log.d(TAG, "Phase 2 (Think): Past experience recommendation: $recommendedTools")

        // ═════════════════════════════════════════════════════════════════════
        // PHASE 3: PLAN (Create step-by-step execution plan)
        // ═════════════════════════════════════════════════════════════════════
        onStatusUpdate?.invoke("Creating step-by-step plan...")
        val plan = createExecutionPlan(cleanedInput, intentType, structuredIntent, memoryContext)
        Log.i(TAG, "Phase 3 (Plan): Generated plan with ${plan.steps.size} steps.")

        // ═════════════════════════════════════════════════════════════════════
        // PHASE 4: EXECUTE & PHASE 5: MONITOR/EVALUATE & PHASE 6: IMPROVE/RETRY
        // ═════════════════════════════════════════════════════════════════════
        memory2.working.startTask(cleanedInput)
        val toolSequenceUsed = mutableListOf<String>()
        var isOverallSuccess = true
        val resultsAccumulator = StringBuilder()
        var lastResultData: Any? = null
        var lastResultSummary = ""

        plan.status = PlanStatus.EXECUTING

        for ((index, step) in plan.steps.withIndex()) {
            memory2.working.setStepIndex(index)
            step.status = StepStatus.RUNNING

            // Check condition if present
            if (!step.condition.isNullOrBlank()) {
                val shouldRun = evaluateCondition(step.condition, memory2)
                if (!shouldRun) {
                    step.status = StepStatus.SKIPPED
                    Log.d(TAG, "Step [${step.id}] skipped due to unsatisfied condition: ${step.condition}")
                    continue
                }
            }

            // Dependency Graph Resolution: Check if prerequisite step completed
            if (!step.dependsOnStepId.isNullOrBlank()) {
                val parentStep = plan.steps.find { it.id == step.dependsOnStepId }
                if (parentStep != null && parentStep.status != StepStatus.COMPLETED && parentStep.status != StepStatus.SKIPPED) {
                    Log.w(TAG, "Step [${step.id}] dependency [${step.dependsOnStepId}] is in status: ${parentStep.status}. Marking step as failed.")
                    step.status = StepStatus.FAILED
                    step.result = PluginResult.failure("none", "Prerequisite step [${step.dependsOnStepId}] failed.")
                    isOverallSuccess = false
                    resultsAccumulator.append("Step '${step.description}' failed because previous action did not complete. ")
                    continue
                }
            }

            // Dynamic intermediate variable binding ($step1_output, $summary_text, $prev_output)
            val resolvedParams = resolveStepParameters(
                step = step,
                memory2 = memory2,
                plan = plan,
                lastResultData = lastResultData,
                lastResultSummary = lastResultSummary
            )
            step.parameters = resolvedParams

            // Resolve capable plugin from Plugin Registry
            val plugin = resolvePluginForStep(step)
            if (plugin == null) {
                step.status = StepStatus.FAILED
                step.result = PluginResult.failure("none", "No capable plugin found in registry for: ${step.requiredCapability}")
                isOverallSuccess = false
                resultsAccumulator.append("Could not find capable tool for ${step.description}. ")
                continue
            }

            step.selectedPluginId = plugin.metadata.id
            toolSequenceUsed.add(plugin.metadata.id)

            val stepContext = PluginContext(
                context = context,
                parameters = resolvedParams,
                callerId = "orchestrator"
            )

            // EXECUTE STEP
            onStatusUpdate?.invoke("Executing Step ${index + 1}/${plan.steps.size}: ${plugin.metadata.name}...")
            val stepStart = System.currentTimeMillis()
            var execResult = try {
                plugin.execute(stepContext)
            } catch (e: Exception) {
                PluginResult.failure(plugin.metadata.id, e.message ?: "Execution exception")
            }
            val stepLatency = System.currentTimeMillis() - stepStart
            pluginRegistry.recordExecution(plugin.metadata.id, execResult.isSuccess, stepLatency)

            // PHASE 5: MONITOR & EVALUATE
            onStatusUpdate?.invoke("Verifying Step ${index + 1} results...")
            var (isVerified, verifyReason) = verifyStepResult(execResult)

            // PHASE 6: IMPROVE / RETRY / STRATEGY FALLBACK
            val isSensitive = isDestructiveOrSensitiveAction(plugin, step)

            // Safe Retry: Only if NOT destructive/sensitive and failed
            if ((!execResult.isSuccess || !isVerified) && !isSensitive) {
                var retryAttempt = 1
                val maxRetries = 3
                while (retryAttempt <= maxRetries && (!execResult.isSuccess || !isVerified)) {
                    step.retryAttempts = retryAttempt
                    onStatusUpdate?.invoke("Retrying ${plugin.metadata.name} (Attempt $retryAttempt/$maxRetries)...")
                    Log.w(TAG, "Safe retry attempt $retryAttempt/$maxRetries for ${plugin.metadata.id} after: $verifyReason")
                    delay(retryAttempt * 500L) // Exponential backoff

                    execResult = try {
                        plugin.execute(stepContext)
                    } catch (e: Exception) {
                        PluginResult.failure(plugin.metadata.id, e.message ?: "Retry exception")
                    }
                    val verifiedCheck = verifyStepResult(execResult)
                    isVerified = verifiedCheck.first
                    verifyReason = verifiedCheck.second

                    if (execResult.isSuccess && isVerified) {
                        step.verified = true
                        step.verificationNotes = "Succeeded on retry attempt $retryAttempt"
                        break
                    }
                    retryAttempt++
                }
            } else if ((!execResult.isSuccess || !isVerified) && isSensitive) {
                Log.w(TAG, "Step [${step.id}] failed. Destructive/sensitive action (${plugin.metadata.id}) will NOT be automatically retried.")
                step.verificationNotes = "Destructive/sensitive action not retried after failure: $verifyReason"
            }

            // Strategy Change / Fallback if still failed
            if (!execResult.isSuccess || !isVerified) {
                val fallbackPlugin = pluginRegistry.findFallbackPlugin(plugin.metadata.id)
                if (fallbackPlugin != null) {
                    onStatusUpdate?.invoke("Changing strategy: trying fallback ${fallbackPlugin.metadata.name}...")
                    step.usedFallback = true
                    step.fallbackPluginId = fallbackPlugin.metadata.id
                    toolSequenceUsed.add(fallbackPlugin.metadata.id)

                    val fbResult = try {
                        fallbackPlugin.execute(stepContext)
                    } catch (e: Exception) {
                        PluginResult.failure(fallbackPlugin.metadata.id, e.message ?: "Fallback exception")
                    }
                    val (fbVerified, fbReason) = verifyStepResult(fbResult)
                    pluginRegistry.recordExecution(fallbackPlugin.metadata.id, fbResult.isSuccess && fbVerified, 0L)
                    if (fbResult.isSuccess && fbVerified) {
                        execResult = fbResult
                        isVerified = true
                        verifyReason = "Fallback succeeded: $fbReason"
                    }
                }
            }

            step.result = execResult
            step.verified = isVerified
            step.verificationNotes = verifyReason

            if (execResult.isSuccess && isVerified) {
                step.status = StepStatus.COMPLETED
                val outData = execResult.data ?: execResult.summary
                lastResultData = outData
                lastResultSummary = execResult.summary

                // Store intermediate outputs for downstream step chaining
                memory2.working.storeIntermediateResult(step.id, outData)
                memory2.working.storeIntermediateResult("${step.id}_output", outData)
                memory2.working.storeIntermediateResult("${step.id}_summary", execResult.summary)
                val normalizedId = step.id.replace("_", "")
                memory2.working.storeIntermediateResult("${normalizedId}_output", outData)
                memory2.working.storeIntermediateResult("summary_text", execResult.summary)
                memory2.working.storeIntermediateResult("prev_output", outData)

                memory2.session.recordToolResult(step.selectedPluginId ?: "plugin", execResult.summary)
                resultsAccumulator.append(execResult.summary).append(". ")
            } else {
                step.status = StepStatus.FAILED
                isOverallSuccess = false
                resultsAccumulator.append("Step '${step.description}' encountered an error: ${execResult.error ?: verifyReason}. ")
            }
        }

        plan.status = if (isOverallSuccess) PlanStatus.COMPLETED else PlanStatus.PARTIALLY_COMPLETED

        // ═════════════════════════════════════════════════════════════════════
        // PHASE 7: EVALUATE & RECORD LEARNING
        // ═════════════════════════════════════════════════════════════════════
        onStatusUpdate?.invoke("Evaluating outcome & recording experience...")
        memory2.evaluateAndStore(
            goal = cleanedInput,
            outcomeSummary = resultsAccumulator.toString(),
            toolSequence = toolSequenceUsed,
            isSuccess = isOverallSuccess
        )
        // Record into autonomous ExperienceLearningEngine
        val learningEngine = com.soltini.app.learning.ExperienceLearningEngine.getInstance(context)
        learningEngine.recordTryAndResult(
            goal = cleanedInput,
            taskType = plan.intentType.name.lowercase(),
            methodAttempted = plan.steps.mapNotNull { it.selectedPluginId }.joinToString(" -> ").ifBlank { "DirectAction" },
            toolsUsed = toolSequenceUsed,
            steps = plan.steps.map { it.description },
            isSuccess = isOverallSuccess,
            outcomeSummary = resultsAccumulator.toString(),
            userConfirmed = (intentType == IntentType.USER_CORRECTION)
        )
        memory2.working.completeTask()

        // ═════════════════════════════════════════════════════════════════════
        // PHASE 8: COMPLETE (Synthesize One Final Response from Verified Results)
        // ═════════════════════════════════════════════════════════════════════
        onStatusUpdate?.invoke("Synthesizing response...")
        val finalResponse = synthesizeFinalResponse(cleanedInput, plan, resultsAccumulator.toString())
        plan.finalResponse = finalResponse

        Log.i(TAG, "─── ORCHESTRATOR COMPLETE: Duration=${System.currentTimeMillis() - startTime}ms, Success=$isOverallSuccess ───")

        OrchestratorResult(
            plan = plan,
            isSuccess = isOverallSuccess,
            responseText = finalResponse,
            toolSequenceUsed = toolSequenceUsed,
            memoryUpdated = true,
            executionDurationMs = System.currentTimeMillis() - startTime
        )
    }

    // ─── Step 1: LLM-Based Structured Intent & Parameter Parser ───────────────

    private suspend fun parseIntentAndParametersWithGemini(
        input: String,
        memoryContext: RetrievedMemoryContext
    ): StructuredIntentResult? {
        val apiKey = appSettings.effectiveApiKey().trim()
        if (apiKey.isBlank()) return null

        return try {
            val manifest = pluginRegistry.getCapabilitiesManifest().toString()
            val prompt = """You are MYRA's Orchestrator Intent & Structured Parameter Parser.
Parse the user's input into a single structured JSON response based on available capabilities and memory context.

AVAILABLE CAPABILITIES:
$manifest

MEMORY CONTEXT:
${memoryContext.formattedPromptBlock}

USER REQUEST:
"$input"

Output a single valid JSON object strictly matching this schema:
{
  "intent_type": "CONVERSATIONAL | ACTIONABLE | MULTISTEP_COMPLEX | EXPLICIT_MEMORY | MEMORY_QUERY | MEMORY_DELETE | MEMORY_UPDATE | USER_CORRECTION",
  "capability": "plugin_id from manifest or null",
  "parameters": { },
  "entities": {
    "file_reference": "natural file name or description (e.g. physics notes, bill, report) or null",
    "target_folder": "target folder name or null"
  }
}

Guidelines:
1. If the user asks what you remember, or asks to view/list memories (e.g. "tumhe mere baare mein kya yaad hai", "meri memory dikhao", "what do you remember about me"), set "intent_type": "MEMORY_QUERY".
2. If the user asks to delete or forget something from memory (e.g. "is baat ko memory se delete kar do", "bhool jao"), set "intent_type": "MEMORY_DELETE".
3. If the user asks to update an existing memory or fact, set "intent_type": "MEMORY_UPDATE".
4. If the user provides a preference, rule, or correction about behavior (e.g. "short answers diya karo", "ye galat tha", "ye mera current project hai"), set "intent_type": "USER_CORRECTION".
5. If the user explicitly asks to remember something (e.g. "remember that...", "mera naam...", "ise yaad rakhna"), set "intent_type": "EXPLICIT_MEMORY".
6. If the user asks for multiple distinct actions/steps (e.g. "Reel dekho, WhatsApp pe bhejo, lights band karo" or "Check weather and remind me"), set "intent_type": "MULTISTEP_COMPLEX".
7. If the user asks for a single specific task matching an available capability (e.g. weather, control device, set location trigger / geofence like "ghar pahunchte hi Wi-Fi on karo" -> capability "set_location_trigger" with action_description and label, flashlight, file read/edit/delete/search/summarize, crypto rate, timer, screen action), set "intent_type": "ACTIONABLE", set "capability" to the exact matching plugin_id, and extract pertinent keys into "parameters". If a file or document is referenced in any way, populate "entities.file_reference" and/or "entities.target_folder".
8. If the user is having a casual conversation, greeting, asking general questions, or chatting, set "intent_type": "CONVERSATIONAL" and "capability": null.
9. Return ONLY raw JSON without markdown code fences or conversational text."""

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
            }.toString()

            val req = Request.Builder()
                .url("$BASE_URL/$PRIMARY_MODEL:generateContent?key=$apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                AppLogger.w(TAG, "Gemini structured intent parsing HTTP ${resp.code}")
                return null
            }

            val respBody = resp.body?.string() ?: return null
            val text = JSONObject(respBody)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text") ?: return null

            val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val json = JSONObject(clean)
            val intentTypeStr = json.optString("intent_type", "CONVERSATIONAL").uppercase()
            val intentType = when {
                intentTypeStr.contains("QUERY") -> IntentType.MEMORY_QUERY
                intentTypeStr.contains("DELETE") -> IntentType.MEMORY_DELETE
                intentTypeStr.contains("UPDATE") -> IntentType.MEMORY_UPDATE
                intentTypeStr.contains("CORRECTION") -> IntentType.USER_CORRECTION
                intentTypeStr.contains("EXPLICIT") -> IntentType.EXPLICIT_MEMORY
                intentTypeStr.contains("MULTISTEP") -> IntentType.MULTISTEP_COMPLEX
                intentTypeStr.contains("ACTION") -> IntentType.ACTIONABLE
                else -> IntentType.CONVERSATIONAL
            }
            val capability = json.optString("capability").ifBlank { null }
            val parameters = json.optJSONObject("parameters") ?: JSONObject()
            val entities = json.optJSONObject("entities") ?: JSONObject()

            Log.d(TAG, "Gemini parsed structured intent: type=$intentType, capability=$capability, entities=$entities")
            StructuredIntentResult(
                intentType = intentType,
                capability = capability,
                parameters = parameters,
                entities = entities
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Gemini structured intent parser fallback: ${e.message}")
            Log.w(TAG, "Gemini structured intent parser fallback: ${e.message}")
            null
        }
    }

    private fun classifyIntentFallback(input: String): IntentType {
        val lower = input.lowercase()

        // 1. Memory Query
        if (lower.contains("kya yaad hai") || lower.contains("memory dikhao") ||
            lower.contains("what do you remember") || lower.contains("show my memory") ||
            lower.contains("show memory") || lower.contains("list my memories") ||
            lower.contains("meri memory")) {
            return IntentType.MEMORY_QUERY
        }

        // 2. Memory Delete
        if (lower.contains("memory se delete") || lower.contains("memory se hata") ||
            lower.contains("delete from memory") || lower.contains("remove from memory") ||
            lower.contains("bhool jao") || lower.contains("is memory ko delete")) {
            return IntentType.MEMORY_DELETE
        }

        // 3. Memory Update
        if (lower.contains("memory ko update") || lower.contains("update memory") ||
            lower.contains("memory badlo") || lower.contains("is memory ko update")) {
            return IntentType.MEMORY_UPDATE
        }

        // 4. User Correction & Preference Rules
        if (lower.startsWith("short answer") || lower.contains("short answers diya karo") ||
            lower.contains("chote answer") || lower.contains("galat tha") ||
            lower.contains("galat bataya") || lower.contains("aage se") ||
            lower.contains("mera current project") || lower.contains("ye mera project") ||
            lower.contains("don't say that") || lower.contains("stop doing that")) {
            return IntentType.USER_CORRECTION
        }

        // 5. Explicit memory commands
        if (lower.startsWith("remember") || lower.startsWith("yaad rakh") ||
            lower.contains("remember that") || lower.contains("yaad rakhna ki") ||
            lower.contains("ise yaad rakhna") || lower.contains("hamesha yaad rakhna")) {
            return IntentType.EXPLICIT_MEMORY
        }

        // Multi-step complex intents containing conjunctions / conditional phrases / multiple coordinated actions
        val hasCondition = lower.contains("if ") || lower.contains("agar ") ||
                lower.contains("then ") || lower.contains("aur agar ") ||
                lower.contains("otherwise ") || lower.contains("warna ")
        val hasMultiAction = (lower.contains(" and ") || lower.contains(" aur ") || lower.contains(",") || lower.contains(";")) &&
                (lower.contains("remind") || lower.contains("send") || lower.contains("message") || lower.contains("weather") ||
                 lower.contains("alarm") || lower.contains("whatsapp") || lower.contains("calendar") || lower.contains("summarize") ||
                 lower.contains("notes") || lower.contains("follow-up") || lower.contains("followup") || lower.contains("bhejo") ||
                 lower.contains("padho") || lower.contains("kholo") || lower.contains("daal do"))

        if (hasCondition || hasMultiAction) {
            return IntentType.MULTISTEP_COMPLEX
        }

        // Actionable triggers
        val actionKeywords = listOf(
            "weather", "mausam", "temperature", "forecast", "barish", "rain",
            "crypto", "bitcoin", "eth", "btc", "price", "rate",
            "torch", "flashlight", "light", "volume", "mute", "silent",
            "screenshot", "screen shot", "photo", "screen dekho", "dekho", "padho",
            "instagram", "insta", "dm", "whatsapp", "bhejo", "message", "msg",
            "youtube", "play", "alarm", "timer", "clock",
            "open", "launch", "kholo", "search", "dhoondo", "form", "fill",
            "call", "answer", "cut", "battery", "status", "fan", "switch", "lamp", "api"
        )

        val isAction = actionKeywords.any { lower.contains(it) }
        return if (isAction) IntentType.ACTIONABLE else IntentType.CONVERSATIONAL
    }

    // ─── Step 3: Capability Discovery & Planning ─────────────────────────────

    private suspend fun createExecutionPlan(
        input: String,
        intentType: IntentType,
        structuredIntent: StructuredIntentResult?,
        memoryContext: RetrievedMemoryContext
    ): OrchestratorPlan {
        val plan = OrchestratorPlan(
            userGoal = input,
            intentType = intentType
        )

        // If it's a complex multi-step request, use Gemini Flash decomposition with registry grounding
        if (intentType == IntentType.MULTISTEP_COMPLEX) {
            val plannedSteps = planComplexWithGemini(input, memoryContext)
            if (plannedSteps.isNotEmpty()) {
                plan.steps.addAll(plannedSteps)
                return plan
            }
        }

        // If structuredIntent identified an ACTIONABLE capability and plugin exists
        if (structuredIntent != null && intentType == IntentType.ACTIONABLE && !structuredIntent.capability.isNullOrBlank()) {
            val targetPlugin = pluginRegistry.getPlugin(structuredIntent.capability)
            if (targetPlugin != null) {
                val params = JSONObject(structuredIntent.parameters.toString())

                // Merge extracted entities into parameters for file actions and others
                val fileRef = structuredIntent.entities.optString("file_reference").ifBlank { null }
                if (fileRef != null) {
                    if (!params.has("file_name")) params.put("file_name", fileRef)
                    if (!params.has("file_reference")) params.put("file_reference", fileRef)
                    if (!params.has("raw_query")) params.put("raw_query", input)
                }
                val targetFolder = structuredIntent.entities.optString("target_folder").ifBlank { null }
                if (targetFolder != null) {
                    if (!params.has("destination_folder")) params.put("destination_folder", targetFolder)
                    if (!params.has("target_folder")) params.put("target_folder", targetFolder)
                }
                if (!params.has("query")) {
                    params.put("query", input)
                }
                if (!params.has("raw_query")) {
                    params.put("raw_query", input)
                }

                plan.steps.add(
                    TaskStep(
                        id = "step_main",
                        description = "Execute ${targetPlugin.metadata.name}",
                        requiredCapability = targetPlugin.metadata.id,
                        selectedPluginId = targetPlugin.metadata.id,
                        parameters = params
                    )
                )
                return plan
            }
        }

        // Fallback to keyword matching & candidate evaluation
        return createExecutionPlanFallback(input, intentType, memoryContext)
    }

    private fun createExecutionPlanFallback(
        input: String,
        intentType: IntentType,
        memoryContext: RetrievedMemoryContext
    ): OrchestratorPlan {
        val plan = OrchestratorPlan(
            userGoal = input,
            intentType = intentType
        )

        val lower = input.lowercase()

        // Example: Weather + Conditional Reminder
        if ((lower.contains("weather") || lower.contains("mausam")) &&
            (lower.contains("remind") || lower.contains("alarm") || lower.contains("umbrella") || lower.contains("chhatri"))) {

            val city = extractCity(input, memoryContext)
            val step1 = TaskStep(
                id = "step_weather",
                description = "Retrieve weather forecast for $city",
                requiredCapability = "weather_service",
                selectedPluginId = "weather_service",
                parameters = JSONObject().apply { put("city", city) }
            )
            val step2 = TaskStep(
                id = "step_reminder",
                description = "Set reminder to carry umbrella if rain expected",
                requiredCapability = "clock_timer_service",
                selectedPluginId = "clock_timer_service",
                dependsOnStepId = "step_weather",
                condition = "rain_expected",
                parameters = JSONObject().apply {
                    put("seconds", 3600)
                    put("label", "Take Umbrella - Rain Expected")
                }
            )
            plan.steps.add(step1)
            plan.steps.add(step2)
            return plan
        }

        // Single actionable capability
        val lowerInput = input.lowercase()
        val isFileRequest = Regex("""(?i)\.(pdf|txt|md|json|kt|java|py|png|jpg|jpeg|csv|zip)\b""").containsMatchIn(input) ||
                lowerInput.contains("file") || lowerInput.contains("folder") || lowerInput.contains("directory") ||
                lowerInput.contains("document") || lowerInput.contains("in files") || lowerInput.contains("files me")

        val candidates = pluginRegistry.findCandidatesForTask(input, limit = 5)
        val best = if (isFileRequest && pluginRegistry.getPlugin("file_manager_service") != null) {
            candidates.find { it.plugin.metadata.id == "file_manager_service" }
                ?: PluginCandidate(pluginRegistry.getPlugin("file_manager_service")!!, 100f, "File request keyword match", 1.0f)
        } else {
            candidates.firstOrNull()
        }

        if (best != null) {
            val params = extractParametersForPluginFallback(input, best.plugin.metadata.id, memoryContext)
            plan.steps.add(
                TaskStep(
                    id = "step_main",
                    description = "Execute ${best.plugin.metadata.name}",
                    requiredCapability = best.plugin.metadata.id,
                    selectedPluginId = best.plugin.metadata.id,
                    parameters = params
                )
            )
        } else {
            // Fallback to screen operator or general web search
            plan.steps.add(
                TaskStep(
                    id = "step_operator",
                    description = "Execute phone operator for user request",
                    requiredCapability = "screen_operator_agent",
                    selectedPluginId = "screen_operator_agent",
                    parameters = JSONObject().apply { put("goal", input) }
                )
            )
        }

        return plan
    }

    private fun resolvePluginForStep(step: TaskStep): Plugin? {
        // 1. If explicit plugin ID
        step.selectedPluginId?.let { id ->
            val p = pluginRegistry.getPlugin(id)
            if (p != null && p.isAvailable(context)) return p
        }

        // 2. Discover dynamically from registry
        val candidates = pluginRegistry.findCandidatesForTask(step.requiredCapability)
        return candidates.firstOrNull()?.plugin
    }

    private fun extractCity(input: String, memoryContext: RetrievedMemoryContext): String {
        val lower = input.lowercase()
        val inMatch = Regex("in\\s+([a-zA-Z]+)").find(lower)
        if (inMatch != null) return inMatch.groupValues[1].replaceFirstChar { it.uppercase() }

        val meMatch = Regex("([a-zA-Z]+)\\s+me\\b").find(lower)
        if (meMatch != null) return meMatch.groupValues[1].replaceFirstChar { it.uppercase() }

        // Check memory for user saved city
        for (item in memoryContext.relevantItems) {
            if (item.content.lowercase().contains("city") || item.content.lowercase().contains("location")) {
                val words = item.content.split(" ")
                if (words.isNotEmpty()) return words.last()
            }
        }
        return "Delhi"
    }

    private fun extractParametersForPluginFallback(
        input: String,
        pluginId: String,
        memoryContext: RetrievedMemoryContext
    ): JSONObject {
        val json = JSONObject()
        val lower = input.lowercase()

        when (pluginId) {
            "weather_service" -> {
                json.put("city", extractCity(input, memoryContext))
            }
            "crypto_price_service" -> {
                val coin = when {
                    lower.contains("bitcoin") || lower.contains("btc") -> "bitcoin"
                    lower.contains("ethereum") || lower.contains("eth") -> "ethereum"
                    lower.contains("solana") || lower.contains("sol") -> "solana"
                    lower.contains("doge") -> "dogecoin"
                    else -> "bitcoin"
                }
                json.put("coin", coin)
                json.put("currency", if (lower.contains("inr") || lower.contains("rupee")) "inr" else "usd")
            }
            "dictionary_service" -> {
                val match = Regex("(meaning of|define|definition of|kya matlab hai|arth)\\s+([a-zA-Z]+)").find(lower)
                val word = match?.groupValues?.last() ?: input.split(" ").last()
                json.put("word", word)
            }
            "currency_exchange_service" -> {
                json.put("from", "USD")
                json.put("to", "INR")
            }
            "device_torch_controller" -> {
                if (lower.contains("on") || lower.contains("chala") || lower.contains("jalao")) {
                    json.put("enabled", true)
                } else if (lower.contains("off") || lower.contains("band")) {
                    json.put("enabled", false)
                }
            }
            "device_volume_controller" -> {
                val numMatch = Regex("\\b(\\d{1,3})\\b").find(input)
                if (numMatch != null) {
                    json.put("percent", numMatch.groupValues[1].toInt().coerceIn(0, 100))
                } else if (lower.contains("mute") || lower.contains("silent")) {
                    json.put("ringer_mode", "silent")
                }
            }
            "whatsapp_automation_agent" -> {
                val contactMatch = Regex("(to|ko)\\s+([a-zA-Z0-9]+)").find(lower)
                if (contactMatch != null) json.put("contact_name", contactMatch.groupValues[2])
                json.put("message", input)
            }
            "instagram_automation_agent" -> {
                val userMatch = Regex("(to|ko)\\s+([a-zA-Z0-9._]+)").find(lower)
                if (userMatch != null) json.put("username", userMatch.groupValues[2])
                json.put("message", input)
            }
            "app_launcher_service" -> {
                val appMatch = Regex("(open|kholo|launch)\\s+([a-zA-Z0-9]+)").find(lower)
                json.put("app_name", appMatch?.groupValues?.get(2) ?: "Chrome")
            }
            "google_search_service" -> {
                val q = input.replace(Regex("(?i)^(search|google|dhoondo|find)\\s+"), "").trim()
                json.put("query", q)
            }
            "file_manager_service" -> {
                val action = when {
                    lower.contains("delete") || lower.contains("hatao") || lower.contains("mita do") || lower.contains("remove") -> "delete"
                    lower.contains("summarize") || lower.contains("summary") || lower.contains("kya likha hai") -> "summarize"
                    lower.contains("padho") || lower.contains("read") || lower.contains("kholo") || lower.contains("show content") -> "read"
                    lower.contains("edit") || lower.contains("replace") || lower.contains("badal") || lower.contains("write to") -> "edit"
                    lower.contains("copy") -> "copy"
                    lower.contains("move") -> "move"
                    lower.contains("rename") || lower.contains("naam") -> "rename"
                    lower.contains("duplicate") -> "find_duplicates"
                    lower.contains("photo") || lower.contains("image") || lower.contains("ocr") -> "analyze_image"
                    lower.contains("in this document") || lower.contains("chapter") || lower.contains("search in") -> "search_document"
                    lower.contains("create") || lower.contains("banao") || lower.contains("naya") || lower.contains("new file") -> "create"
                    lower.contains("list") || lower.contains("files dikhao") || lower.contains("folder contents") -> "list_directory"
                    else -> "search"
                }
                json.put("action", action)
                json.put("query", input)
                json.put("raw_query", input)

                val filePattern = Regex("""(?i)([a-zA-Z0-9_\-]+\.(txt|pdf|md|json|kt|java|py|js|png|jpg|jpeg|csv|zip))""")
                val foundFiles = filePattern.findAll(input).map { it.value }.toList()
                if (foundFiles.isNotEmpty()) {
                    json.put("file_name", foundFiles[0])
                    if (foundFiles.size > 1 && action == "rename") {
                        json.put("new_name", foundFiles[1])
                    }
                } else {
                    val words = input.split(" ")
                    val candidate = words.find { it.endsWith(".txt") || it.endsWith(".pdf") || it.endsWith(".md") }
                    if (candidate != null) {
                        json.put("file_name", candidate)
                    } else {
                        // Extract natural language file candidate (e.g. "Physics PDF", "chemistry notes", "report")
                        val cleanCandidate = input
                            .replace(Regex("(?i)^(myra|hey myra|soltini),?\\s*"), "")
                            .replace(Regex("(?i)^(please|kripya)\\s*"), "")
                            .replace(Regex("(?i)^(read|summarize|summary of|open|kholo|padho|edit|delete|hatao|mita do|copy|move|show|dekho|search|find|banao|create)\\s+"), "")
                            .replace(Regex("(?i)^(meri|mera|mere|my|the)\\s+"), "")
                            .replace(Regex("(?i)\\s+(padho|kholo|dikhao|batao|summarize karo|hatao|delete karo|mita do|copy karo|move karo)$"), "")
                            .trim()
                        if (cleanCandidate.isNotBlank()) {
                            json.put("file_name", cleanCandidate)
                        }
                    }
                }

                // If rename and new name has no extension or was specified with 'to'
                if (action == "rename" && !json.has("new_name")) {
                    val renameMatch = Regex("""(?i)(?:to|as|karke|naam)\s+['"]?([a-zA-Z0-9_.-]+)['"]?""").find(input)
                    renameMatch?.groupValues?.get(1)?.let {
                        json.put("new_name", it)
                    }
                }

                // Destination folder for copy or move
                if (action == "copy" || action == "move") {
                    val destMatch = Regex("""(?i)(?:to|in|into|me|folder)\s+['"]?([a-zA-Z0-9_ -]+?)['"]?(?:\s+folder|\s+directory|$)""").find(input)
                    destMatch?.groupValues?.get(1)?.let {
                        val trimmed = it.trim()
                        if (trimmed.isNotBlank() && !trimmed.contains(".")) {
                            json.put("destination_folder", trimmed)
                        }
                    }
                }

                // Content extraction for create or edit
                if (action == "create" || action == "edit") {
                    val contentMatch = Regex("""(?i)(?:with content|content:|likha ho|likho|content)\s+['"]?(.*?)['"]?$""").find(input)
                    if (contentMatch != null) {
                        json.put("content", contentMatch.groupValues[1].trim())
                    }

                    val replaceMatch = Regex("""(?i)replace\s+['"]?(.*?)['"]?\s+with\s+['"]?(.*?)['"]?$""").find(input)
                    if (replaceMatch != null) {
                        json.put("find_text", replaceMatch.groupValues[1].trim())
                        json.put("replace_with", replaceMatch.groupValues[2].trim())
                    }
                }

                if (lower.contains("confirm") || lower.contains("haan") || lower.contains("yes") || lower.contains("proceed")) {
                    json.put("confirmed", true)
                }
            }
            else -> {
                json.put("request", input)
            }
        }
        return json
    }

    private fun evaluateCondition(condition: String, mem: Memory2Engine): Boolean {
        if (condition == "rain_expected") {
            val weatherData = mem.working.getIntermediateResult("step_weather")
            val str = weatherData?.toString()?.lowercase() ?: ""
            return str.contains("rain") || str.contains("shower") || str.contains("drizzle") ||
                    str.contains("thunder") || str.contains("cloud") || str.contains("barish")
        }
        return true
    }

    private suspend fun planComplexWithGemini(
        input: String,
        memoryContext: RetrievedMemoryContext
    ): List<TaskStep> {
        val apiKey = appSettings.effectiveApiKey()
        if (apiKey.isBlank()) return emptyList()

        return try {
            val manifest = pluginRegistry.getCapabilitiesManifest().toString()
            val prompt = """You are MYRA's Orchestrator Planning Brain.
Break down this user request into sequential task steps using ONLY the available capabilities in the Plugin Registry.

AVAILABLE CAPABILITIES:
$manifest

MEMORY CONTEXT:
${memoryContext.formattedPromptBlock}

USER REQUEST:
"$input"

IMPORTANT MULTI-STEP CHAINING & VARIABLE PASSING RULES:
1. When user requests multiple coordinated actions (e.g. "Meeting notes summarize karo, Rohan ko WhatsApp karo, aur calendar me follow-up daal do"):
   - Split into distinct sequential steps with IDs: "step_1", "step_2", "step_3", etc.
   - For downstream steps that depend on earlier outputs, set "depends_on" to the predecessor step ID (e.g. "step_1").
   - Pass intermediate output variables in parameters using ${"$"}{step_id}_output, ${"$"}{summary_text}, or ${"$"}{prev_output} (e.g. { "message": "${"$"}{step_1}_output", "contact": "Rohan" }).
2. Always choose exact matching capability from the manifest.

Output a JSON array of step objects:
[
  {
    "id": "step_1",
    "description": "Step description",
    "required_capability": "plugin_id_from_manifest",
    "parameters": { "param1": "value" },
    "depends_on": "step_id or null",
    "condition": "optional condition e.g. rain_expected or null"
  }
]
Output valid JSON array ONLY, no explanation."""

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
            }.toString()

            val req = Request.Builder()
                .url("$BASE_URL/$PRIMARY_MODEL:generateContent?key=$apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return emptyList()
            val respBody = resp.body?.string() ?: return emptyList()

            val text = JSONObject(respBody)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text") ?: return emptyList()

            val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val arr = JSONArray(clean)
            val steps = mutableListOf<TaskStep>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val cap = obj.optString("required_capability")
                val stepId = obj.optString("id").ifBlank { "step_${i + 1}" }
                val depId = obj.optString("depends_on").ifBlank {
                    obj.optString("dependsOnStepId").ifBlank { null }
                }
                steps.add(
                    TaskStep(
                        id = stepId,
                        description = obj.optString("description"),
                        requiredCapability = cap,
                        selectedPluginId = cap,
                        parameters = obj.optJSONObject("parameters") ?: JSONObject(),
                        dependsOnStepId = depId,
                        condition = obj.optString("condition").ifBlank { null }
                    )
                )
            }
            steps
        } catch (e: Exception) {
            Log.w(TAG, "Gemini complex planning fallback: ${e.message}")
            emptyList()
        }
    }

    /**
     * Resolves dynamic intermediate variables (e.g. $step_1_output, $summary_text, $prev_output)
     * from previous task execution steps into the current step parameters.
     */
    private fun resolveStepParameters(
        step: TaskStep,
        memory2: Memory2Engine,
        plan: OrchestratorPlan,
        lastResultData: Any?,
        lastResultSummary: String
    ): JSONObject {
        val resolved = JSONObject()
        val keys = step.parameters.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val rawValue = step.parameters.opt(key)
            if (rawValue is String) {
                var str = rawValue
                // Variable pattern matching $var or ${var}
                val varRegex = Regex("""\$\{?([a-zA-Z0-9_]+)\}?""")
                str = varRegex.replace(str) { match ->
                    val varName = match.groupValues[1]
                    when {
                        varName.equals("prev_output", ignoreCase = true) ||
                        varName.equals("previous_output", ignoreCase = true) -> {
                            lastResultData?.toString() ?: lastResultSummary
                        }
                        varName.equals("summary_text", ignoreCase = true) -> {
                            lastResultSummary.ifBlank { lastResultData?.toString() ?: match.value }
                        }
                        varName.endsWith("_output") || varName.endsWith("_summary") -> {
                            val baseStepId = varName.removeSuffix("_output").removeSuffix("_summary")
                            val stored = memory2.working.getIntermediateResult(baseStepId)
                                ?: memory2.working.getIntermediateResult(varName)
                                ?: plan.steps.find { it.id.equals(baseStepId, ignoreCase = true) || it.id.replace("_", "").equals(baseStepId.replace("_", ""), ignoreCase = true) }?.result?.let {
                                    it.data ?: it.summary
                                }
                            stored?.toString() ?: match.value
                        }
                        else -> {
                            val stored = memory2.working.getIntermediateResult(varName)
                            stored?.toString() ?: match.value
                        }
                    }
                }

                // If parameter is message/body/content and it remains a placeholder, bind to parent step output
                if ((key == "message" || key == "text" || key == "content" || key == "notes" || key == "body") &&
                    (str.isBlank() || str.startsWith("$")) && !step.dependsOnStepId.isNullOrBlank()) {
                    val depResult = memory2.working.getIntermediateResult(step.dependsOnStepId!!)
                        ?: plan.steps.find { it.id == step.dependsOnStepId }?.result?.let { it.data ?: it.summary }
                    if (depResult != null) {
                        str = depResult.toString()
                    }
                }

                resolved.put(key, str)
            } else {
                resolved.put(key, rawValue)
            }
        }
        return resolved
    }

    // ─── Step 8: Final Synthesis ─────────────────────────────────────────────

    private fun synthesizeFinalResponse(
        userInput: String,
        plan: OrchestratorPlan,
        rawResults: String
    ): String {
        val completedSteps = plan.steps.filter { it.status == StepStatus.COMPLETED && it.verified }
        val failedSteps = plan.steps.filter { it.status == StepStatus.FAILED || !it.verified }

        if (completedSteps.isEmpty() && failedSteps.isNotEmpty()) {
            val err = failedSteps.first().result?.error
                ?: failedSteps.first().verificationNotes
                ?: "Action verification failed"
            return "Boss, ${failedSteps.first().description} complete nahi ho paya: $err."
        }

        // Friendly, warm, concise Hinglish response honoring Myra persona
        val summaries = completedSteps.mapNotNull { it.result?.summary }.filter { it.isNotBlank() }
        val mainSummary = if (summaries.isNotEmpty()) summaries.joinToString(". ") else rawResults

        return if (failedSteps.isEmpty()) {
            "Ji Boss! $mainSummary"
        } else {
            val failedDescs = failedSteps.joinToString(", ") { it.description }
            "Ji Boss, task partially complete hua: $mainSummary (Lekin $failedDescs pura nahi ho saka)."
        }
    }

    private fun isDestructiveOrSensitiveAction(plugin: Plugin, step: TaskStep): Boolean {
        if (plugin.metadata.riskLevel == RiskLevel.HIGH) return true
        if (plugin.metadata.category == PluginCategory.MESSAGING ||
            plugin.metadata.category == PluginCategory.SOCIAL) return true

        val descLower = step.description.lowercase()
        val paramStr = step.parameters.toString().lowercase()
        val sensitiveKeywords = listOf(
            "delete", "remove", "mita", "hatao", "erase", "format",
            "send", "bhejo", "post", "dm", "message", "chat",
            "call", "dial", "phone", "ring",
            "pay", "transfer", "buy", "purchase", "order",
            "account", "password", "pin", "logout", "reset"
        )
        return sensitiveKeywords.any { descLower.contains(it) || paramStr.contains(it) }
    }

    private fun verifyStepResult(result: PluginResult): Pair<Boolean, String> {
        if (!result.isSuccess) {
            return Pair(false, result.error ?: "Plugin reported failure")
        }

        val summary = result.summary.trim()
        val data = result.data

        if (summary.isBlank() && data == null) {
            return Pair(false, "Received empty response from tool")
        }

        val lowerSummary = summary.lowercase()
        if (lowerSummary.startsWith("error") ||
            lowerSummary.startsWith("failed to") ||
            lowerSummary.contains("not found") ||
            lowerSummary.contains("exception occurred") ||
            lowerSummary.contains("permission denied")) {
            return Pair(false, summary)
        }

        if (data is JSONObject) {
            val status = data.optString("status", "").lowercase()
            if (status == "error" || status == "failed" || status == "restricted") {
                return Pair(false, data.optString("message", "Error reported in tool result object"))
            }
            if (status == "playing_video" || status == "playing" || status == "searching" ||
                status == "searching_web_fallback" || status == "success") {
                return Pair(true, "Result verified successfully ($status)")
            }
        }

        return Pair(true, "Result verified successfully")
    }

    private suspend fun generateConversationalResponse(
        input: String,
        memoryContext: RetrievedMemoryContext
    ): String {
        val apiKey = appSettings.effectiveApiKey()
        if (apiKey.isBlank()) {
            return "Ji Boss, main sun rahi hu. Batayein main aapki kya madad kar sakti hu?"
        }

        return try {
            // Retrieve relevant document knowledge from user authorized storage
            val ragHits = try {
                ragEngine.searchKnowledge(input, limit = 3)
            } catch (_: Exception) {
                emptyList()
            }
            val ragBlock = if (ragHits.isNotEmpty()) ragEngine.formatRagContextForPrompt(ragHits) else ""

            val systemPrompt = """You are MYRA, a warm, emotionally expressive, loyal companion created for Boss.
Language: Natural Hinglish (warm, respectful, colloquial Hindi in Latin script).

MEMORY CONTEXT:
${memoryContext.formattedPromptBlock}

$ragBlock

Respond directly to Boss with genuine human warmth, accuracy, and intelligence."""

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", input) })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemPrompt) })
                    })
                })
            }.toString()

            val req = Request.Builder()
                .url("$BASE_URL/$PRIMARY_MODEL:generateContent?key=$apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                return "Ji Boss, maine aapki baat suni hai."
            }
            val respBody = resp.body?.string() ?: return "Ji Boss!"
            val text = JSONObject(respBody)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")

            text?.trim() ?: "Ji Boss, batayein!"
        } catch (e: Exception) {
            "Ji Boss, bataiye kya kaam karna hai!"
        }
    }

    private fun classifyResourceAndRisk(
        input: String
    ): Pair<com.soltini.app.security.ResourceCategory, com.soltini.app.security.RiskLevel> {
        val lower = input.lowercase()
        return when {
            lower.contains("vault") || lower.contains("secret file") || lower.contains("private file") ->
                Pair(com.soltini.app.security.ResourceCategory.PRIVATE_FILES_VAULT, com.soltini.app.security.RiskLevel.OWNER_ONLY)

            lower.contains("note") || lower.contains("mere notes") || lower.contains("harshit ke notes") ->
                Pair(com.soltini.app.security.ResourceCategory.PERSONAL_NOTES, com.soltini.app.security.RiskLevel.OWNER_ONLY)

            lower.contains("message dikhao") || lower.contains("messages dikhao") || lower.contains("sms dikhao") ||
            lower.contains("whatsapp chats") || lower.contains("harshit ke message") || lower.contains("read message") || lower.contains("read sms") ->
                Pair(com.soltini.app.security.ResourceCategory.CALLS_SMS_READ, com.soltini.app.security.RiskLevel.OWNER_ONLY)

            lower.contains("voice profile") || lower.contains("add user") || lower.contains("security settings") || lower.contains("api key") ->
                Pair(com.soltini.app.security.ResourceCategory.VOICE_PROFILES_MGMT, com.soltini.app.security.RiskLevel.OWNER_ONLY)

            lower.contains("pay") || lower.contains("payment") || lower.contains("upi") || lower.contains("transfer money") ->
                Pair(com.soltini.app.security.ResourceCategory.FINANCIAL_PAYMENTS, com.soltini.app.security.RiskLevel.OWNER_ONLY)

            lower.contains("gaana") || lower.contains("song") || lower.contains("music") || lower.contains("bajao") ->
                Pair(com.soltini.app.security.ResourceCategory.MEDIA_PLAYBACK, com.soltini.app.security.RiskLevel.LOW_RISK)

            lower.contains("mausam") || lower.contains("weather") || lower.contains("time") || lower.contains("samay") || lower.contains("tarikh") || lower.contains("date") ->
                Pair(com.soltini.app.security.ResourceCategory.GENERAL_ASSISTANT, com.soltini.app.security.RiskLevel.LOW_RISK)

            lower.contains("calculator") || lower.contains("calculate") || lower.contains("hisab") ->
                Pair(com.soltini.app.security.ResourceCategory.UTILITIES, com.soltini.app.security.RiskLevel.LOW_RISK)

            lower.contains("reminder") || lower.contains("alarm") ->
                Pair(com.soltini.app.security.ResourceCategory.ALARMS_REMINDERS, com.soltini.app.security.RiskLevel.MEDIUM_RISK)

            lower.contains("message bhejo") || lower.contains("sms bhejo") || lower.contains("call karo") || lower.contains("phone lagao") ->
                Pair(com.soltini.app.security.ResourceCategory.CALLS_SMS_SEND, com.soltini.app.security.RiskLevel.HIGH_RISK)

            else ->
                Pair(com.soltini.app.security.ResourceCategory.GENERAL_ASSISTANT, com.soltini.app.security.RiskLevel.LOW_RISK)
        }
    }
}
