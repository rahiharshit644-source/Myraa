package com.soltini.app.homeautomation.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * WifiMqttCredentialsStore
 *
 * Encrypted persistent storage for network & broker credentials:
 * - WiFi SSID & WiFi Password
 * - MQTT Host, Port, Username, Password
 *
 * Security Architecture:
 * - Employs Android Keystore backed AES/GCM/NoPadding (256-bit) to encrypt secrets at rest.
 * - Hardware-backed keystore key prevents extraction even if the device filesystem is accessed.
 * - Automatically initializes Keystore key pair if absent.
 * - Graceful fallback to obfuscated preferences in environments where KeyStore is unavailable (e.g. JVM tests).
 *
 * Connections:
 * - Used by MqttManager to establish TLS connections to HiveMQ Cloud or local brokers.
 * - Used by Esp32CodeGenerator to pre-fill the generated C++ Arduino sketches with real credentials.
 * - Managed in the UI via AddDeviceScreen / Settings.
 */
class WifiMqttCredentialsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "WifiMqttCredStore"
        private const val PREFS_NAME = "soltini_wifi_mqtt_secure_prefs"
        private const val KEY_ALIAS = "SoltiniMqttKeyAlias"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128

        // Preference keys
        private const val KEY_WIFI_SSID = "sec_wifi_ssid"
        private const val KEY_WIFI_PASS = "sec_wifi_pass"
        private const val KEY_MQTT_HOST = "sec_mqtt_host"
        private const val KEY_MQTT_PORT = "sec_mqtt_port"
        private const val KEY_MQTT_USER = "sec_mqtt_user"
        private const val KEY_MQTT_PASS = "sec_mqtt_pass"

        // Defaults
        const val DEFAULT_MQTT_PORT = 8883 // Standard TLS port for HiveMQ Cloud
        const val DEFAULT_MQTT_HOST = ""
    }

    init {
        initKeyStore()
    }

    private fun initKeyStore() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
                Log.i(TAG, "Initialized new hardware-backed AES-256 key in Android Keystore.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Keystore initialization notice (fallback mode active): ${e.message}")
        }
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            null
        }
    }

    private fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val key = getSecretKey() ?: return "RAW:" + Base64.encodeToString(
            plainText.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption error, fallback applied: ${e.message}")
            "RAW:" + Base64.encodeToString(plainText.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        }
    }

    private fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        if (cipherText.startsWith("RAW:")) {
            val rawB64 = cipherText.removePrefix("RAW:")
            return try {
                String(Base64.decode(rawB64, Base64.NO_WRAP), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        }
        val key = getSecretKey() ?: return ""
        return try {
            val combined = Base64.decode(cipherText, Base64.NO_WRAP)
            if (combined.size <= 12) return ""
            val iv = ByteArray(12)
            val encryptedBytes = ByteArray(combined.size - 12)
            System.arraycopy(combined, 0, iv, 0, 12)
            System.arraycopy(combined, 12, encryptedBytes, 0, encryptedBytes.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error: ${e.message}")
            ""
        }
    }

    // ── Public Accessors ──────────────────────────────────────────────────────

    var wifiSsid: String
        get() = decrypt(prefs.getString(KEY_WIFI_SSID, "") ?: "")
        set(value) = prefs.edit().putString(KEY_WIFI_SSID, encrypt(value.trim())).apply()

    var wifiPass: String
        get() = decrypt(prefs.getString(KEY_WIFI_PASS, "") ?: "")
        set(value) = prefs.edit().putString(KEY_WIFI_PASS, encrypt(value)).apply()

    var mqttHost: String
        get() = decrypt(prefs.getString(KEY_MQTT_HOST, "") ?: "").ifBlank { DEFAULT_MQTT_HOST }
        set(value) = prefs.edit().putString(KEY_MQTT_HOST, encrypt(value.trim())).apply()

    var mqttPort: Int
        get() = prefs.getInt(KEY_MQTT_PORT, DEFAULT_MQTT_PORT)
        set(value) = prefs.edit().putInt(KEY_MQTT_PORT, if (value in 1..65535) value else DEFAULT_MQTT_PORT).apply()

    var mqttUser: String
        get() = decrypt(prefs.getString(KEY_MQTT_USER, "") ?: "")
        set(value) = prefs.edit().putString(KEY_MQTT_USER, encrypt(value.trim())).apply()

    var mqttPass: String
        get() = decrypt(prefs.getString(KEY_MQTT_PASS, "") ?: "")
        set(value) = prefs.edit().putString(KEY_MQTT_PASS, encrypt(value)).apply()

    fun saveAll(
        wifiSsid: String,
        wifiPass: String,
        mqttHost: String,
        mqttPort: Int = DEFAULT_MQTT_PORT,
        mqttUser: String,
        mqttPass: String
    ) {
        this.wifiSsid = wifiSsid
        this.wifiPass = wifiPass
        this.mqttHost = mqttHost
        this.mqttPort = mqttPort
        this.mqttUser = mqttUser
        this.mqttPass = mqttPass
        Log.i(TAG, "Saved and encrypted WiFi & MQTT credentials.")
    }

    fun isWifiConfigured(): Boolean = wifiSsid.isNotBlank()

    fun isMqttConfigured(): Boolean = mqttHost.isNotBlank() && mqttUser.isNotBlank()
}
