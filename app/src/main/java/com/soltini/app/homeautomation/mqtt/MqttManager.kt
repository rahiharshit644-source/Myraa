package com.soltini.app.homeautomation.mqtt

import android.content.Context
import android.util.Log
import com.soltini.app.homeautomation.data.WifiMqttCredentialsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocketFactory

/**
 * MqttConnectionStatus
 *
 * Observable connection state of the MQTT client connecting to HiveMQ Cloud or local broker.
 */
enum class MqttConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/**
 * MqttManager
 *
 * Robust MQTT manager built on the Eclipse Paho Java client.
 *
 * Responsibilities:
 * 1. Establishes secure TLS connection to HiveMQ Cloud (or local broker) using credentials
 *    from WifiMqttCredentialsStore.
 * 2. Manages auto-reconnect with exponential backoff on connection drops.
 * 3. Subscribes to the wildcard topic "home/+/+/state" to ingest real-time state feedback
 *    from all active ESP32 nodes.
 * 4. Exposes `deviceStates` as a reactive StateFlow so UI cards and voice controller
 *    can observe and read live ON/OFF states.
 * 5. Provides high-level `publish()` for sending commands to "home/{room}/{device}/set".
 *
 * Shared Singleton Pattern:
 * Accessible by both the DeviceController (voice) and the UI ViewModels (AddDeviceScreen).
 */
class MqttManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val credStore = WifiMqttCredentialsStore(appContext)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var client: MqttAsyncClient? = null
    private val clientPersistence = MemoryPersistence()

    private val _connectionStatus = MutableStateFlow(MqttConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<MqttConnectionStatus> = _connectionStatus.asStateFlow()

    private val _statusMessage = MutableStateFlow("Disconnected")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    /**
     * Map of topicBase (e.g. "home/living_room/ceiling_light") -> State ("ON" or "OFF")
     */
    private val stateCache = ConcurrentHashMap<String, String>()
    private val _deviceStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val deviceStates: StateFlow<Map<String, String>> = _deviceStates.asStateFlow()

    private var isManualDisconnect = false
    private var reconnectAttempt = 0

    companion object {
        private const val TAG = "MqttManager"
        private const val STATE_WILDCARD_TOPIC = "home/+/+/state"

        @Volatile
        private var INSTANCE: MqttManager? = null

        fun getInstance(context: Context): MqttManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MqttManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Connects to the configured MQTT broker (HiveMQ Cloud).
     */
    fun connect() {
        if (_connectionStatus.value == MqttConnectionStatus.CONNECTING ||
            _connectionStatus.value == MqttConnectionStatus.CONNECTED
        ) {
            Log.d(TAG, "MQTT already connecting or connected.")
            return
        }

        val rawHost = credStore.mqttHost.trim()
        val port = credStore.mqttPort
        val user = credStore.mqttUser.trim()
        val pass = credStore.mqttPass

        if (rawHost.isBlank()) {
            _statusMessage.value = "Broker host not configured"
            _connectionStatus.value = MqttConnectionStatus.ERROR
            Log.w(TAG, "Cannot connect: MQTT host is empty.")
            return
        }

        isManualDisconnect = false
        _connectionStatus.value = MqttConnectionStatus.CONNECTING
        _statusMessage.value = "Connecting to $rawHost:$port..."

        scope.launch {
            try {
                val brokerUrl = buildServerUri(rawHost, port)
                val clientId = "SoltiniApp_" + UUID.randomUUID().toString().take(8)

                client?.disconnectForcibly()
                client?.close()

                val newClient = MqttAsyncClient(brokerUrl, clientId, clientPersistence)
                client = newClient

                newClient.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.i(TAG, "MQTT connected to $serverURI (reconnect=$reconnect)")
                        _connectionStatus.value = MqttConnectionStatus.CONNECTED
                        _statusMessage.value = "Connected to $rawHost"
                        reconnectAttempt = 0
                        subscribeToStates(newClient)
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                        if (!isManualDisconnect) {
                            _connectionStatus.value = MqttConnectionStatus.RECONNECTING
                            _statusMessage.value = "Reconnecting..."
                            scheduleReconnect()
                        } else {
                            _connectionStatus.value = MqttConnectionStatus.DISCONNECTED
                            _statusMessage.value = "Disconnected"
                        }
                    }

                    override fun messageArrived(topic: String, message: MqttMessage) {
                        handleIncomingMessage(topic, message)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        // Message delivery confirmation
                    }
                })

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    isAutomaticReconnect = true
                    connectionTimeout = 12
                    keepAliveInterval = 30
                    if (user.isNotBlank()) {
                        userName = user
                        password = pass.toCharArray()
                    }
                    if (brokerUrl.startsWith("ssl://")) {
                        socketFactory = SSLSocketFactory.getDefault()
                    }
                }

                newClient.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Log.i(TAG, "Paho connect onSuccess callback invoked.")
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        val errMsg = exception?.message ?: "Unknown connection error"
                        Log.e(TAG, "Paho connect onFailure: $errMsg", exception)
                        _connectionStatus.value = MqttConnectionStatus.ERROR
                        _statusMessage.value = "Connection failed: $errMsg"
                        if (!isManualDisconnect) {
                            scheduleReconnect()
                        }
                    }
                })

            } catch (e: Exception) {
                Log.e(TAG, "Error initiating MQTT connection: ${e.message}", e)
                _connectionStatus.value = MqttConnectionStatus.ERROR
                _statusMessage.value = "Error: ${e.message}"
                scheduleReconnect()
            }
        }
    }

    private fun buildServerUri(host: String, port: Int): String {
        return when {
            host.startsWith("ssl://") || host.startsWith("tcp://") || host.startsWith("wss://") -> host
            port == 8883 -> "ssl://$host:$port"
            else -> "tcp://$host:$port"
        }
    }

    private fun subscribeToStates(mqttClient: MqttAsyncClient) {
        try {
            mqttClient.subscribe(STATE_WILDCARD_TOPIC, 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Subscribed successfully to state topic: $STATE_WILDCARD_TOPIC")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to subscribe to $STATE_WILDCARD_TOPIC: ${exception?.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception subscribing to states: ${e.message}")
        }
    }

    private fun handleIncomingMessage(topic: String, message: MqttMessage) {
        try {
            val payload = String(message.payload, StandardCharsets.UTF_8).trim()
            Log.d(TAG, "Incoming MQTT message: $topic -> $payload")

            // Topic format: home/{room}/{device}/state -> base: home/{room}/{device}
            if (topic.endsWith("/state")) {
                val topicBase = topic.removeSuffix("/state")
                stateCache[topicBase] = payload.uppercase()
                _deviceStates.value = HashMap(stateCache)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming MQTT message: ${e.message}")
        }
    }

    /**
     * Publishes an ON, OFF, or TOGGLE command to an ESP32 device topic.
     * e.g., topic = "home/living_room/ceiling_light/set", payload = "ON"
     */
    fun publish(topic: String, payload: String, qos: Int = 1, retained: Boolean = false): Boolean {
        val currentClient = client
        if (currentClient == null || !currentClient.isConnected) {
            Log.w(TAG, "Cannot publish to $topic: MQTT client not connected.")
            // Try connecting in background if credentials exist
            if (credStore.isMqttConfigured()) {
                connect()
            }
            return false
        }

        return try {
            val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                this.qos = qos
                this.isRetained = retained
            }
            currentClient.publish(topic, message)
            Log.i(TAG, "Published to $topic -> $payload")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish to $topic: ${e.message}", e)
            false
        }
    }

    /**
     * Returns the cached state for a device (e.g. "ON" or "OFF") if known.
     */
    fun getDeviceState(topicBase: String): String? {
        return stateCache[topicBase]
    }

    private fun scheduleReconnect() {
        if (isManualDisconnect) return
        scope.launch {
            reconnectAttempt++
            val backoffSec = (2L * reconnectAttempt).coerceAtMost(30L)
            Log.d(TAG, "Scheduling MQTT reconnect attempt #$reconnectAttempt in ${backoffSec}s")
            delay(backoffSec * 1000)
            if (!isManualDisconnect && _connectionStatus.value != MqttConnectionStatus.CONNECTED) {
                connect()
            }
        }
    }

    fun disconnect() {
        isManualDisconnect = true
        scope.launch {
            try {
                client?.disconnect()
                client?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error during MQTT disconnect: ${e.message}")
            } finally {
                client = null
                _connectionStatus.value = MqttConnectionStatus.DISCONNECTED
                _statusMessage.value = "Disconnected"
            }
        }
    }
}
