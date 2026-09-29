package com.soltini.app.homeautomation.model

import org.json.JSONObject

/**
 * ToolCallModels
 *
 * Strongly-typed data models representing incoming Gemini Live function calls
 * and outgoing function responses over the bi-directional WebSocket.
 *
 * Connections:
 * - Parsed from GeminiLiveManager server messages containing "toolCall".
 * - Dispatched to DeviceController for hardware execution.
 * - Serialized back to JSON for GeminiLiveManager.sendToolResponse().
 */

/**
 * Represents a single function call parsed from Gemini Live's "toolCall.functionCalls" array.
 */
data class GeminiFunctionCall(
    val id: String,
    val name: String,
    val args: JSONObject
)

/**
 * Valid actions supported by the control_device function.
 */
enum class DeviceAction {
    ON,
    OFF,
    TOGGLE;

    companion object {
        fun fromString(value: String): DeviceAction {
            return when (value.trim().uppercase()) {
                "ON", "1", "TRUE", "OPEN", "START" -> ON
                "OFF", "0", "FALSE", "CLOSE", "STOP" -> OFF
                "TOGGLE", "SWITCH" -> TOGGLE
                else -> TOGGLE
            }
        }
    }
}

/**
 * Extracted parameters from Gemini Live's control_device function invocation.
 */
data class DeviceControlCall(
    val callId: String,
    val deviceName: String,
    val action: DeviceAction
)

/**
 * Result of the control_device execution, formatted for Gemini's functionResponse.
 */
sealed class DeviceControlResult {
    abstract val callId: String
    abstract val functionName: String
    abstract fun toJsonObject(): JSONObject

    data class Success(
        override val callId: String,
        override val functionName: String = "control_device",
        val deviceName: String,
        val roomName: String,
        val appliedAction: String,
        val mqttTopic: String,
        val confirmationMessage: String
    ) : DeviceControlResult() {
        override fun toJsonObject(): JSONObject = JSONObject().apply {
            put("status", "success")
            put("device", deviceName)
            put("room", roomName)
            put("action", appliedAction)
            put("topic", mqttTopic)
            put("message", confirmationMessage)
        }
    }

    data class DeviceNotFound(
        override val callId: String,
        override val functionName: String = "control_device",
        val queriedName: String,
        val availableDevices: List<String>
    ) : DeviceControlResult() {
        override fun toJsonObject(): JSONObject = JSONObject().apply {
            put("status", "error")
            put("error_code", "DEVICE_NOT_FOUND")
            put("message", "Device '$queriedName' was not found in the home registry. Available devices: ${availableDevices.joinToString(", ")}. Please add it via the in-app Device Wizard.")
        }
    }

    data class Failure(
        override val callId: String,
        override val functionName: String = "control_device",
        val deviceName: String,
        val error: String
    ) : DeviceControlResult() {
        override fun toJsonObject(): JSONObject = JSONObject().apply {
            put("status", "error")
            put("error_code", "EXECUTION_FAILED")
            put("device", deviceName)
            put("message", "Failed to control device '$deviceName': $error")
        }
    }
}
