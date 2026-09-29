package com.soltini.app.homeautomation.generator

import com.soltini.app.homeautomation.data.DeviceEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Esp32CodeGenerator
 *
 * Pure Kotlin generator that produces complete, production-ready Arduino C++ firmware (.ino)
 * for ESP32 microcontrollers.
 *
 * Architecture & Design:
 * - One firmware sketch is generated per physical board (`esp32ClientId`).
 * - All devices mapped to that board in Room's `DeviceEntity` table are compiled into a unified
 *   hardware configuration array in the sketch.
 * - Employs WiFiClientSecure + PubSubClient for encrypted TLS communication with HiveMQ Cloud.
 * - Subscribes to each device's specific command topic: `home/{roomName}/{deviceName}/set`.
 * - Publishes state confirmations back to `home/{roomName}/{deviceName}/state` as RETAINED messages,
 *   enabling immediate state sync whenever the Android app or another client connects.
 * - Contains robust WiFi & MQTT auto-reconnection loops and detailed Serial logging for debugging.
 *
 * Connections:
 * - Invoked by AddDeviceScreen and DeviceListScreen via HomeViewModel when the user clicks "Generate Firmware".
 * - Consumes `DeviceEntity` list from `DeviceDao.getByEsp32ClientId()`.
 * - Consumes encrypted credentials from `WifiMqttCredentialsStore`.
 */
object Esp32CodeGenerator {

    /**
     * Generates a complete, ready-to-flash Arduino C++ (.ino) sketch for a specific ESP32 board.
     *
     * @param devices List of DeviceEntity instances assigned to this physical board.
     * @param wifiSsid Target 2.4GHz WiFi SSID.
     * @param wifiPass WiFi WPA2 Pre-Shared Key.
     * @param mqttHost MQTT broker host (e.g. "xxxxxx.s1.eu.hivemq.cloud").
     * @param mqttPort MQTT broker TLS port (typically 8883 for HiveMQ Cloud).
     * @param mqttUser MQTT broker username.
     * @param mqttPass MQTT broker password.
     * @param clientId Board identifier (esp32ClientId).
     * @return Complete, compilation-ready C++ code.
     */
    fun generateSketch(
        devices: List<DeviceEntity>,
        wifiSsid: String,
        wifiPass: String,
        mqttHost: String,
        mqttPort: Int = 8883,
        mqttUser: String,
        mqttPass: String,
        clientId: String
    ): String {
        val boardDevices = devices.filter { it.esp32ClientId.equals(clientId, ignoreCase = true) }
            .ifEmpty { devices } // Fallback if filtered list is empty

        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        // Build the device struct array in C++
        val deviceStructs = StringBuilder()
        boardDevices.forEachIndexed { index, dev ->
            val setTopic = "${dev.mqttTopicBase}/set"
            val stateTopic = "${dev.mqttTopicBase}/state"
            deviceStructs.append(
                """  { "${dev.deviceName}", "${dev.roomName}", ${dev.gpioPin}, "$setTopic", "$stateTopic", false }"""
            )
            if (index < boardDevices.size - 1) deviceStructs.append(",\n")
        }

        return """/*
 * =====================================================================================
 *  SOLTINI AI HOME AUTOMATION — ESP32 FIRMWARE
 *  Board Client ID : $clientId
 *  Generated Date  : $dateStr
 *  Device Count    : ${boardDevices.size}
 * =====================================================================================
 *
 *  HARDWARE PREREQUISITES:
 *  - ESP32 Development Board (ESP32-WROOM-32, NodeMCU-32S, etc.)
 *  - 2.4 GHz WiFi Network (ESP32 does not support 5 GHz WiFi)
 *  - Relay Module(s) connected to assigned GPIO pins
 *
 *  REQUIRED ARDUINO IDE LIBRARIES (Install via Library Manager):
 *  1. PubSubClient by Nick O'Leary (v2.8+)
 *
 *  COMMUNICATION CONVENTION:
 *  - Command Topic : home/{room}/{device}/set   (Payload: "ON", "OFF", or "TOGGLE")
 *  - State Topic   : home/{room}/{device}/state (Payload: "ON" or "OFF", Retained: true)
 * =====================================================================================
 */

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>

// ── 1. NETWORK & MQTT CONFIGURATION ───────────────────────────────────────────────────
const char* WIFI_SSID     = "$wifiSsid";
const char* WIFI_PASSWORD = "$wifiPass";

const char* MQTT_BROKER   = "$mqttHost";
const int   MQTT_PORT     = $mqttPort;
const char* MQTT_USER     = "$mqttUser";
const char* MQTT_PASS     = "$mqttPass";
const char* MQTT_CLIENT_ID= "$clientId";

// Active-Low Relay configuration (Most standard 5V/3.3V relay boards trigger on LOW).
// Set this to false if your relay or transistor driver triggers on HIGH.
const bool RELAY_ACTIVE_LOW = true;

// ── 2. DEVICE STRUCT DEFINITION ───────────────────────────────────────────────────────
struct SmartDevice {
  const char* name;
  const char* room;
  int gpioPin;
  const char* setTopic;
  const char* stateTopic;
  bool currentState; // true = ON, false = OFF
};

// ── 3. ASSIGNED PIN MAPPING FOR THIS BOARD ────────────────────────────────────────────
SmartDevice devices[] = {
$deviceStructs
};
const int DEVICE_COUNT = sizeof(devices) / sizeof(devices[0]);

// ── 4. CLIENT INSTANCES ───────────────────────────────────────────────────────────────
WiFiClientSecure espClient;
PubSubClient mqttClient(espClient);

unsigned long lastReconnectAttempt = 0;
const unsigned long RECONNECT_INTERVAL_MS = 5000;

// ── 5. HARDWARE RELAY CONTROL HELPER ──────────────────────────────────────────────────
void applyPinState(int gpioPin, bool isOn) {
  if (RELAY_ACTIVE_LOW) {
    digitalWrite(gpioPin, isOn ? LOW : HIGH);
  } else {
    digitalWrite(gpioPin, isOn ? HIGH : LOW);
  }
}

// ── 6. STATE PUBLISHING (RETAINED) ───────────────────────────────────────────────────
void publishState(SmartDevice &dev) {
  const char* payload = dev.currentState ? "ON" : "OFF";
  // Publish with retained=true so any newly-connected app immediately reads current state
  mqttClient.publish(dev.stateTopic, payload, true);
  Serial.printf("[MQTT] State Published -> %s: %s\n", dev.stateTopic, payload);
}

// ── 7. MQTT INCOMING COMMAND HANDLER ─────────────────────────────────────────────────
void callback(char* topic, byte* payload, unsigned int length) {
  String message = "";
  for (unsigned int i = 0; i < length; i++) {
    message += (char)payload[i];
  }
  message.trim();
  message.toUpperCase();

  Serial.printf("\n[MQTT RECEIVED] Topic: %s | Payload: %s\n", topic, message.c_str());

  for (int i = 0; i < DEVICE_COUNT; i++) {
    if (strcmp(topic, devices[i].setTopic) == 0) {
      bool previousState = devices[i].currentState;

      if (message == "ON") {
        devices[i].currentState = true;
      } else if (message == "OFF") {
        devices[i].currentState = false;
      } else if (message == "TOGGLE") {
        devices[i].currentState = !devices[i].currentState;
      } else {
        Serial.printf("[WARN] Unknown command '%s' for device '%s'\n", message.c_str(), devices[i].name);
        return;
      }

      applyPinState(devices[i].gpioPin, devices[i].currentState);
      Serial.printf("[HARDWARE] %s (%s) GPIO %d -> %s\n", 
                    devices[i].name, devices[i].room, devices[i].gpioPin, 
                    devices[i].currentState ? "ON" : "OFF");

      publishState(devices[i]);
      return;
    }
  }
}

// ── 8. WIFI SETUP & CONNECTION ───────────────────────────────────────────────────────
void setup_wifi() {
  delay(100);
  Serial.println("\n-------------------------------------------");
  Serial.printf("[WIFI] Connecting to SSID: %s\n", WIFI_SSID);

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 40) {
    delay(500);
    Serial.print(".");
    attempts++;
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\n[WIFI] Connected successfully!");
    Serial.printf("[WIFI] IP Address: %s | RSSI: %d dBm\n", 
                  WiFi.localIP().toString().c_str(), WiFi.RSSI());
  } else {
    Serial.println("\n[ERROR] Failed to connect to WiFi. Check SSID & password.");
  }
  Serial.println("-------------------------------------------");
}

// ── 9. MQTT BROKER CONNECTION & TOPIC SUBSCRIPTIONS ──────────────────────────────────
boolean reconnect() {
  Serial.printf("[MQTT] Connecting to HiveMQ Broker: %s:%d...\n", MQTT_BROKER, MQTT_PORT);

  // Connect to HiveMQ Cloud TLS with credentials
  if (mqttClient.connect(MQTT_CLIENT_ID, MQTT_USER, MQTT_PASS)) {
    Serial.printf("[MQTT] Connected as Client ID: %s!\n", MQTT_CLIENT_ID);

    // Subscribe to command topic for each device on this board
    for (int i = 0; i < DEVICE_COUNT; i++) {
      mqttClient.subscribe(devices[i].setTopic, 1);
      Serial.printf("[MQTT] Subscribed -> %s\n", devices[i].setTopic);
      
      // Publish initial hardware state to broker
      publishState(devices[i]);
    }
    return true;
  } else {
    Serial.printf("[MQTT ERROR] Connection failed, rc=%d. Retrying in %lu ms...\n", 
                  mqttClient.state(), RECONNECT_INTERVAL_MS);
    return false;
  }
}

// ── 10. ARDUINO INITIAL SETUP ────────────────────────────────────────────────────────
void setup() {
  Serial.begin(115200);
  delay(500);

  Serial.println("\n===========================================");
  Serial.printf(" SOLTINI ESP32 NODE: %s\n", MQTT_CLIENT_ID);
  Serial.printf(" Devices Configured : %d\n", DEVICE_COUNT);
  Serial.println("===========================================");

  // Initialize GPIO pins as OUTPUT and set default safe state (OFF)
  for (int i = 0; i < DEVICE_COUNT; i++) {
    pinMode(devices[i].gpioPin, OUTPUT);
    devices[i].currentState = false;
    applyPinState(devices[i].gpioPin, false);
    Serial.printf("[PIN INIT] Configured GPIO %d as OUTPUT (OFF) for '%s'\n", 
                  devices[i].gpioPin, devices[i].name);
  }

  // Connect to WiFi
  setup_wifi();

  // Configure TLS connection for HiveMQ Cloud
  // Note: setInsecure() allows TLS handshake without bundling the root CA certificate.
  // In enterprise production, you can replace this with espClient.setCACert(root_ca).
  espClient.setInsecure();

  // Set MQTT Broker parameters and increase buffer size for topic paths
  mqttClient.setServer(MQTT_BROKER, MQTT_PORT);
  mqttClient.setCallback(callback);
  mqttClient.setBufferSize(512);

  lastReconnectAttempt = 0;
}

// ── 11. MAIN EXECUTION LOOP ──────────────────────────────────────────────────────────
void loop() {
  // Ensure WiFi is still connected
  if (WiFi.status() != WL_CONNECTED) {
    setup_wifi();
    return;
  }

  // Ensure MQTT is connected
  if (!mqttClient.connected()) {
    unsigned long now = millis();
    if (now - lastReconnectAttempt > RECONNECT_INTERVAL_MS) {
      lastReconnectAttempt = now;
      if (reconnect()) {
        lastReconnectAttempt = 0;
      }
    }
  } else {
    // Process incoming packets & maintain keep-alive ping
    mqttClient.loop();
  }
}
"""
    }
}
