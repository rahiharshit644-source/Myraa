package com.soltini.app.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.soltini.app.MainActivity
import com.soltini.app.homeautomation.control.DeviceController
import com.soltini.app.memory2.Memory2Database
import com.soltini.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * GeofenceBroadcastReceiver
 *
 * Catches location transition events (ENTER / EXIT) and triggers automated device or phone actions.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GeofenceReceiver"
        private const val CHANNEL_ID = "myra_geofence_channel"
        private const val CHANNEL_NAME = "MYRA Location Automation"
        private const val NOTIFICATION_ID = 4041
    }

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return
        if (geofencingEvent.hasError()) {
            AppLogger.e(TAG, "Geofencing error code: ${geofencingEvent.errorCode}")
            return
        }

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return

        val manager = GeofenceManager.getInstance(context)
        val deviceController = DeviceController.create(context)

        CoroutineScope(Dispatchers.IO).launch {
            for (geofence in triggeringGeofences) {
                val rule = manager.getRule(geofence.requestId) ?: continue
                val transitionName = when (transition) {
                    Geofence.GEOFENCE_TRANSITION_ENTER -> "Ghar/Area pahunche"
                    Geofence.GEOFENCE_TRANSITION_EXIT -> "Area se nikle"
                    else -> "Location change"
                }

                AppLogger.i(TAG, "Triggered rule '${rule.label}': ${rule.actionDescription} ($transitionName)")

                // 1. Execute target device action if specified
                if (!rule.targetDevice.isNullOrBlank() && !rule.targetAction.isNullOrBlank()) {
                    try {
                        deviceController.executeControl(
                            callId = "geofence_${rule.id}",
                            deviceName = rule.targetDevice,
                            actionRequested = rule.targetAction
                        )
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed executing device action for geofence: ${e.message}")
                    }
                }

                // 2. Post Proactive Notification
                val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                    )
                    notifManager.createNotificationChannel(channel)
                }

                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                )

                val message = "Boss, aap ${rule.label} pahunch gaye hain. Trigger '${rule.actionDescription}' execute kar diya gaya hai."

                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_map)
                    .setContentTitle("📍 Location Automation Triggered")
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()

                notifManager.notify(NOTIFICATION_ID + rule.id.hashCode() % 1000, notification)

                // 3. Record in Memory2Database
                try {
                    val db = Memory2Database.getInstance(context)
                    db.recordExperience(
                        taskType = "geofence_${rule.label}",
                        toolSequence = listOf("GeofenceManager", rule.actionDescription),
                        isSuccess = true
                    )
                } catch (_: Exception) {}
            }
        }
    }
}
