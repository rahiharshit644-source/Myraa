package com.soltini.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.soltini.app.overlay.OverlayService
import com.soltini.app.services.BackgroundVoiceService

/**
 * BootReceiver
 *
 * Listens for BOOT_COMPLETED (and OEM equivalents like QUICKBOOT_POWERON on HTC/OnePlus)
 * and starts the Soltini foreground services automatically after device reboot.
 *
 * START_STICKY only handles app-kill-and-restart within a session. This receiver
 * handles the cold-boot case where the OS has never started our process yet.
 *
 * NOTE: Some OEM launchers (Xiaomi MIUI, Huawei EMUI, Samsung One UI) require the user
 * to additionally enable "Autostart" in their device's Battery / App settings.
 * We cannot override that restriction from code.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SoltiniBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // Accept standard boot + OEM quick-boot variants + screen unlock & power events
        val isBootAction = action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == Intent.ACTION_USER_PRESENT ||
                action == Intent.ACTION_POWER_CONNECTED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||          // HTC
                action == "com.htc.intent.action.QUICKBOOT_POWERON" ||          // HTC (legacy)
                action == "android.intent.action.MY_PACKAGE_REPLACED" ||        // APK update restart
                action == "com.soltini.app.RESTART_SERVICE"                     // Custom task removed restart

        if (!isBootAction) return

        Log.i(TAG, "Boot/revival event received (action=$action) — ensuring Soltini services are active")

        // ── Start BackgroundVoiceService ─────────────────────────────────────
        try {
            if (!BackgroundVoiceService.isRunning()) {
                val voiceIntent = Intent(context, BackgroundVoiceService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(voiceIntent)
                } else {
                    context.startService(voiceIntent)
                }
                Log.i(TAG, "BackgroundVoiceService started after $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BackgroundVoiceService: ${e.message}", e)
        }

        // ── Start OverlayService (only if overlay permission is still granted) ─
        try {
            if (Settings.canDrawOverlays(context) && OverlayService.getInstance() == null) {
                val overlayIntent = Intent(context, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(overlayIntent)
                } else {
                    context.startService(overlayIntent)
                }
                Log.i(TAG, "OverlayService started after $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OverlayService: ${e.message}", e)
        }

        // ── Reschedule All Persistent Scheduled Tasks ───────────────────────────
        try {
            com.soltini.app.scheduler.ScheduledTaskManager.getInstance(context).rescheduleAllPendingTasks()
            Log.i(TAG, "ScheduledTaskManager: pending tasks rescheduled after $action")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule pending tasks: ${e.message}", e)
        }
    }
}
