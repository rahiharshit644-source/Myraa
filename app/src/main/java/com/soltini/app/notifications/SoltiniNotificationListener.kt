package com.soltini.app.notifications

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * System NotificationListenerService to intercept incoming notifications
 * and provide Direct Reply capabilities via RemoteInput.
 */
class SoltiniNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "SoltiniNotifListener"

        @Volatile
        private var instance: SoltiniNotificationListener? = null

        fun getInstance(): SoltiniNotificationListener? = instance

        fun rebindIfNeeded(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestRebind(ComponentName(context, SoltiniNotificationListener::class.java))
                    Log.i(TAG, "Requested rebind for SoltiniNotificationListener")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not requestRebind: ${e.message}")
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "SoltiniNotificationListener successfully connected")
        try {
            val currentActive = activeNotifications
            NotificationRepository.getInstance(applicationContext).syncActiveNotifications(currentActive)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing active notifications on connect: ${e.message}", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "SoltiniNotificationListener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        try {
            com.soltini.app.telephony.CallNotificationManager.getInstance(applicationContext).onNotificationPosted(sbn)
            NotificationRepository.getInstance(applicationContext).processIncomingNotification(sbn)
            com.soltini.app.messaging.BusyModeManager.getInstance(applicationContext).processIncomingNotification(sbn)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming notification: ${e.message}", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        try {
            com.soltini.app.telephony.CallNotificationManager.getInstance(applicationContext).onNotificationRemoved(sbn)
            NotificationRepository.getInstance(applicationContext).removeNotification(sbn.key)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing notification: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }
}
