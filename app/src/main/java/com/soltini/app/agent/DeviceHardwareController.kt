package com.soltini.app.agent

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.util.Log
import android.view.KeyEvent
import org.json.JSONObject

/**
 * DeviceHardwareController
 *
 * Provides direct voice control over device hardware, settings, audio, and sensors:
 * - Flashlight / Torch (ON / OFF / TOGGLE)
 * - Volume (Set %, Increase, Decrease, Mute, Max)
 * - Media Playback (Play/Pause, Next, Previous)
 * - Ringer & DND Mode (Normal, Vibrate, Silent)
 * - Device & Battery Health Info (Battery %, Charging, Free Storage, RAM, Network)
 * - Alarms & Timers
 * - Clipboard (Read / Copy)
 */
class DeviceHardwareController(private val context: Context) {

    companion object {
        private const val TAG = "DeviceHardwareCtrl"
        @Volatile
        private var isTorchOn: Boolean = false
    }

    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val cameraManager by lazy { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val clipboardManager by lazy { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    // ─── Torch / Flashlight ───────────────────────────────────────────────────

    fun setTorch(enable: Boolean): JSONObject {
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                return error("Flashlight hardware not found on device")
            }

            cameraManager.setTorchMode(cameraId, enable)
            isTorchOn = enable
            JSONObject().apply {
                put("status", "success")
                put("torch_state", if (enable) "ON" else "OFF")
                put("message", if (enable) "Flashlight turned ON" else "Flashlight turned OFF")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to control torch: ${e.message}", e)
            error("Could not control flashlight: ${e.message}")
        }
    }

    fun toggleTorch(): JSONObject {
        return setTorch(!isTorchOn)
    }

    // ─── Sound & Volume ───────────────────────────────────────────────────────

    fun setVolume(percent: Int, streamType: Int = AudioManager.STREAM_MUSIC): JSONObject {
        return try {
            val maxVolume = audioManager.getStreamMaxVolume(streamType)
            val targetVolume = ((percent.coerceIn(0, 100) / 100f) * maxVolume).toInt()
            audioManager.setStreamVolume(streamType, targetVolume, AudioManager.FLAG_SHOW_UI)
            val actualPercent = (targetVolume * 100) / maxVolume

            JSONObject().apply {
                put("status", "success")
                put("volume_percent", actualPercent)
                put("message", "Volume set to $actualPercent%")
            }
        } catch (e: Exception) {
            error("Could not set volume: ${e.message}")
        }
    }

    fun adjustVolume(direction: String, streamType: Int = AudioManager.STREAM_MUSIC): JSONObject {
        return try {
            val dir = when (direction.uppercase()) {
                "UP", "INCREASE", "RAISE" -> AudioManager.ADJUST_RAISE
                "DOWN", "DECREASE", "LOWER" -> AudioManager.ADJUST_LOWER
                else -> AudioManager.ADJUST_SAME
            }
            audioManager.adjustStreamVolume(streamType, dir, AudioManager.FLAG_SHOW_UI)
            val current = audioManager.getStreamVolume(streamType)
            val max = audioManager.getStreamMaxVolume(streamType)
            val pct = (current * 100) / max

            JSONObject().apply {
                put("status", "success")
                put("volume_percent", pct)
                put("message", "Volume is now $pct%")
            }
        } catch (e: Exception) {
            error("Could not adjust volume: ${e.message}")
        }
    }

    fun setMute(mute: Boolean): JSONObject {
        return try {
            if (mute) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            } else {
                val half = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, half, AudioManager.FLAG_SHOW_UI)
            }
            JSONObject().apply {
                put("status", "success")
                put("muted", mute)
                put("message", if (mute) "Media sound muted" else "Media sound unmuted")
            }
        } catch (e: Exception) {
            error("Could not toggle mute: ${e.message}")
        }
    }

    // ─── Ringer Mode & DND ────────────────────────────────────────────────────

    fun setRingerMode(mode: String): JSONObject {
        return try {
            val targetMode = when (mode.uppercase()) {
                "SILENT" -> AudioManager.RINGER_MODE_SILENT
                "VIBRATE" -> AudioManager.RINGER_MODE_VIBRATE
                "NORMAL", "RING" -> AudioManager.RINGER_MODE_NORMAL
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            audioManager.ringerMode = targetMode

            JSONObject().apply {
                put("status", "success")
                put("ringer_mode", mode.uppercase())
                put("message", "Phone set to ${mode.uppercase()} mode")
            }
        } catch (e: Exception) {
            error("Could not change ringer mode: ${e.message}")
        }
    }

    // ─── Universal Media Controls ─────────────────────────────────────────────

    fun dispatchMediaKey(keyCode: Int): JSONObject {
        return try {
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)

            val name = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play/Pause"
                KeyEvent.KEYCODE_MEDIA_NEXT -> "Next track"
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Previous track"
                else -> "Media key $keyCode"
            }
            JSONObject().apply {
                put("status", "success")
                put("action", name)
                put("message", "Media action dispatched: $name")
            }
        } catch (e: Exception) {
            error("Could not dispatch media key: ${e.message}")
        }
    }

    // ─── Device Health & Battery Info ─────────────────────────────────────────

    fun getDeviceStatus(): JSONObject {
        return try {
            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val plugType = when (chargePlug) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC Wall Charger"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB Cable"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless Pad"
                else -> if (isCharging) "Charging" else "Unplugged"
            }

            // Storage info
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val availableBlocks = stat.availableBlocksLong
            val totalBlocks = stat.blockCountLong
            val freeGb = (availableBlocks * blockSize) / (1024 * 1024 * 1024)
            val totalGb = (totalBlocks * blockSize) / (1024 * 1024 * 1024)

            // Connectivity
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNetwork)
            val networkType = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile Data"
                else -> "Offline / Disconnected"
            }

            // Audio volumes
            val musicVol = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100) /
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val ringerModeStr = when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "Silent"
                AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                else -> "Normal"
            }

            JSONObject().apply {
                put("status", "success")
                put("battery_percent", batteryPct)
                put("is_charging", isCharging)
                put("power_source", plugType)
                put("free_storage_gb", freeGb)
                put("total_storage_gb", totalGb)
                put("network_type", networkType)
                put("media_volume_percent", musicVol)
                put("ringer_mode", ringerModeStr)
                put("flashlight_on", isTorchOn)
                put("summary", "Battery: $batteryPct% (${if (isCharging) "Charging via $plugType" else "Not charging"}), Free Storage: ${freeGb}GB of ${totalGb}GB, Network: $networkType, Volume: $musicVol%, Sound: $ringerModeStr")
            }
        } catch (e: Exception) {
            error("Could not inspect device status: ${e.message}")
        }
    }

    // ─── Alarms & Timers ──────────────────────────────────────────────────────

    fun setAlarm(hour: Int, minute: Int, message: String = "Alarm"): JSONObject {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val timeStr = String.format("%02d:%02d", hour, minute)
            JSONObject().apply {
                put("status", "success")
                put("alarm_time", timeStr)
                put("message", "Alarm set for $timeStr ($message)")
            }
        } catch (e: Exception) {
            error("Could not set alarm: ${e.message}")
        }
    }

    fun setTimer(lengthSeconds: Int, message: String = "Timer"): JSONObject {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, lengthSeconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val minutes = lengthSeconds / 60
            val seconds = lengthSeconds % 60
            val durText = if (minutes > 0) "$minutes min $seconds sec" else "$seconds sec"

            JSONObject().apply {
                put("status", "success")
                put("duration", durText)
                put("message", "Timer set for $durText ($message)")
            }
        } catch (e: Exception) {
            error("Could not set timer: ${e.message}")
        }
    }

    // ─── Clipboard ────────────────────────────────────────────────────────────

    fun getClipboard(): JSONObject {
        return try {
            val clip = clipboardManager.primaryClip
            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context).toString()
            } else ""

            JSONObject().apply {
                put("status", "success")
                put("has_content", text.isNotBlank())
                put("text", text)
                put("message", if (text.isNotBlank()) "Clipboard content: \"$text\"" else "Clipboard is empty")
            }
        } catch (e: Exception) {
            error("Could not read clipboard: ${e.message}")
        }
    }

    fun copyToClipboard(text: String, label: String = "Soltini"): JSONObject {
        return try {
            val clip = ClipData.newPlainText(label, text)
            clipboardManager.setPrimaryClip(clip)
            JSONObject().apply {
                put("status", "success")
                put("copied_text", text)
                put("message", "Text copied to clipboard")
            }
        } catch (e: Exception) {
            error("Could not copy to clipboard: ${e.message}")
        }
    }

    private fun error(msg: String): JSONObject = JSONObject().apply {
        put("status", "error")
        put("message", msg)
    }
}
