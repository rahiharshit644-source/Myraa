package com.soltini.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soltini.app.homeautomation.control.DeviceController
import com.soltini.app.homeautomation.data.DeviceEntity
import com.soltini.app.homeautomation.data.HomeDatabase
import com.soltini.app.network.ConnectionState
import com.soltini.app.services.BackgroundVoiceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TranscriptEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: Sender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Sender {
        USER, GEMINI
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var voiceService: BackgroundVoiceService? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _micAmplitude = MutableStateFlow(0f)
    val micAmplitude: StateFlow<Float> = _micAmplitude.asStateFlow()

    private val _speakerAmplitude = MutableStateFlow(0f)
    val speakerAmplitude: StateFlow<Float> = _speakerAmplitude.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _hasMicPermission = MutableStateFlow(false)
    val hasMicPermission: StateFlow<Boolean> = _hasMicPermission.asStateFlow()

    private val _transcripts = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcripts: StateFlow<List<TranscriptEntry>> = _transcripts.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isLitePaused = MutableStateFlow(false)
    val isLitePaused: StateFlow<Boolean> = _isLitePaused.asStateFlow()

    private val _isHardPaused = MutableStateFlow(false)
    val isHardPaused: StateFlow<Boolean> = _isHardPaused.asStateFlow()

    private val homeDb = HomeDatabase.getInstance(application)
    private val deviceController = DeviceController.create(application)
    val homeDevices: StateFlow<List<DeviceEntity>> = homeDb.deviceDao().getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<DeviceEntity>())
    val liveDeviceStates: StateFlow<Map<String, String>> = com.soltini.app.homeautomation.mqtt.MqttManager.getInstance(application).deviceStates

    fun toggleDevice(device: DeviceEntity, onResult: ((String) -> Unit)? = null) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = deviceController.executeControl(
                callId = "ui_toggle_${System.currentTimeMillis()}",
                deviceName = device.deviceName,
                actionRequested = "TOGGLE"
            )
            val msg = result.optString("message", "${device.deviceName} toggled")
            onResult?.invoke(msg)
        }
    }

    fun executeDeviceControl(deviceName: String, action: String, onResult: ((String) -> Unit)? = null) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = deviceController.executeControl(
                callId = "ui_cmd_${System.currentTimeMillis()}",
                deviceName = deviceName,
                actionRequested = action
            )
            val msg = result.optString("message", "$deviceName $action executed")
            onResult?.invoke(msg)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BackgroundVoiceService.LocalBinder
            voiceService = binder.getService()
            observeService()
            // Safety net: if START_STICKY restarted the service but the WebSocket is not
            // yet alive (e.g. still Disconnected after the auto-connect in onCreate),
            // trigger connect() here so the user always gets a live session when opening the app.
            viewModelScope.launch {
                val svc = voiceService ?: return@launch
                if (!svc.isSleeping.value &&
                    svc.connectionState.value == ConnectionState.Disconnected) {
                    svc.connect()
                }
            }
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            voiceService = null
        }
    }

    init {
        // Preload conversation history from database immediately
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val db = com.soltini.app.memory2.Memory2Database(application)
                val saved = db.getAllConversationEntries(limit = 100)
                if (saved.isNotEmpty() && _transcripts.value.isEmpty()) {
                    val entries = saved.map { rec ->
                        val sender = if (rec.sender == "USER") TranscriptEntry.Sender.USER else TranscriptEntry.Sender.GEMINI
                        TranscriptEntry(id = rec.id, sender = sender, text = rec.text, timestamp = rec.timestamp)
                    }
                    _transcripts.value = entries
                }
            } catch (e: Exception) {
                // Non-fatal preload error
            }
        }

        val intent = Intent(application, BackgroundVoiceService::class.java)
        try {
            androidx.core.content.ContextCompat.startForegroundService(application, intent)
        } catch (_: Exception) {
            try { application.startService(intent) } catch (_: Exception) {}
        }
        application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun observeService() {
        voiceService?.let { service ->
            viewModelScope.launch { service.connectionState.collect { _connectionState.value = it } }
            viewModelScope.launch { service.errorMessage.collect { _errorMessage.value = it } }
            viewModelScope.launch { service.micAmplitude.collect { _micAmplitude.value = it } }
            viewModelScope.launch { service.speakerAmplitude.collect { _speakerAmplitude.value = it } }
            viewModelScope.launch { service.isSpeaking.collect { _isSpeaking.value = it } }
            viewModelScope.launch { service.isMuted.collect { _isMuted.value = it } }
            viewModelScope.launch { service.hasMicPermission.collect { _hasMicPermission.value = it } }
            viewModelScope.launch { service.transcripts.collect { _transcripts.value = it } }
            viewModelScope.launch { service.isPaused.collect {
                _isPaused.value = it
                _isLitePaused.value = it
            }}
            viewModelScope.launch { service.isHardPaused.collect { _isHardPaused.value = it } }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        voiceService?.onPermissionResult(granted)
    }

    fun connect() {
        voiceService?.connect()
    }

    fun disconnect() {
        voiceService?.disconnect()
    }

    fun toggleMute() {
        voiceService?.toggleMute()
    }

    fun clearTranscript() {
        voiceService?.clearTranscript()
        _transcripts.value = emptyList()
    }

    /**
     * Sends a user text command to MYRA orchestrator and records it in conversation history.
     */
    fun sendUserMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val svc = voiceService
        if (svc != null) {
            svc.processUserTextCommand(trimmed)
        } else {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    val settings = com.soltini.app.settings.AppSettings(app)
                    val mem2 = com.soltini.app.memory2.Memory2Engine.getInstance(app, settings)
                    val plugins = com.soltini.app.plugins.PluginRegistry.getInstance(app)
                    val orch = com.soltini.app.orchestrator.MyraOrchestrator.getInstance(app, mem2, plugins, settings)

                    val userEntry = TranscriptEntry(sender = TranscriptEntry.Sender.USER, text = trimmed)
                    val list = _transcripts.value.toMutableList()
                    list.add(userEntry)
                    _transcripts.value = list
                    mem2.db.saveConversationEntry(userEntry.id, userEntry.sender.name, userEntry.text, userEntry.timestamp)

                    val result = orch.processRequest(trimmed)
                    val agentEntry = TranscriptEntry(sender = TranscriptEntry.Sender.GEMINI, text = result.responseText)
                    val after = _transcripts.value.toMutableList()
                    after.add(agentEntry)
                    _transcripts.value = after
                    mem2.db.saveConversationEntry(agentEntry.id, agentEntry.sender.name, agentEntry.text, agentEntry.timestamp)
                } catch (e: Exception) {
                    val errEntry = TranscriptEntry(sender = TranscriptEntry.Sender.GEMINI, text = "Error: ${e.message}")
                    val list = _transcripts.value.toMutableList()
                    list.add(errEntry)
                    _transcripts.value = list
                }
            }
        }
    }

    /** Disconnects and reconnects to apply new API key / persona / voice settings. */
    fun reconnect() {
        voiceService?.reconnect()
    }

    /** Toggles Pause Mode: pauses everything including accessibility, or resumes. */
    fun togglePauseMode() {
        voiceService?.togglePauseMode()
    }

    /** Lite Pause: silences accessibility without disabling. Instant resume. */
    fun toggleLitePauseMode() {
        voiceService?.toggleLitePauseMode()
    }

    /** Hard Pause: calls disableSelf() for strict banks. Auto-opens settings on resume. */
    fun toggleHardPauseMode() {
        voiceService?.toggleHardPauseMode()
    }

    /** Gives Settings UI access to user-configurable AI settings. */
    fun getAppSettings() = voiceService?.appSettings

    /** Gives UI access to the unified Memory 2.0 Engine (facts, sessions, knowledge, experience). */
    fun getMemory2Engine() = voiceService?.memory2Engine

    /** Gives UI access to Plugin & Tool Registry. */
    fun getPluginRegistry() = voiceService?.pluginRegistry

    /** Gives UI access to MYRA Orchestrator. */
    fun getMyraOrchestrator() = voiceService?.myraOrchestrator

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unbindService(connection)
        } catch (e: Exception) {
            // Ignore if already unbound
        }
    }
}
