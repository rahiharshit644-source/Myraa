package com.soltini.app.homeautomation.control

import android.content.Context
import android.util.Log
import com.soltini.app.homeautomation.data.DeviceDao
import com.soltini.app.homeautomation.data.DeviceEntity
import com.soltini.app.homeautomation.data.HomeDatabase
import com.soltini.app.homeautomation.model.DeviceAction
import com.soltini.app.homeautomation.model.DeviceControlResult
import com.soltini.app.homeautomation.mqtt.MqttManager
import org.json.JSONObject
import java.util.Locale

/**
 * DeviceController
 *
 * Core execution bridge between Gemini Live function-calling and the physical ESP32 mesh.
 *
 * Architecture & Data Integrity:
 * - Reads directly from the unified Room `DeviceEntity` table via `DeviceDao` — guaranteed
 *   single source of truth with the Device Wizard.
 * - Resolves natural language device requests (e.g. "Ceiling Light", "Fan") to the stored
 *   `mqttTopicBase` (e.g. "home/living_room/ceiling_light").
 * - Transmits the MQTT command payload to "{mqttTopicBase}/set".
 * - Returns structured `DeviceControlResult` objects that Gemini consumes as `functionResponse`
 *   to generate spoken audio feedback for the user.
 *
 * Connections:
 * - Invoked by BackgroundVoiceService.onToolCall when `toolName == "control_device"`.
 * - Injected with HomeDatabase.deviceDao and MqttManager singleton.
 */
class DeviceController(
    private val deviceDao: DeviceDao,
    private val mqttManager: MqttManager
) {

    companion object {
        private const val TAG = "DeviceController"

        fun create(context: Context): DeviceController {
            val db = HomeDatabase.getInstance(context)
            val mqtt = MqttManager.getInstance(context)
            return DeviceController(db.deviceDao(), mqtt)
        }
    }

    /**
     * Executes the Gemini Live "control_device" function call.
     *
     * @param callId Gemini function call ID (used to correlate response).
     * @param deviceName Target device name (e.g. "Living Room Light" or "Fan").
     * @param actionRequested Requested state: "ON", "OFF", or "TOGGLE".
     * @return JSONObject containing the execution output to be passed to sendToolResponse.
     */
    suspend fun executeControl(
        callId: String,
        deviceName: String,
        actionRequested: String
    ): JSONObject {
        val parsedAction = DeviceAction.fromString(actionRequested)
        Log.i(TAG, "Executing control_device: callId=$callId, device=$deviceName, action=$parsedAction")

        // 1. Resolve target device from the unified Room registry
        val matchedDevice = resolveDevice(deviceName)

        if (matchedDevice == null) {
            val allDevices = deviceDao.getAllList()
            val availableNames = allDevices.map { "${it.deviceName} (${it.roomName})" }
            Log.w(TAG, "Device not found for: '$deviceName'. Available: $availableNames")

            val notFoundResult = DeviceControlResult.DeviceNotFound(
                callId = callId,
                queriedName = deviceName,
                availableDevices = availableNames
            )
            return notFoundResult.toJsonObject()
        }

        // 2. Determine target payload (handling TOGGLE dynamically via live state cache)
        val topicBase = matchedDevice.mqttTopicBase
        val commandTopic = "$topicBase/set"

        val targetPayload = when (parsedAction) {
            DeviceAction.ON -> "ON"
            DeviceAction.OFF -> "OFF"
            DeviceAction.TOGGLE -> {
                val currentState = mqttManager.getDeviceState(topicBase)
                if (currentState?.equals("ON", ignoreCase = true) == true) "OFF" else "ON"
            }
        }

        // 3. Publish MQTT message to the device's command topic
        val isPublished = mqttManager.publish(commandTopic, targetPayload, qos = 1, retained = false)

        return if (isPublished) {
            val confirmation = "Turned $targetPayload the ${matchedDevice.deviceName} in the ${matchedDevice.roomName}."
            val successResult = DeviceControlResult.Success(
                callId = callId,
                deviceName = matchedDevice.deviceName,
                roomName = matchedDevice.roomName,
                appliedAction = targetPayload,
                mqttTopic = commandTopic,
                confirmationMessage = confirmation
            )
            Log.i(TAG, "Device command published successfully: $confirmation")
            successResult.toJsonObject()
        } else {
            val failResult = DeviceControlResult.Failure(
                callId = callId,
                deviceName = matchedDevice.deviceName,
                error = "MQTT command could not be dispatched. Verify broker connectivity in Settings."
            )
            Log.e(TAG, "Failed to publish MQTT command for ${matchedDevice.deviceName}")
            failResult.toJsonObject()
        }
    }

    /**
     * Resolves device using exact match first, followed by fuzzy/token matching
     * across deviceName and roomName.
     */
    private suspend fun resolveDevice(query: String): DeviceEntity? {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)

        // 1. Direct exact match in database
        val direct = deviceDao.getByDeviceName(cleanQuery)
        if (direct != null) return direct

        // 2. Fetch all devices to evaluate composite and fuzzy matching
        val all = deviceDao.getAllList()
        if (all.isEmpty()) return null

        // Check if query matches "Room + Device" e.g., "living room light"
        val compositeMatch = all.firstOrNull {
            val combined = "${it.roomName} ${it.deviceName}".lowercase(Locale.ROOT)
            combined == cleanQuery || cleanQuery.contains(it.deviceName.lowercase(Locale.ROOT))
        }
        if (compositeMatch != null) return compositeMatch

        // Check if query is contained in deviceName or vice-versa
        return all.firstOrNull {
            val dev = it.deviceName.lowercase(Locale.ROOT)
            dev.contains(cleanQuery) || cleanQuery.contains(dev)
        }
    }

    /**
     * Configures a location context trigger (e.g. "Ghar pahunchne pe Wi-Fi on kar dena")
     */
    fun setupLocationTrigger(
        context: Context,
        label: String,
        latitude: Double,
        longitude: Double,
        actionDescription: String,
        targetDevice: String? = null,
        targetAction: String? = null,
        isEnterTrigger: Boolean = true,
        radiusMeters: Float = 100f
    ) {
        val manager = com.soltini.app.automation.GeofenceManager.getInstance(context)
        val rule = com.soltini.app.automation.GeofenceManager.LocationTriggerRule(
            id = "geo_${label.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}",
            label = label,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters,
            transitionType = if (isEnterTrigger) com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_ENTER else com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_EXIT,
            actionDescription = actionDescription,
            targetDevice = targetDevice,
            targetAction = targetAction
        )
        manager.registerTrigger(rule) { success, msg ->
            Log.i(TAG, "Location trigger registered: $success ($msg)")
        }
    }

    /**
     * Lists active location-based automation rules.
     */
    fun getLocationTriggers(context: Context): List<com.soltini.app.automation.GeofenceManager.LocationTriggerRule> {
        return com.soltini.app.automation.GeofenceManager.getInstance(context).getAllRules()
    }
}
