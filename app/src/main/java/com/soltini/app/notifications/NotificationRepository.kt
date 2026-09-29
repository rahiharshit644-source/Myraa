package com.soltini.app.notifications

import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NotificationRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "NotificationRepository"
        private const val PREFS_NAME = "soltini_notifications_prefs"
        private const val KEY_IGNORED_APPS = "ignored_apps"

        @Volatile
        private var INSTANCE: NotificationRepository? = null

        fun getInstance(context: Context): NotificationRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NotificationRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // By default, NO apps are ignored unless the user explicitly commands it!
    private val _ignoredApps = MutableStateFlow<Set<String>>(loadIgnoredApps())
    val ignoredApps: StateFlow<Set<String>> = _ignoredApps.asStateFlow()

    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()

    private fun loadIgnoredApps(): Set<String> {
        val stored = prefs.getStringSet(KEY_IGNORED_APPS, emptySet()) ?: emptySet()
        return stored.toSet()
    }

    fun isNotificationAccessGranted(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    /**
     * Add an app to the ignored list (by friendly name or package name).
     */
    fun ignoreApp(appNameOrPackage: String): Boolean {
        val clean = appNameOrPackage.trim().lowercase()
        if (clean.isBlank()) return false
        val updated = _ignoredApps.value.toMutableSet()
        updated.add(clean)
        _ignoredApps.value = updated
        prefs.edit().putStringSet(KEY_IGNORED_APPS, updated).apply()
        Log.i(TAG, "Added '$clean' to ignored apps. Current ignored: $updated")
        // Remove already captured notifications from this ignored app
        _notifications.value = _notifications.value.filterNot { isAppIgnored(it.packageName, it.appName) }
        return true
    }

    /**
     * Remove an app from the ignored list.
     */
    fun unignoreApp(appNameOrPackage: String): Boolean {
        val clean = appNameOrPackage.trim().lowercase()
        val updated = _ignoredApps.value.toMutableSet()
        val removed = updated.removeIf { it.equals(clean, ignoreCase = true) || it.contains(clean) || clean.contains(it) }
        if (removed) {
            _ignoredApps.value = updated
            prefs.edit().putStringSet(KEY_IGNORED_APPS, updated).apply()
            Log.i(TAG, "Removed '$clean' from ignored apps. Current ignored: $updated")
        }
        return removed
    }

    fun getIgnoredApps(): Set<String> = _ignoredApps.value

    /**
     * Checks if the app is marked as ignored by the user.
     * By default, returns false unless explicitly present in user's ignored list.
     */
    fun isAppIgnored(packageName: String, appName: String): Boolean {
        val set = _ignoredApps.value
        if (set.isEmpty()) return false
        val pkgLower = packageName.lowercase()
        val nameLower = appName.lowercase()
        return set.any { ignored ->
            val ign = ignored.lowercase()
            pkgLower == ign || nameLower == ign || pkgLower.contains(ign) || nameLower.contains(ign)
        }
    }

    /**
     * Parse incoming StatusBarNotification and update memory repository.
     */
    fun processIncomingNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return

        // 1. Never record Soltini's own persistent/service notifications
        if (pkg == context.packageName) return

        val appName = try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg
        }

        // 2. Check user's ignored apps list
        if (isAppIgnored(pkg, appName)) {
            Log.d(TAG, "Skipping notification from ignored app: $appName ($pkg)")
            return
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: Bundle()

        // Extract title (Sender name, group name, or subject)
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: ""

        // Extract message body
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: ""

        // Check for MessagingStyle conversation content (common in WhatsApp, Telegram, Google Messages)
        if (text.isBlank() || text.matches(Regex("^\\d+\\s+new messages?$", RegexOption.IGNORE_CASE))) {
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (messages != null && messages.isNotEmpty()) {
                val lastMsg = messages.lastOrNull()
                if (lastMsg is Bundle) {
                    val bundleText = lastMsg.getCharSequence("text")?.toString()
                    if (!bundleText.isNullOrBlank()) {
                        text = bundleText
                    }
                    val sender = lastMsg.getCharSequence("sender")?.toString()
                    if (!sender.isNullOrBlank() && title.isBlank()) {
                        title = sender
                    }
                }
            }
        }

        // Skip completely empty notifications or silent ongoing system background bars
        if (title.isBlank() && text.isBlank()) return
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0

        // Extract Direct Reply Action (RemoteInput)
        val replyAction = extractReplyAction(notification)
        val canReply = replyAction != null

        // Skip non-replyable ongoing system UI notices (like USB debugging or low battery warning)
        if (isOngoing && !canReply && (pkg == "android" || pkg == "com.android.systemui")) {
            return
        }

        val item = NotificationItem(
            key = sbn.key,
            packageName = pkg,
            appName = appName,
            title = title.ifBlank { appName },
            text = text,
            timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            canReply = canReply,
            replyAction = replyAction
        )

        // Prepend to notifications list, keeping max 35 recent items
        val currentList = _notifications.value.filterNot { it.key == sbn.key }.toMutableList()
        currentList.add(0, item)
        if (currentList.size > 35) {
            _notifications.value = currentList.take(35)
        } else {
            _notifications.value = currentList
        }
        Log.i(TAG, "Recorded notification from $appName ($title): \"$text\" (canReply=$canReply)")
    }

    /**
     * Removes notification from memory when dismissed.
     */
    fun removeNotification(key: String) {
        _notifications.value = _notifications.value.filterNot { it.key == key }
    }

    /**
     * Initial sync when NotificationListenerService connects.
     */
    fun syncActiveNotifications(activeList: Array<StatusBarNotification>?) {
        if (activeList == null) return
        activeList.forEach { sbn ->
            processIncomingNotification(sbn)
        }
    }

    /**
     * Get recent notifications filtered by app name if specified.
     */
    fun getRecentNotifications(limit: Int = 10, appFilter: String? = null): List<NotificationItem> {
        val filter = appFilter?.trim()?.lowercase()
        val all = _notifications.value
        return if (filter.isNullOrBlank()) {
            all.take(limit)
        } else {
            all.filter { notif ->
                notif.appName.lowercase().contains(filter) ||
                notif.packageName.lowercase().contains(filter) ||
                notif.title.lowercase().contains(filter)
            }.take(limit)
        }
    }

    /**
     * Reply directly to a notification using Android RemoteInput without opening the app.
     */
    fun replyToNotification(recipientOrApp: String, replyMessage: String): ReplyResult {
        if (replyMessage.isBlank()) {
            return ReplyResult.Failure("Reply message cannot be empty.")
        }

        val replyableList = _notifications.value.filter { it.canReply && it.replyAction != null }
        if (replyableList.isEmpty()) {
            return ReplyResult.Failure("No replyable notifications found. The app or sender might have already cleared the notification or does not support direct reply.")
        }

        val query = recipientOrApp.trim().lowercase()

        // 1. Try matching title/sender name
        var target: NotificationItem? = replyableList.firstOrNull {
            it.title.lowercase().contains(query) || query.contains(it.title.lowercase())
        }

        // 2. Try matching app name (e.g. WhatsApp, Telegram, Messages)
        if (target == null) {
            target = replyableList.firstOrNull {
                it.appName.lowercase().contains(query) || query.contains(it.appName.lowercase())
            }
        }

        // 3. Fallback for generic words like "latest", "last", "usse", "reply", or if only 1 replyable notification exists
        if (target == null && (query.contains("last") || query.contains("latest") || query.contains("recent") || query.contains("usse") || query.contains("unhe") || query.isBlank() || replyableList.size == 1)) {
            target = replyableList.firstOrNull()
        }

        if (target == null) {
            val available = replyableList.take(3).joinToString { "${it.title} (${it.appName})" }
            return ReplyResult.Failure("Could not find a notification from '$recipientOrApp' to reply to. Recent replyable senders: $available")
        }

        val action = target.replyAction ?: return ReplyResult.Failure("Reply action not available for ${target.title}.")
        val success = action.sendReply(context, replyMessage)

        return if (success) {
            ReplyResult.Success(
                recipient = target.title.ifBlank { target.appName },
                appName = target.appName,
                replyText = replyMessage
            )
        } else {
            ReplyResult.Failure("Failed to dispatch direct reply to ${target.appName}.")
        }
    }

    fun clearAllNotifications() {
        _notifications.value = emptyList()
    }

    /**
     * Finds the first Notification.Action that contains a RemoteInput.
     */
    private fun extractReplyAction(notification: Notification): ExtractedReplyAction? {
        val actions = notification.actions ?: return null
        for (action in actions) {
            val inputs = action.remoteInputs
            if (inputs != null && inputs.isNotEmpty()) {
                val pendingIntent = action.actionIntent ?: continue
                return ExtractedReplyAction(pendingIntent, inputs)
            }
        }
        return null
    }
}
