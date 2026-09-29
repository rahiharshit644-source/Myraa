package com.soltini.app.services

import android.app.AlarmManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.soltini.app.MainActivity
import androidx.core.app.NotificationCompat
import com.soltini.app.BootReceiver
import com.soltini.app.agent.AgentToolExecutor
import com.soltini.app.agent.BrowserUseClient
import com.soltini.app.audio.AudioPlayer
import com.soltini.app.audio.AudioRecorder
import com.soltini.app.network.ConnectionState
import com.soltini.app.network.GeminiLiveListener
import com.soltini.app.network.GeminiLiveManager
import com.soltini.app.network.ToolCallListener
import com.soltini.app.settings.AppSettings
import com.soltini.app.ui.TranscriptEntry
import com.soltini.app.overlay.OverlayService
import com.soltini.app.homeautomation.control.DeviceController
import com.soltini.app.homeautomation.mqtt.MqttManager
import com.soltini.app.homeautomation.data.WifiMqttCredentialsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import com.soltini.app.notifications.NotificationRepository
import com.soltini.app.notifications.ReplyResult
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BackgroundVoiceService
 *
 * Core foreground service that:
 *   1. Maintains the persistent Gemini Live WebSocket connection.
 *   2. Routes microphone audio to the WebSocket.
 *   3. Plays received audio from Gemini.
 *   4. Implements ToolCallListener to execute device actions when Gemini requests them.
 *   5. Manages sleep / wake state (disconnects Gemini on sleep, stays alive for re-trigger).
 */
class BackgroundVoiceService : Service(), GeminiLiveListener, ToolCallListener {

    companion object {
        private const val TAG = "BackgroundVoiceService"
        private const val CHANNEL_ID = "VoiceSessionChannel"
        private const val NOTIFICATION_ID = 2

        @Volatile
        private var instance: BackgroundVoiceService? = null

        fun getInstance(): BackgroundVoiceService? = instance
        fun isRunning(): Boolean = instance != null
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    // SupervisorJob: one failing coroutine does NOT cancel all sibling coroutines
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── User-configurable AI settings ────────────────────────────────────────
    lateinit var appSettings: AppSettings
        private set

    // ── Persistent vector memory ─────────────────────────────────────────────
    lateinit var memory2Engine: com.soltini.app.memory2.Memory2Engine
        private set
    lateinit var pluginRegistry: com.soltini.app.plugins.PluginRegistry
        private set
    lateinit var myraOrchestrator: com.soltini.app.orchestrator.MyraOrchestrator
        private set

    /**
     * Counts how many tool calls are currently in-flight.
     * Accessibility high-friction tracking is enabled while this is > 0
     * and disabled when it drops back to 0, preventing stale tracking state.
     */
    private val activeToolCallCount = AtomicInteger(0)

    private val audioPlayer = AudioPlayer()
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var geminiLiveManager: GeminiLiveManager
    private lateinit var agentToolExecutor: AgentToolExecutor
    private val browserUseClient: BrowserUseClient by lazy { BrowserUseClient(this) }
    private lateinit var backgroundAgentRunner: com.soltini.app.agent.BackgroundAgentRunner
    private lateinit var agentPhoneController: com.soltini.app.agent.AgentPhoneController
    private lateinit var deviceController: DeviceController
    private lateinit var mqttManager: MqttManager

    private val _hasMicPermission = MutableStateFlow(false)
    val hasMicPermission: StateFlow<Boolean> = _hasMicPermission.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSleeping = MutableStateFlow(false)
    val isSleeping: StateFlow<Boolean> = _isSleeping.asStateFlow()

    /**
     * Lite Pause — mic/WS off, accessibility service silenced but still listed.
     * Works for most banking apps. No re-enable needed.
     */
    private val _isLitePaused = MutableStateFlow(false)
    val isLitePaused: StateFlow<Boolean> = _isLitePaused.asStateFlow()

    /**
     * Hard Pause — mic/WS off + disableSelf() on accessibility service.
     * For strict banking apps (HDFC, ICICI, etc.). Requires one manual re-enable.
     */
    private val _isHardPaused = MutableStateFlow(false)
    val isHardPaused: StateFlow<Boolean> = _isHardPaused.asStateFlow()

    /** True if either pause mode is active (convenience for UI) */
    val isPaused: StateFlow<Boolean> get() = _isLitePaused  // kept for compat — see combined below

    private val _transcripts = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcripts: StateFlow<List<TranscriptEntry>> = _transcripts.asStateFlow()
    private var currentGeminiTranscript = StringBuilder()
    private var currentTurnGeminiEntryId: String? = null
    private var lastSpeechTimestamp = System.currentTimeMillis()
    private var pendingCallPrompt: String? = null

    // Expose flows from the child components
    lateinit var connectionState: StateFlow<ConnectionState>
    lateinit var errorMessage: StateFlow<String?>
    lateinit var micAmplitude: StateFlow<Float>
    lateinit var speakerAmplitude: StateFlow<Float>
    lateinit var isSpeaking: StateFlow<Boolean>

    // Zero-battery MIUI bypass
    private var silentMediaPlayer: MediaPlayer? = null

    inner class LocalBinder : Binder() {
        fun getService(): BackgroundVoiceService = this@BackgroundVoiceService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "BackgroundVoiceService created")

        // Start hardware-offloaded silent audio to bypass MIUI/OEM killers with 0% battery drain
        startZeroBatterySilentAudioTrick()

        // ── FIRST: Call startForeground immediately ─────────────────────────────
        createNotificationChannel()
        startForegroundService()

        // ── WakeLock with auto-renewal and watchdog loop ──────────────────────────
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Soltini::VoiceWakeLock").apply {
            setReferenceCounted(false)
            try {
                acquire(10 * 60 * 1000L)
            } catch (_: Exception) {}
        }

        // WakeLock auto-renewal + background health watchdog
        serviceScope.launch {
            while (isActive) {
                delay(4 * 60 * 1000L) // renew every 4 minutes before any 10-minute expiry
                if (!_isSleeping.value && !_isLitePaused.value && !_isHardPaused.value) {
                    try {
                        wakeLock?.acquire(10 * 60 * 1000L)
                        Log.d(TAG, "WakeLock refreshed")
                    } catch (e: Exception) {
                        Log.w(TAG, "WakeLock renewal failed: ${e.message}")
                    }

                    // Keep silent audio loop active for zero-battery protection
                    try {
                        if (silentMediaPlayer == null || silentMediaPlayer?.isPlaying == false) {
                            Log.d(TAG, "Restarting silent media player in background")
                            startZeroBatterySilentAudioTrick()
                        }
                    } catch (_: Exception) {}

                    // Keep Gemini Live connection alive if dropped in background
                    if (connectionState.value == ConnectionState.Disconnected && _hasMicPermission.value) {
                        Log.i(TAG, "Heartbeat: Reconnecting Gemini Live session in background...")
                        connect()
                    }
                }
            }
        }

        // ── User-configurable settings + memory ──────────────────────────────
        appSettings = AppSettings(this)
        memory2Engine = com.soltini.app.memory2.Memory2Engine.getInstance(this, appSettings)
        pluginRegistry = com.soltini.app.plugins.PluginRegistry.getInstance(this)

        agentToolExecutor = AgentToolExecutor(this)
        com.soltini.app.plugins.BuiltinPluginInitializer.initializeAll(this, pluginRegistry, agentToolExecutor, appSettings)
        myraOrchestrator = com.soltini.app.orchestrator.MyraOrchestrator.getInstance(this, memory2Engine, pluginRegistry, appSettings)

        // Preload persistent conversation history across app restarts
        serviceScope.launch(Dispatchers.IO) {
            try {
                val savedRecords = memory2Engine.db.getAllConversationEntries(limit = 100)
                if (savedRecords.isNotEmpty()) {
                    val entries = savedRecords.map { rec ->
                        val sender = if (rec.sender == "USER") TranscriptEntry.Sender.USER else TranscriptEntry.Sender.GEMINI
                        TranscriptEntry(
                            id = rec.id,
                            sender = sender,
                            text = rec.text,
                            timestamp = rec.timestamp
                        )
                    }
                    _transcripts.value = entries
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not preload conversation history: ${e.message}")
            }
        }

        // Background knowledge re-indexing on startup + periodic sync for authorized SAF folders
        serviceScope.launch(Dispatchers.IO) {
            val storageManager = com.soltini.app.storage.SafStorageManager(this@BackgroundVoiceService)
            val ragEngine = com.soltini.app.rag.RagKnowledgeEngine.getInstance(this@BackgroundVoiceService, appSettings)
            while (isActive) {
                try {
                    val authorized = storageManager.getAuthorizedLocations()
                    if (authorized.isNotEmpty()) {
                        Log.i(TAG, "Running background RAG knowledge re-index for ${authorized.size} authorized folder(s)...")
                        ragEngine.indexAllAuthorizedFolders(storageManager)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Background RAG folder indexing error: ${e.message}")
                }
                delay(30 * 60 * 1000L) // Periodic check every 30 minutes
            }
        }

        // Habit-based proactive routine scheduling (immediate scan + 12h recurring WorkManager job)
        try {
            com.soltini.app.automation.ProactiveRoutineScheduler.initPeriodicHabitAnalysis(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed initializing proactive habit scheduler: ${e.message}")
        }

        audioRecorder = AudioRecorder(
            onAudioChunkCaptured = { pcmChunk ->
                if (!_isSleeping.value) {
                    geminiLiveManager.sendAudioChunk(pcmChunk)
                }
            },
            onSpeechStateChanged = {
                lastSpeechTimestamp = System.currentTimeMillis()
            }
        ).apply {
            isDataSaverEnabled = appSettings.isDataSaverEnabled
            vadThreshold = appSettings.vadThreshold
            micGain = appSettings.micGain
            isScreenRecordingModeEnabled = appSettings.isScreenRecordingModeEnabled
        }

        // Pass 'this' as both GeminiLiveListener and ToolCallListener
        geminiLiveManager = GeminiLiveManager(
            listener = this,
            toolCallListener = this,
            appSettings = appSettings,
            memory2Engine = memory2Engine,
            pluginRegistry = pluginRegistry
        )

        backgroundAgentRunner = com.soltini.app.agent.BackgroundAgentRunner(this, agentToolExecutor, appSettings)
        agentPhoneController = com.soltini.app.agent.AgentPhoneController(this, agentToolExecutor, agentToolExecutor.screenOperator, appSettings)
        deviceController = DeviceController.create(this)
        mqttManager = MqttManager.getInstance(this)

        if (WifiMqttCredentialsStore(this).isMqttConfigured()) {
            mqttManager.connect()
        }

        // Screen Companion Hook: When proactive insight is triggered, deliver to Gemini Live session
        com.soltini.app.companion.ScreenCompanionManager.getInstance(this).onProactiveInsightReady = { insightPrompt ->
            if (!_isSleeping.value && geminiLiveManager.connectionState.value == ConnectionState.Live) {
                geminiLiveManager.sendClientContent(insightPrompt)
            }
        }

        // Phone Call Handling Hook: Announce incoming calls aloud to Boss and handle voice control
        val callNotificationManager = com.soltini.app.telephony.CallNotificationManager.getInstance(this)
        callNotificationManager.startListening()
        callNotificationManager.onIncomingCallDetected = { callerName, phoneNumber ->
            Log.i(TAG, "Incoming call detected in BackgroundVoiceService: $callerName ($phoneNumber)")
            // User requirement: When data is OFF, do NOT ask in robotic voice! Only when online, Myra herself asks.
            val isOnline = isNetworkConnected()
            if (!isOnline) {
                Log.i(TAG, "Mobile data/internet is OFF during incoming call: suppressing robotic TTS announcement completely as requested.")
            } else {
                val callPrompt = """[URGENT INCOMING PHONE CALL]
Caller: "$callerName" (${phoneNumber ?: "Unknown number"}).
Boss ke phone par call aa raha hai!
Turant Boss ko bolo:
"Boss, $callerName ka call aa raha hai, aap bataiye kya karna hai — uthana hai ya reject karna hai?"
Aur Boss jo bolein:
- Agar Boss bolein "utha lo", "answer karo", "haan receive karo", "phone uthao": execute tool `answer_call`.
- Agar Boss bolein "kaat do", "reject karo", "mat uthao", "phone kaat de": execute tool `reject_call`.
Speak now immediately!"""

                if (!_isSleeping.value && geminiLiveManager.connectionState.value == ConnectionState.Live) {
                    geminiLiveManager.sendClientContent(callPrompt)
                } else {
                    // Mobile data is ON: store prompt and wake/connect so Myra speaks with her natural voice (never robotic TTS)
                    pendingCallPrompt = callPrompt
                    if (_isSleeping.value) {
                        wakeUp()
                    } else if (geminiLiveManager.connectionState.value != ConnectionState.Live) {
                        connect()
                    }
                }
            }
        }

        connectionState = geminiLiveManager.connectionState
        errorMessage = geminiLiveManager.errorMessage
        micAmplitude = audioRecorder.micAmplitude
        speakerAmplitude = audioPlayer.speakerAmplitude
        isSpeaking = audioPlayer.isSpeaking

        serviceScope.launch {
            connectionState.collect { state ->
                when (state) {
                    ConnectionState.Live -> {
                        if (appSettings.isScreenRecordingModeEnabled) {
                            try {
                                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                am.mode = AudioManager.MODE_IN_COMMUNICATION
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to set MODE_IN_COMMUNICATION: ${e.message}")
                            }
                        }

                        lastSpeechTimestamp = System.currentTimeMillis()
                        if (_hasMicPermission.value) {
                            audioRecorder.startRecording()
                        }
                        audioPlayer.start()
                        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.LISTENING)

                        // If an incoming call arrived while connecting, ask Boss immediately in natural voice
                        pendingCallPrompt?.let { prompt ->
                            Log.i(TAG, "Myra connected to Gemini Live: delivering pending incoming call prompt hands-free!")
                            geminiLiveManager.sendClientContent(prompt)
                            pendingCallPrompt = null
                        }
                    }
                    ConnectionState.Disconnected, ConnectionState.Error -> {
                        try {
                            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            am.mode = AudioManager.MODE_NORMAL
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to restore MODE_NORMAL: ${e.message}")
                        }

                        audioRecorder.stopRecording()
                        val ringState = if (_isSleeping.value) com.soltini.app.overlay.RingState.SLEEPING else com.soltini.app.overlay.RingState.IDLE
                        OverlayService.getInstance()?.setRingState(ringState)
                    }
                    else -> {}
                }
            }
        }

        // Keep OverlayService synced with amplitude
        serviceScope.launch {
            speakerAmplitude.collect { amp ->
                OverlayService.getInstance()?.setMouthAmplitude(amp)
            }
        }
        serviceScope.launch {
            isSpeaking.collect { isSpeaking ->
                audioRecorder.isModelSpeaking = isSpeaking
                if (isSpeaking) {
                    lastSpeechTimestamp = System.currentTimeMillis()
                }
                OverlayService.getInstance()?.setTalking(isSpeaking)
                if (connectionState.value == ConnectionState.Live) {
                    if (isSpeaking) {
                        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.SPEAKING)
                    } else {
                        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.LISTENING)
                    }
                }
            }
        }

        // Auto-connect if restarted by system (e.g. after swipe from recents)
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            _hasMicPermission.value = true
            if (connectionState.value == ConnectionState.Disconnected) {
                connect()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (_isSleeping.value && !_isLitePaused.value && !_isHardPaused.value) {
            wakeUp()
        }
        if (!_isSleeping.value && !_isLitePaused.value && !_isHardPaused.value) {
            if (_hasMicPermission.value && connectionState.value == ConnectionState.Disconnected) {
                connect()
            }
        }
        // Trigger habit analysis scan on service start
        serviceScope.launch(Dispatchers.IO) {
            try {
                com.soltini.app.automation.ProactiveRoutineScheduler.analyzeHabitsAndSchedule(
                    this@BackgroundVoiceService,
                    memory2Engine.db
                )
            } catch (_: Exception) {}
        }
        // START_STICKY: Instructs Android OS to automatically recreate this service
        // if killed by low memory killer or background process reclamation.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (_isLitePaused.value || _isHardPaused.value) {
            Log.i(TAG, "Task removed while PAUSED — shutting down completely and not auto-restarting")
            
            // Fully shut down everything since the user swiped away while paused
            audioRecorder.stopRecording()
            audioPlayer.stop()
            geminiLiveManager.disconnect()
            wakeLock?.let { if (it.isHeld) it.release() }
            
            com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = false
            OverlayService.stop(this)
            stopSelf()
        } else {
            Log.i(TAG, "Task removed (swiped from recents) — scheduling AlarmManager to restart service")
            try {
                val voiceIntent = Intent(applicationContext, BackgroundVoiceService::class.java)
                val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(
                        applicationContext,
                        1001,
                        voiceIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                } else {
                    PendingIntent.getService(
                        applicationContext,
                        1001,
                        voiceIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 1000,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 1000,
                        pendingIntent
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule resurrection alarm: ${e.message}", e)
            }
        }
        
        super.onTaskRemoved(rootIntent)
    }

    private fun startZeroBatterySilentAudioTrick() {
        try {
            // Generate a tiny 1-second silent WAV file
            val silentFile = File(cacheDir, "zero_battery_silence.wav")
            if (!silentFile.exists()) {
                val sampleRate = 8000
                val channels = 1
                val bitsPerSample = 16
                val byteRate = sampleRate * channels * bitsPerSample / 8
                val totalDataLen = sampleRate * 2 // 1 second of audio
                val totalAudioLen = totalDataLen
                val totalDataLenWithHeader = totalDataLen + 36

                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray())
                header.putInt(totalDataLenWithHeader)
                header.put("WAVE".toByteArray())
                header.put("fmt ".toByteArray())
                header.putInt(16) // Subchunk1Size
                header.putShort(1.toShort()) // AudioFormat (PCM)
                header.putShort(channels.toShort())
                header.putInt(sampleRate)
                header.putInt(byteRate)
                header.putShort((channels * bitsPerSample / 8).toShort()) // BlockAlign
                header.putShort(bitsPerSample.toShort())
                header.put("data".toByteArray())
                header.putInt(totalAudioLen)

                FileOutputStream(silentFile).use { fos ->
                    fos.write(header.array())
                    fos.write(ByteArray(totalAudioLen)) // zeroes
                }
            }

            try {
                silentMediaPlayer?.release()
            } catch (_: Exception) {}

            silentMediaPlayer = MediaPlayer().apply {
                setDataSource(silentFile.absolutePath)
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setVolume(0f, 0f)
                isLooping = true
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "Silent media player error ($what, $extra), restarting...")
                    try {
                        release()
                    } catch (_: Exception) {}
                    serviceScope.launch {
                        delay(2000)
                        startZeroBatterySilentAudioTrick()
                    }
                    true
                }
                prepare()
                start()
            }
            Log.i(TAG, "Zero-battery silent audio trick activated.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start silent media player: ${e.message}")
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun onPermissionResult(granted: Boolean) {
        _hasMicPermission.value = granted
        if (granted) {
            // Re-call startForegroundService now that we have permission, so the OS adds the microphone FGS type
            startForegroundService()
            if (connectionState.value == ConnectionState.Live) {
                audioRecorder.startRecording()
            }
        }
    }

    fun connect() {
        if (isPaused.value) {
            Log.i(TAG, "connect() ignored because service is paused.")
            return
        }
        _isSleeping.value = false
        geminiLiveManager.connect()
    }

    /**
     * Disconnects the current session and immediately opens a fresh one.
     * Used by the "Save & Reconnect" button in AI Configuration settings
     * so new API key / persona / voice take effect without restarting.
     */
    fun reconnect() {
        Log.i(TAG, "Reconnecting to apply new AI settings")
        audioRecorder.isDataSaverEnabled = appSettings.isDataSaverEnabled
        audioRecorder.vadThreshold = appSettings.vadThreshold
        audioRecorder.micGain = appSettings.micGain
        audioRecorder.isScreenRecordingModeEnabled = appSettings.isScreenRecordingModeEnabled
        audioRecorder.stopRecording()
        audioPlayer.stop()
        geminiLiveManager.disconnect()
        // Small gap to let WebSocket close cleanly before re-opening
        serviceScope.launch {
            kotlinx.coroutines.delay(500)
            connect()
        }
    }

    fun disconnect() {
        audioRecorder.stopRecording()
        audioPlayer.stop()
        geminiLiveManager.disconnect()
    }

    fun toggleMute() {
        val newMute = !_isMuted.value
        _isMuted.value = newMute
        audioRecorder.isMuted = newMute
    }

    fun clearTranscript() {
        _transcripts.value = emptyList()
        currentGeminiTranscript.clear()
        currentTurnGeminiEntryId = null
        serviceScope.launch(Dispatchers.IO) {
            memory2Engine.db.clearConversationHistory()
        }
    }

    fun appendUserMessage(text: String): TranscriptEntry {
        val entry = TranscriptEntry(
            sender = TranscriptEntry.Sender.USER,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        val list = _transcripts.value.toMutableList()
        list.add(entry)
        _transcripts.value = list
        serviceScope.launch(Dispatchers.IO) {
            memory2Engine.db.saveConversationEntry(entry.id, entry.sender.name, entry.text, entry.timestamp)
        }
        return entry
    }

    fun appendAgentMessage(text: String): TranscriptEntry {
        val entry = TranscriptEntry(
            sender = TranscriptEntry.Sender.GEMINI,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        val list = _transcripts.value.toMutableList()
        list.add(entry)
        _transcripts.value = list
        serviceScope.launch(Dispatchers.IO) {
            memory2Engine.db.saveConversationEntry(entry.id, entry.sender.name, entry.text, entry.timestamp)
        }
        return entry
    }

    fun processUserTextCommand(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        appendUserMessage(trimmed)
        serviceScope.launch(Dispatchers.IO) {
            try {
                val orchestratorResult = myraOrchestrator.processRequest(trimmed)
                appendAgentMessage(orchestratorResult.responseText)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing text command: ${e.message}", e)
                appendAgentMessage("Error executing command: ${e.message}")
            }
        }
    }

    // ── Lite Pause (default) ─────────────────────────────────────────────────
    /**
     * Lite Pause Mode:
     *   • Stops mic + WebSocket (0 data while paused)
     *   • Sets isPausedLite = true on the accessibility service, making it
     *     completely silent: onAccessibilityEvent returns immediately,
     *     no window reads — functionally invisible to most banking apps
     *     (GPay, PhonePe, Paytm, most bank apps check for active monitoring,
     *     not just whether the service is listed).
     *   • Does NOT call disableSelf() — so NO re-enable is needed on resume.
     *   • Resume is instant: mic + WebSocket reconnect automatically.
     */
    fun enterLitePauseMode() {
        if (_isLitePaused.value || _isHardPaused.value) return
        Log.i(TAG, "Entering Lite Pause — silencing accessibility without disabling")
        _isLitePaused.value = true
        _isSleeping.value = true

        audioRecorder.stopRecording()
        audioPlayer.stop()
        geminiLiveManager.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }

        // Silence the service without disabling it
        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isPausedLite = true
        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = false

        // Completely hide the orb overlay while paused
        OverlayService.getInstance()?.setHiddenForBanking(true)
        updateNotification("⏸ Lite Pause — tap to resume")
    }

    fun exitLitePauseMode() {
        if (!_isLitePaused.value) return
        Log.i(TAG, "Exiting Lite Pause")
        _isLitePaused.value = false
        _isSleeping.value = false

        // Un-silence the accessibility service
        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isPausedLite = false

        if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
        // Show the orb overlay again
        OverlayService.getInstance()?.setHiddenForBanking(false)
        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.IDLE)
        updateNotification("Your AI Assistant is ready")
        connect()
    }

    fun toggleLitePauseMode() {
        if (_isLitePaused.value) exitLitePauseMode() else enterLitePauseMode()
    }

    // ── Hard Pause (strict banks) ─────────────────────────────────────────────
    /**
     * Hard Pause Mode:
     *   • Stops mic + WebSocket
     *   • Calls disableSelf() — removes Soltini from Android's enabled-services
     *     list entirely. Even the most strict banking apps (HDFC, ICICI) will
     *     not find it.
     *   • On resume, automatically opens the accessibility settings screen so the
     *     user only needs ONE tap to re-enable (instead of hunting through menus).
     */
    fun enterHardPauseMode() {
        if (_isHardPaused.value || _isLitePaused.value) return
        Log.i(TAG, "Entering Hard Pause (Force Stop) — opening App Info to self-terminate")
        _isHardPaused.value = true
        _isSleeping.value = true

        audioRecorder.stopRecording()
        audioPlayer.stop()
        geminiLiveManager.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }

        // Tell the accessibility service to look for and click the Force Stop button
        val a11yService = com.soltini.app.agent.SoltiniAccessibilityService.getInstance()
        if (a11yService != null) {
            a11yService.isAutomatingForceStop = true

            // Launch the App Info settings screen for this app
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } else {
            // Fallback if accessibility is dead
            stopSelf()
        }

        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.SLEEPING)
        updateNotification("🔴 Force Stopped — open app to resume")
    }

    fun exitHardPauseMode() {
        if (!_isHardPaused.value) return
        Log.i(TAG, "Exiting Hard Pause (Force Stop mode)")
        _isHardPaused.value = false
        _isSleeping.value = false

        if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.IDLE)
        updateNotification("Your AI Assistant is ready")

        // Accessibility is automatically rebound when the app is manually launched after Force Stop
        connect()
    }

    fun toggleHardPauseMode() {
        if (_isHardPaused.value) exitHardPauseMode() else enterHardPauseMode()
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open accessibility settings: ${e.message}")
        }
    }

    // Legacy compat — togglePauseMode now triggers Lite Pause by default
    fun enterPauseMode() = enterLitePauseMode()
    fun exitPauseMode() = if (_isLitePaused.value) exitLitePauseMode() else exitHardPauseMode()
    fun togglePauseMode() = toggleLitePauseMode()

    /**
     * Puts Soltini into 100% sleep:
     *   - Stops microphone recording (AudioRecord released)
     *   - Stops audio playback (AudioTrack released)
     *   - Fully disconnects Gemini WebSocket + shuts down OkHttp client
     *   - Releases the WakeLock so the CPU can actually sleep
     *   - Disables accessibility tool-call flag (no event processing overhead)
     *
     * The foreground service stays alive (notification visible) so Android keeps
     * the process ready for the next wakeup tap on the orb.
     */
    fun sleep() {
        Log.i(TAG, "Soltini going to sleep — full power-down")
        _isSleeping.value = true

        // Stop all audio hardware
        audioRecorder.stopRecording()
        audioPlayer.stop()

        // Cut the WebSocket and shut down the OkHttp thread pool
        geminiLiveManager.disconnect()

        // Release WakeLock — CPU can now truly sleep between orb taps
        wakeLock?.let { if (it.isHeld) it.release() }

        // Disable accessibility event processing — no overhead while idle
        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = false

        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.SLEEPING)
    }

    /**
     * Wakes Soltini from sleep:
     *   - Reacquires a fresh WakeLock (10-min timeout)
     *   - Reconnects to Gemini Live WebSocket
     */
    fun wakeUp() {
        Log.i(TAG, "Soltini waking up from sleep")
        _isSleeping.value = false

        // Reacquire WakeLock so the CPU stays on during voice session
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L)
        }

        connect()
    }

    /**
     * Toggles between sleep and awake states.
     * Called when the user taps the floating orb while the agent is sleeping.
     */
    fun toggleSleep() {
        if (_isSleeping.value) wakeUp() else sleep()
    }

    // ─── ToolCallListener Implementation ─────────────────────────────────────

    /**
     * Called on the main thread when Gemini sends a toolCall message.
     * We execute the action and send the result back to Gemini.
     *
     * Uses an atomic counter instead of a plain boolean flag so that concurrent
     * tool calls can each independently increment/decrement without clobbering each
     * other. High-friction accessibility tracking is on only while count > 0.
     */
    override fun onToolCall(callId: String, toolName: String, args: JSONObject) {
        Log.i(TAG, "Tool call received: $toolName (callId=$callId) | args: $args")

        // ─── Storage Intelligence, File Manager & RAG ─────────────────────────
        if (toolName == "manage_files" || toolName == "file_manager") {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val plugin = pluginRegistry.getPlugin("file_manager_service")
                    val ctx = com.soltini.app.plugins.PluginContext(
                        context = applicationContext,
                        parameters = args
                    )
                    val res = plugin?.execute(ctx)
                    val respJson = JSONObject().apply {
                        put("status", if (res?.isSuccess == true) "success" else "error")
                        put("summary", res?.summary ?: "File operation finished")
                        put("data", res?.data ?: JSONObject())
                    }
                    geminiLiveManager.sendToolResponse(callId, toolName, respJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing manage_files: ${e.message}", e)
                    val errJson = JSONObject().apply {
                        put("status", "error")
                        put("message", "File operation failed: ${e.message}")
                    }
                    geminiLiveManager.sendToolResponse(callId, toolName, errJson)
                }
            }
            return
        }

        if (toolName == "control_device") {
            val deviceName = args.optString("device_name", "")
            val action = args.optString("action", "TOGGLE")
            Log.i(TAG, "Smart Home control_device: deviceName='$deviceName', action='$action'")

            serviceScope.launch(Dispatchers.IO) {
                try {
                    val resultJson = deviceController.executeControl(callId, deviceName, action)
                    geminiLiveManager.sendToolResponse(callId, toolName, resultJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing control_device: ${e.message}", e)
                    val errorJson = JSONObject().apply {
                        put("status", "error")
                        put("message", "Failed to execute device control: ${e.message}")
                    }
                    geminiLiveManager.sendToolResponse(callId, toolName, errorJson)
                }
            }
            return
        }

        if (toolName == "read_notifications") {
            val notifRepo = NotificationRepository.getInstance(applicationContext)
            if (!notifRepo.isNotificationAccessGranted()) {
                geminiLiveManager.sendToolResponse(
                    callId, toolName,
                    JSONObject().apply {
                        put("status", "permission_needed")
                        put("message", "Notification access permission is not enabled. Ask the user to enable Notification Access in the app Settings so you can read notifications.")
                    }
                )
                return
            }

            val limit = args.optInt("limit", 5)
            val appFilter = args.optString("app_filter", "").takeIf { it.isNotBlank() }
            val list = notifRepo.getRecentNotifications(limit = limit, appFilter = appFilter)

            if (list.isEmpty()) {
                geminiLiveManager.sendToolResponse(
                    callId, toolName,
                    JSONObject().apply {
                        put("status", "empty")
                        put("message", if (appFilter != null) "No recent notifications found for $appFilter." else "No new notifications found right now.")
                    }
                )
            } else {
                val array = JSONArray()
                list.forEach { notif ->
                    array.put(JSONObject().apply {
                        put("app", notif.appName)
                        put("sender", notif.title)
                        put("message", notif.text)
                        put("time_ago", notif.getTimeAgo())
                        put("can_reply", notif.canReply)
                    })
                }
                geminiLiveManager.sendToolResponse(
                    callId, toolName,
                    JSONObject().apply {
                        put("status", "success")
                        put("count", list.size)
                        put("notifications", array)
                        put("instruction", "Read these notifications aloud conversationally, mentioning the app, sender, and message. Ask if they want to reply to any of them.")
                    }
                )
            }
            return
        }

        if (toolName == "reply_to_notification") {
            val notifRepo = NotificationRepository.getInstance(applicationContext)
            if (!notifRepo.isNotificationAccessGranted()) {
                geminiLiveManager.sendToolResponse(
                    callId, toolName,
                    JSONObject().apply {
                        put("status", "permission_needed")
                        put("message", "Notification access permission is not enabled.")
                    }
                )
                return
            }

            val recipient = args.optString("recipient_or_app", "")
            val replyMessage = args.optString("reply_message", "")

            val result = notifRepo.replyToNotification(recipient, replyMessage)
            when (result) {
                is ReplyResult.Success -> {
                    geminiLiveManager.sendToolResponse(
                        callId, toolName,
                        JSONObject().apply {
                            put("status", "success")
                            put("message", "Sent reply to ${result.recipient} on ${result.appName}: \"${result.replyText}\". Inform the user that the reply was sent successfully.")
                        }
                    )
                }
                is ReplyResult.Failure -> {
                    geminiLiveManager.sendToolResponse(
                        callId, toolName,
                        JSONObject().apply {
                            put("status", "error")
                            put("message", result.reason)
                        }
                    )
                }
            }
            return
        }

        if (toolName == "manage_ignored_apps") {
            val notifRepo = NotificationRepository.getInstance(applicationContext)
            val action = args.optString("action", "LIST").uppercase()
            val appName = args.optString("app_name", "").trim()

            when (action) {
                "IGNORE" -> {
                    if (appName.isBlank()) {
                        geminiLiveManager.sendToolResponse(callId, toolName, JSONObject().apply {
                            put("status", "error")
                            put("message", "Please specify which app you want to ignore.")
                        })
                    } else {
                        notifRepo.ignoreApp(appName)
                        geminiLiveManager.sendToolResponse(callId, toolName, JSONObject().apply {
                            put("status", "success")
                            put("message", "Added $appName to ignored notifications list. Notifications from $appName will no longer be read.")
                        })
                    }
                }
                "UNIGNORE" -> {
                    if (appName.isBlank()) {
                        geminiLiveManager.sendToolResponse(callId, toolName, JSONObject().apply {
                            put("status", "error")
                            put("message", "Please specify which app you want to unignore.")
                        })
                    } else {
                        val removed = notifRepo.unignoreApp(appName)
                        geminiLiveManager.sendToolResponse(callId, toolName, JSONObject().apply {
                            put("status", "success")
                            put("message", if (removed) "Removed $appName from ignored list. Its notifications will be read now." else "$appName was not in the ignored list.")
                        })
                    }
                }
                else -> {
                    val ignoredSet = notifRepo.getIgnoredApps()
                    geminiLiveManager.sendToolResponse(callId, toolName, JSONObject().apply {
                        put("status", "success")
                        put("ignored_apps", JSONArray(ignoredSet.toList()))
                        put("message", if (ignoredSet.isEmpty()) "No apps are currently ignored. Soltini reads notifications from all apps." else "Currently ignored apps: ${ignoredSet.joinToString(", ")}")
                    })
                }
            }
            return
        }

        if (toolName == "observe_screen") {
            val queryFocus = args.optString("query_focus", "")
            serviceScope.launch(Dispatchers.IO) {
                val companionManager = com.soltini.app.companion.ScreenCompanionManager.getInstance(applicationContext)
                val snapshot = companionManager.captureCurrentScreenWithVision(queryFocus)

                if (!snapshot.isAvailable) {
                    geminiLiveManager.sendToolResponse(callId, toolName, JSONObject().apply {
                        put("status", "unavailable")
                        put("message", snapshot.errorMessage ?: "Screen is not accessible right now.")
                    })
                } else if (snapshot.isSecure) {
                    geminiLiveManager.sendToolResponse(callId, toolName, JSONObject().apply {
                        put("status", "secure_screen")
                        put("message", "Screen viewing is safely paused because a banking, payment, or secure app is currently visible.")
                    })
                } else {
                    // Send visual frame to Gemini Live WebSocket multimodal stream
                    if (!snapshot.base64Jpeg.isNullOrBlank()) {
                        geminiLiveManager.sendImageFrame(snapshot.base64Jpeg)
                    }

                    val summary = snapshot.toHumanSummary()
                    geminiLiveManager.sendToolResponse(callId, toolName, JSONObject().apply {
                        put("status", "success")
                        put("app", snapshot.currentApp)
                        put("summary", summary)
                        put("typing_now", JSONArray(snapshot.userTypingTexts))
                        put("visible_items", JSONArray(snapshot.visibleTexts.take(15)))
                        if (snapshot.visualSummary.isNotBlank()) {
                            put("visual_vision_details", snapshot.visualSummary)
                        }
                        put("instruction", "You are looking directly at Boss's phone screen with both visual eyes (Gemini Vision) and accessibility inspection. If query_focus was '$queryFocus', answer it directly with high accuracy. Speak in a warm, sweet, human-like voice (natural conversational Hinglish). Describe what is visually seen, including photos, products, colors, graphs, or icons.")
                    })
                }
            }
            return
        }

        if (toolName == "toggle_screen_companion") {
            val enable = args.optBoolean("enabled", true)
            val companionManager = com.soltini.app.companion.ScreenCompanionManager.getInstance(applicationContext)
            companionManager.setLiveCompanionEnabled(enable)

            geminiLiveManager.sendToolResponse(callId, toolName, JSONObject().apply {
                put("status", "success")
                put("enabled", enable)
                put("message", if (enable) {
                    "Live Screen Companion mode is now ACTIVE. You are actively watching their screen and will offer helpful tips, suggestions, and assistance whenever they work or type."
                } else {
                    "Live Screen Companion mode has been turned OFF. You will only observe the screen when explicitly asked."
                })
            })
            return
        }

        if (toolName == "sleep_agent") {
            sleep()
            return
        }

        if (toolName == "answer_call") {
            val callManager = com.soltini.app.telephony.CallNotificationManager.getInstance(applicationContext)
            val res = callManager.answerCallDetailed()
            geminiLiveManager.sendToolResponse(
                callId, toolName,
                JSONObject().apply {
                    put("status", if (res.success) "success" else "failed")
                    put("method", res.method)
                    put("message", res.message)
                    put("instruction", if (res.success) {
                        "Confirm to Boss politely in Hindi/Hinglish: 'Ji Boss, call receive kar liya hai.'"
                    } else {
                        "Inform Boss in Hindi/Hinglish that call could not be answered: 'Boss, call receive nahi ho paya: ${res.message}'"
                    })
                }
            )
            return
        }

        if (toolName == "reject_call") {
            val callManager = com.soltini.app.telephony.CallNotificationManager.getInstance(applicationContext)
            val res = callManager.rejectCallDetailed()
            geminiLiveManager.sendToolResponse(
                callId, toolName,
                JSONObject().apply {
                    put("status", if (res.success) "success" else "failed")
                    put("method", res.method)
                    put("message", res.message)
                    put("instruction", if (res.success) {
                        "Confirm to Boss politely in Hindi/Hinglish: 'Ji Boss, call cut kar diya hai.'"
                    } else {
                        "Inform Boss in Hindi/Hinglish that call could not be rejected: 'Boss, call reject nahi ho paya: ${res.message}'"
                    })
                }
            )
            return
        }

        if (toolName == "get_call_info" || toolName == "who_is_calling") {
            val callManager = com.soltini.app.telephony.CallNotificationManager.getInstance(applicationContext)
            val info = callManager.getCurrentCallInfo()
            val json = info.toJsonObject()
            val instruction = if (info.isIncoming) {
                "Tell Boss in Hindi/Hinglish: 'Call aa raha hai ${info.callerName ?: "Unknown"}. Kya aap receive karna chahte hain ya reject karna chahte hain?'"
            } else {
                "Tell Boss in Hindi/Hinglish: 'Boss, abhi koi incoming call nahi aa raha hai (Current state: ${info.callState}).'"
            }
            json.put("instruction", instruction)
            geminiLiveManager.sendToolResponse(callId, toolName, json)
            return
        }

        if (toolName == "send_sms") {
            val recipient = args.optString("recipient", args.optString("contact", ""))
            val message = args.optString("message", args.optString("text", ""))
            val cleanMsg = com.soltini.app.orchestrator.MyraCommandParser.cleanMessageBody(message).ifBlank { message.trim() }
            val cleanRec = com.soltini.app.orchestrator.MyraCommandParser.cleanRecipient(recipient).ifBlank { recipient.trim() }

            val callManager = com.soltini.app.telephony.CallNotificationManager.getInstance(applicationContext)
            val matches = callManager.searchContacts(cleanRec)

            if (matches.isEmpty()) {
                geminiLiveManager.sendToolResponse(
                    callId, toolName,
                    JSONObject().apply {
                        put("status", "failed")
                        put("message", "Contacts me '$cleanRec' nahi mila.")
                        put("instruction", "Tell Boss in Hindi/Hinglish: 'Contacts me $cleanRec naam ka koi contact nahi mila. Kripya phone number batayein.'")
                    }
                )
            } else if (matches.size > 1 && !matches.any { it.name.equals(cleanRec, ignoreCase = true) }) {
                val listStr = matches.take(3).joinToString(", ") { "${it.name} (${it.phoneNumber})" }
                geminiLiveManager.sendToolResponse(
                    callId, toolName,
                    JSONObject().apply {
                        put("status", "clarification_needed")
                        put("message", "Multiple contacts found: $listStr")
                        put("instruction", "Ask Boss in Hindi/Hinglish: '$cleanRec naam ke multiple contacts mile: $listStr. Kisko message bhejna hai?'")
                    }
                )
            } else {
                val target = matches.find { it.name.equals(cleanRec, ignoreCase = true) } ?: matches.first()
                val smsSender = com.soltini.app.telephony.SmsSender(applicationContext)
                val sendRes = smsSender.sendSms(target.phoneNumber, cleanMsg)
                geminiLiveManager.sendToolResponse(
                    callId, toolName,
                    JSONObject().apply {
                        put("status", if (sendRes.success) "success" else "failed")
                        put("recipient", target.name)
                        put("phone", target.phoneNumber)
                        put("method", sendRes.method)
                        put("message", sendRes.message)
                        put("instruction", if (sendRes.success) {
                            "Tell Boss clearly in Hindi/Hinglish: 'Ji Boss, ${target.name} ko message bhej diya gaya hai.'"
                        } else {
                            "Tell Boss clearly in Hindi/Hinglish: 'Message nahi bheja ja saka: ${sendRes.message}'"
                        })
                    }
                )
            }
            return
        }

        if (toolName == "youtube_play") {
            val query = args.optString("query", args.optString("song", args.optString("title", "")))
            val cleanQuery = com.soltini.app.orchestrator.MyraCommandParser.cleanYouTubeQuery(query).ifBlank { query.trim() }
            val youtubeAutomator = com.soltini.app.agent.YouTubeAutomator(applicationContext)
            val playRes = youtubeAutomator.playSong(cleanQuery)
            val ok = playRes.optString("status") == "playing" || playRes.optString("status") == "success"
            geminiLiveManager.sendToolResponse(
                callId, toolName,
                JSONObject().apply {
                    put("status", if (ok) "success" else "failed")
                    put("query", cleanQuery)
                    put("message", playRes.optString("message", "Playing on YouTube"))
                    put("instruction", if (ok) {
                        "Tell Boss clearly in Hindi/Hinglish: 'Ji Boss, YouTube par $cleanQuery play ho raha hai.'"
                    } else {
                        "Tell Boss clearly: 'YouTube par play karne me samasya aayi: ${playRes.optString("message")}'"
                    })
                }
            )
            return
        }

        if (toolName == "youtube_search") {
            val query = args.optString("query", args.optString("q", ""))
            val cleanQuery = com.soltini.app.orchestrator.MyraCommandParser.cleanYouTubeQuery(query).ifBlank { query.trim() }
            val youtubeAutomator = com.soltini.app.agent.YouTubeAutomator(applicationContext)
            val searchRes = youtubeAutomator.search(cleanQuery)
            val ok = searchRes.optString("status") == "searching" || searchRes.optString("status") == "success"
            geminiLiveManager.sendToolResponse(
                callId, toolName,
                JSONObject().apply {
                    put("status", if (ok) "success" else "failed")
                    put("query", cleanQuery)
                    put("message", searchRes.optString("message", "Searching on YouTube"))
                    put("instruction", if (ok) {
                        "Tell Boss clearly in Hindi/Hinglish: 'Ji Boss, YouTube par $cleanQuery search kar diya hai.'"
                    } else {
                        "Tell Boss clearly: 'YouTube search me samasya aayi: ${searchRes.optString("message")}'"
                    })
                }
            )
            return
        }

        if (toolName == "make_phone_call") {
            val target = args.optString("contact_or_number", "")
            val callManager = com.soltini.app.telephony.CallNotificationManager.getInstance(applicationContext)
            val success = callManager.makeCall(target)
            geminiLiveManager.sendToolResponse(
                callId, toolName,
                JSONObject().apply {
                    put("status", if (success) "success" else "error")
                    put("message", if (success) "Calling $target..." else "Could not place call to $target.")
                    put("instruction", if (success) "Tell Boss: 'Ji Boss, $target ko call mila rahi hu.'" else "Tell Boss you couldn't place the call.")
                }
            )
            return
        }

        if (toolName == "set_busy_mode") {
            val enable = args.optBoolean("enable", true)
            val reason = args.optString("reason", "busy")
            val busyMgr = com.soltini.app.messaging.BusyModeManager.getInstance(applicationContext)
            val responseMsg = if (enable) {
                val sanitized = busyMgr.enableBusyMode(reason)
                "Ji Boss, Busy mode activate ho gaya hai ($sanitized). Ab WhatsApp, Instagram, Telegram, SMS ke messages ka polite auto-reply main khud handle karungi."
            } else {
                val summary = busyMgr.disableBusyMode()
                if (summary.totalRepliedCount > 0) {
                    val contacts = summary.uniqueSenders.joinToString(", ")
                    "Ji Boss, welcome back! Busy mode off kar diya gaya hai. Is session me maine kul ${summary.totalRepliedCount} messages ka reply kiya ($contacts)."
                } else {
                    "Ji Boss, Busy mode deactivate kar diya gaya hai. Is dauran koi naya message nahi aaya tha."
                }
            }
            geminiLiveManager.sendToolResponse(
                callId, toolName,
                JSONObject().apply {
                    put("status", "success")
                    put("busy_mode_active", enable)
                    put("message", responseMsg)
                    put("instruction", "Speak this exact outcome directly to Boss in Hindi/Hinglish: '$responseMsg'")
                }
            )
            appendAgentMessage(responseMsg)
            return
        }

        if (toolName == "get_busy_mode_status") {
            val busyMgr = com.soltini.app.messaging.BusyModeManager.getInstance(applicationContext)
            val active = busyMgr.isBusyModeActive()
            val reason = busyMgr.getBusyReason()
            val history = busyMgr.getRecentHistory(10)
            val msg = if (active) {
                "Boss, abhi Busy Mode active hai ($reason). Total ${history.size} auto-replies bheje gaye hain."
            } else {
                "Boss, Busy Mode abhi band hai. Pichle session me ${history.size} messages handle kiye gaye the."
            }
            geminiLiveManager.sendToolResponse(
                callId, toolName,
                JSONObject().apply {
                    put("status", "success")
                    put("busy_mode_active", active)
                    put("reason", reason)
                    put("history_count", history.size)
                    put("instruction", "Tell Boss in Hindi/Hinglish: '$msg'")
                }
            )
            return
        }

        if (toolName == "trigger_automation") {
            val request = args.optString("request", "")
            Log.i(TAG, "Triggering MYRA Orchestrator for request: \"$request\"")
            appendUserMessage(request)

            // 1. Immediately acknowledge to Live Voice model so it says "I'm on it!" without delay
            geminiLiveManager.sendToolResponse(
                callId,
                toolName,
                JSONObject().apply {
                    put("status", "in_progress")
                    put("message", "Automation task triggered for: \"$request\". Tell Boss you are working on it now (e.g. \"I'm on it!\" or \"Ji Boss, abhi karti hu!\"). Do NOT say it is already finished yet.")
                }
            )

            // 2. Execute MYRA Orchestrator plan asynchronously
            serviceScope.launch(Dispatchers.IO) {
                val count = activeToolCallCount.incrementAndGet()
                if (count == 1) {
                    com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = true
                }
                try {
                    val orchestratorResult = myraOrchestrator.processRequest(request) { statusUpdate ->
                        geminiLiveManager.sendClientContent(
                            "[ORCHESTRATOR UPDATE: $statusUpdate]"
                        )
                    }

                    appendAgentMessage(orchestratorResult.responseText)
                    Log.i(TAG, "MYRA Orchestrator finished with outcome: ${orchestratorResult.responseText}")
                    geminiLiveManager.sendClientContent(
                        "[SYSTEM NOTIFICATION: Task completed for: \"$request\". Outcome: ${orchestratorResult.responseText}. Tools used: ${orchestratorResult.toolSequenceUsed.joinToString(", ")}. Speak directly to Boss in Hindi/Hinglish to share the outcome!]"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "MYRA Orchestrator failed: ${e.message}", e)
                    appendAgentMessage("Task failed: ${e.message}")
                    geminiLiveManager.sendClientContent(
                        "[SYSTEM NOTIFICATION: Background automation failed for request: \"$request\" with error: ${e.message}. Briefly inform Boss.]"
                    )
                } finally {
                    val remaining = activeToolCallCount.decrementAndGet()
                    if (remaining <= 0) {
                        activeToolCallCount.set(0)
                        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = false
                    }
                }
            }
            return
        }

        if (toolName == "browser_use_task") {
            val request = args.optString("request", "")
            Log.i(TAG, "Delegating to Browser-Use bridge for request: \"$request\"")
            appendUserMessage(request)

            // 1. Immediately acknowledge so the Live Voice model responds without delay
            geminiLiveManager.sendToolResponse(
                callId,
                toolName,
                JSONObject().apply {
                    put("status", "in_progress")
                    put("message", "Browser-Use task triggered for: \"$request\". Tell the user you are starting it on their desktop browser now. Do NOT say it is already finished yet.")
                }
            )

            // 2. Call the bridge server asynchronously (this can take a while — real browser navigation)
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val result = browserUseClient.runTask(request)
                    val status = result.optString("status", "error")
                    val message = result.optString("message", "")

                    appendAgentMessage(message)
                    Log.i(TAG, "Browser-Use task finished: status=$status message=$message")
                    geminiLiveManager.sendClientContent(
                        "[SYSTEM NOTIFICATION: Browser-Use task ($status) for: \"$request\". Outcome: $message. Speak directly to the user in Hindi/Hinglish to share the outcome!]"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Browser-Use bridge call failed: ${e.message}", e)
                    appendAgentMessage("Browser-Use task failed: ${e.message}")
                    geminiLiveManager.sendClientContent(
                        "[SYSTEM NOTIFICATION: Browser-Use task failed for request: \"$request\" with error: ${e.message}. Briefly inform the user.]"
                    )
                }
            }
            return
        }
        // Direct device tools (open_app, lock_device, wake_device, press_back, press_home, press_recents)
        serviceScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Executing direct tool: $toolName (callId=$callId)")

            val count = activeToolCallCount.incrementAndGet()
            if (count == 1) {
                com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = true
            }

            try {
                val result = agentToolExecutor.execute(toolName, args)
                geminiLiveManager.sendToolResponse(callId, toolName, result)
                memory2Engine.session.recordToolResult(toolName, result.optString("message", "Executed $toolName"))
            } finally {
                val remaining = activeToolCallCount.decrementAndGet()
                if (remaining <= 0) {
                    activeToolCallCount.set(0)
                    com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = false
                }
            }
        }
    }

    // ─── GeminiLiveListener Implementation ───────────────────────────────────

    override fun onAudioDataReceived(pcmData: ByteArray) {
        audioPlayer.playChunk(pcmData)
    }

    override fun onTextReceived(text: String) {
        currentGeminiTranscript.append(text)
        val fullText = currentGeminiTranscript.toString().trim()

        val list = _transcripts.value.toMutableList()
        val existingIndex = if (currentTurnGeminiEntryId != null) {
            list.indexOfLast { it.id == currentTurnGeminiEntryId }
        } else {
            -1
        }

        if (existingIndex != -1) {
            list[existingIndex] = list[existingIndex].copy(text = fullText)
        } else {
            val newEntry = TranscriptEntry(sender = TranscriptEntry.Sender.GEMINI, text = fullText)
            currentTurnGeminiEntryId = newEntry.id
            list.add(newEntry)
        }

        _transcripts.value = list
    }

    override fun onInterrupted() {
        Log.d(TAG, "User interrupted Gemini speech")
        audioPlayer.interrupt()
        currentGeminiTranscript.clear()
        currentTurnGeminiEntryId = null
    }

    override fun onTurnComplete() {
        Log.d(TAG, "Gemini turn completed")
        // Capture the finished transcript before clearing, then persist to DB and extract memories
        val finishedText = currentGeminiTranscript.toString().trim()
        val turnId = currentTurnGeminiEntryId
        currentGeminiTranscript.clear()
        currentTurnGeminiEntryId = null

        if (finishedText.isNotBlank()) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    // Persist completed response to SQLite conversation history
                    val completedEntry = _transcripts.value.find { it.id == turnId }
                        ?: _transcripts.value.lastOrNull { it.sender == TranscriptEntry.Sender.GEMINI }
                    if (completedEntry != null) {
                        memory2Engine.db.saveConversationEntry(
                            id = completedEntry.id,
                            sender = completedEntry.sender.name,
                            text = completedEntry.text,
                            timestamp = completedEntry.timestamp
                        )
                    }

                    val lastUser = _transcripts.value.lastOrNull { it.sender == TranscriptEntry.Sender.USER }?.text
                        ?: "Voice conversation turn"

                    memory2Engine.evaluateAndStore(
                        goal = lastUser,
                        outcomeSummary = finishedText,
                        toolSequence = emptyList(),
                        isSuccess = true,
                        userExplicitStatement = finishedText
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Memory extraction / conversation save error (non-fatal): ${e.message}")
                }
            }
        }
    }

    override fun onError(message: String) {
        Log.e(TAG, "Gemini Live Error: $message")
        // Reset tool call counter and deactivate accessibility on connection error
        activeToolCallCount.set(0)
        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }

        try {
            silentMediaPlayer?.stop()
            silentMediaPlayer?.release()
            silentMediaPlayer = null
        } catch (e: Exception) { /* ignore */ }

        wakeLock?.let {
            if (it.isHeld) it.release()
        }

        audioRecorder.stopRecording()
        audioPlayer.stop()
        geminiLiveManager.disconnect()
        try {
            com.soltini.app.telephony.CallNotificationManager.getInstance(this).shutdown()
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): android.app.Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Myra")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startForegroundService() {
        val notification = buildNotification("Your AI Assistant is ready")

        if (Build.VERSION.SDK_INT >= 34) {
            val specialUse = 1073741824 // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            val mic = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            try {
                if (hasMic) {
                    startForeground(NOTIFICATION_ID, notification, specialUse or mic)
                } else {
                    startForeground(NOTIFICATION_ID, notification, specialUse)
                }
            } catch (e: Exception) {
                Log.w(TAG, "startForeground with microphone type failed (${e.message}), falling back to specialUse")
                try {
                    startForeground(NOTIFICATION_ID, notification, specialUse)
                } catch (e2: Exception) {
                    Log.e(TAG, "startForeground fallback failed: ${e2.message}", e2)
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            try {
                if (hasMic) {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                Log.w(TAG, "startForeground failed: ${e.message}")
                try { startForeground(NOTIFICATION_ID, notification) } catch (_: Exception) {}
            }
        } else {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.e(TAG, "startForeground error: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Session Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun isNetworkConnected(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }
}
