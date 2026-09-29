package com.soltini.app.homeautomation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soltini.app.homeautomation.control.DeviceController
import com.soltini.app.homeautomation.data.DeviceDao
import com.soltini.app.homeautomation.data.DeviceEntity
import com.soltini.app.homeautomation.data.DeviceType
import com.soltini.app.homeautomation.data.HomeDatabase
import com.soltini.app.homeautomation.data.WifiMqttCredentialsStore
import com.soltini.app.homeautomation.generator.Esp32CodeGenerator
import com.soltini.app.homeautomation.mqtt.MqttConnectionStatus
import com.soltini.app.homeautomation.mqtt.MqttManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * HomeAutomationViewModel
 *
 * ViewModel orchestrating the Device Wizard, Room persistence, MQTT commands,
 * and ESP32 code generation.
 *
 * Connections:
 * - Backs AddDeviceScreen and DeviceListScreen.
 * - Reads and writes to the unified Room `DeviceEntity` table via `DeviceDao`.
 * - Interacts with `MqttManager` to publish manual toggles from the UI and observe live states.
 * - Invokes `Esp32CodeGenerator` to construct board firmware sketches.
 */
class HomeAutomationViewModel(application: Application) : AndroidViewModel(application) {

    private val db = HomeDatabase.getInstance(application)
    val deviceDao: DeviceDao = db.deviceDao()
    val credStore = WifiMqttCredentialsStore(application)
    val mqttManager = MqttManager.getInstance(application)

    // Live list of devices from Room database
    val devices: StateFlow<List<DeviceEntity>> = deviceDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Live list of known ESP32 board identifiers
    val boardIds: StateFlow<List<String>> = deviceDao.getAllBoardIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Live state feedback from MQTT: "home/{room}/{device}" -> "ON" / "OFF"
    val liveStates: StateFlow<Map<String, String>> = mqttManager.deviceStates

    // MQTT connection status
    val mqttStatus: StateFlow<MqttConnectionStatus> = mqttManager.connectionStatus
    val mqttStatusMessage: StateFlow<String> = mqttManager.statusMessage

    // Code preview dialog state
    private val _generatedCode = MutableStateFlow<String?>(null)
    val generatedCode: StateFlow<String?> = _generatedCode.asStateFlow()

    private val _activeBoardForCode = MutableStateFlow<String?>(null)
    val activeBoardForCode: StateFlow<String?> = _activeBoardForCode.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    init {
        // Auto-connect to MQTT if credentials are configured
        if (credStore.isMqttConfigured()) {
            mqttManager.connect()
        }
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    /**
     * Inserts a new device into the Room database and returns the generated DeviceEntity.
     */
    fun saveDevice(
        deviceName: String,
        roomName: String,
        deviceType: DeviceType,
        gpioPin: Int,
        esp32ClientId: String,
        onSaved: (DeviceEntity) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = DeviceEntity(
                deviceName = deviceName.trim(),
                roomName = roomName.trim(),
                deviceType = deviceType,
                gpioPin = gpioPin,
                mqttTopicBase = DeviceEntity.deriveTopicBase(roomName, deviceName),
                esp32ClientId = esp32ClientId.trim()
            )
            val newId = deviceDao.insert(entity)
            val savedEntity = entity.copy(id = newId)

            _userMessage.value = "Added '${savedEntity.deviceName}' to ${savedEntity.roomName}!"
            launch(Dispatchers.Main) {
                onSaved(savedEntity)
            }
        }
    }

    /**
     * Deletes a device from the unified registry.
     */
    fun deleteDevice(device: DeviceEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            deviceDao.delete(device)
            _userMessage.value = "Deleted '${device.deviceName}'"
        }
    }

    /**
     * Toggles a physical device directly from the in-app UI.
     */
    fun toggleDevice(device: DeviceEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentState = mqttManager.getDeviceState(device.mqttTopicBase)
            val nextState = if (currentState?.equals("ON", ignoreCase = true) == true) "OFF" else "ON"
            val topic = "${device.mqttTopicBase}/set"
            val published = mqttManager.publish(topic, nextState)
            if (!published) {
                _userMessage.value = "Could not send command: MQTT disconnected."
            }
        }
    }

    /**
     * Generates the Arduino C++ sketch (.ino) for a specified board.
     */
    fun generateFirmwareForBoard(boardId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val boardDevices = deviceDao.getByEsp32ClientId(boardId)
            val sketch = Esp32CodeGenerator.generateSketch(
                devices = boardDevices,
                wifiSsid = credStore.wifiSsid.ifBlank { "YOUR_WIFI_SSID" },
                wifiPass = credStore.wifiPass.ifBlank { "YOUR_WIFI_PASSWORD" },
                mqttHost = credStore.mqttHost.ifBlank { "YOUR_HIVEMQ_HOST.hivemq.cloud" },
                mqttPort = credStore.mqttPort,
                mqttUser = credStore.mqttUser.ifBlank { "YOUR_MQTT_USERNAME" },
                mqttPass = credStore.mqttPass.ifBlank { "YOUR_MQTT_PASSWORD" },
                clientId = boardId
            )
            _activeBoardForCode.value = boardId
            _generatedCode.value = sketch
        }
    }

    fun dismissCodeDialog() {
        _generatedCode.value = null
        _activeBoardForCode.value = null
    }

    /**
     * Saves WiFi and MQTT broker credentials in the encrypted store.
     */
    fun saveCredentials(
        ssid: String,
        pass: String,
        host: String,
        port: Int,
        user: String,
        mqttPass: String
    ) {
        credStore.saveAll(
            wifiSsid = ssid,
            wifiPass = pass,
            mqttHost = host,
            mqttPort = port,
            mqttUser = user,
            mqttPass = mqttPass
        )
        _userMessage.value = "Credentials saved securely."
        // Reconnect MQTT client with new parameters
        mqttManager.disconnect()
        if (credStore.isMqttConfigured()) {
            mqttManager.connect()
        }
    }

    fun retryMqttConnection() {
        mqttManager.connect()
    }
}
