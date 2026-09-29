package com.soltini.app.homeautomation.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Locale

/**
 * DeviceType
 *
 * Supported hardware device classifications for the ESP32 home automation system.
 * Governs UI icon display and default relay inversion behavior.
 */
enum class DeviceType {
    LIGHT,
    FAN,
    RELAY,
    LOCK,
    CUSTOM
}

/**
 * DeviceEntity
 *
 * Core Room Entity representing a single controllable hardware point (relay, light, fan, lock)
 * wired to a specific GPIO pin on an ESP32 board.
 *
 * Connections to other components:
 * - Read by DeviceDao for Room DB persistence.
 * - Queried by DeviceController during Gemini Live function calls to resolve deviceName -> MQTT topic.
 * - Consumed by Esp32CodeGenerator to produce board-level .ino sketches.
 * - Displayed and modified in AddDeviceScreen and DeviceListScreen.
 *
 * Shared Registry Guarantee:
 * Both the Gemini Live voice assistant and the Device Wizard read and write to this single table.
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceName: String,
    val roomName: String,
    val deviceType: DeviceType = DeviceType.LIGHT,
    val gpioPin: Int,
    val mqttTopicBase: String = deriveTopicBase(roomName, deviceName),
    val esp32ClientId: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Sanitizes room and device names into a clean MQTT topic hierarchy:
         * e.g., "Living Room", "Ceiling Light" -> "home/living_room/ceiling_light"
         */
        fun deriveTopicBase(roomName: String, deviceName: String): String {
            val cleanRoom = roomName.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]+"), "_").trim('_')
            val cleanDevice = deviceName.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]+"), "_").trim('_')
            val finalRoom = if (cleanRoom.isEmpty()) "default_room" else cleanRoom
            val finalDevice = if (cleanDevice.isEmpty()) "device" else cleanDevice
            return "home/$finalRoom/$finalDevice"
        }
    }
}
