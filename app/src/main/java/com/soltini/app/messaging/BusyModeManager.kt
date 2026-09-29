package com.soltini.app.messaging

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.soltini.app.MainActivity
import com.soltini.app.notifications.ExtractedReplyAction
import com.soltini.app.security.VoiceProfileManager
import com.soltini.app.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AutoReplyLogItem
 * Records each autonomous auto-reply dispatched by Myra during Busy Mode.
 */
data class AutoReplyLogItem(
    val id: String = UUID.randomUUID().toString().take(8),
    val timestamp: Long = System.currentTimeMillis(),
    val sender: String,
    val appName: String,
    val packageName: String,
    val incomingText: String,
    val replyText: String
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("timestamp", timestamp)
        put("sender", sender)
        put("appName", appName)
        put("packageName", packageName)
        put("incomingText", incomingText)
        put("replyText", replyText)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): AutoReplyLogItem = AutoReplyLogItem(
            id = json.optString("id", ""),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            sender = json.optString("sender", "Unknown"),
            appName = json.optString("appName", "Chat"),
            packageName = json.optString("packageName", ""),
            incomingText = json.optString("incomingText", ""),
            replyText = json.optString("replyText", "")
        )
    }
}

/**
 * BusyModeSummary
 * Summary of actions taken when the user deactivates Busy Mode ("Main free ho gaya").
 */
data class BusyModeSummary(
    val sessionDurationMinutes: Long,
    val totalRepliedCount: Int,
    val uniqueSenders: List<String>,
    val logs: List<AutoReplyLogItem>
)

/**
 * BusyModeManager
 *
 * Autonomous Messaging Assistant that auto-replies to incoming messages across
 * WhatsApp, Instagram, Telegram, SMS, and other chat apps when the user is busy.
 *
 * Key Capabilities & Safety Guarantees:
 * 1. Self-Reply & Loop Prevention:
 *    - Strict sender filter: Never replies to "You", "Me", "Tu", "Myself", "आप", or any enrolled voice profile names (Harshit, Mummy, etc.).
 *    - Sent Fingerprint Cache: Caches all generated reply texts and substring tokens; never replies to an echo of its own sent message.
 *    - Acknowledgment & Closure Suppression: Ignores "ok", "acha", "cool", "bye", "done", "👍", etc. to prevent ping-pong loops.
 * 2. Rate Limiting & Cooldown:
 *    - 3-minute cooldown per sender.
 *    - Max 2 auto-replies per contact per busy session so conversations never become spammy.
 * 3. Contextual Responses:
 *    - Generates natural, polite Hindi/Hinglish responses tailored to the incoming inquiry (urgent, meeting, status, call request, general).
 * 4. Multi-App Direct Reply:
 *    - Employs Android RemoteInput via [ExtractedReplyAction] to reply instantly in the background without opening the screen or app.
 */
class BusyModeManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BusyModeManager"
        private const val PREFS_NAME = "myra_busy_mode_prefs"
        private const val KEY_BUSY_ACTIVE = "busy_active"
        private const val KEY_BUSY_REASON = "busy_reason"
        private const val KEY_BUSY_START_TIME = "busy_start_time"
        private const val KEY_AUTO_REPLY_HISTORY = "auto_reply_history"
        private const val KEY_USER_NAME = "user_name"
        private const val NOTIFICATION_ID_BUSY_MODE = 9021
        private const val CHANNEL_ID_BUSY_MODE = "myra_busy_mode_channel"

        // Cooldown between replies to the exact same sender in milliseconds (3 minutes)
        private const val SENDER_COOLDOWN_MS = 180_000L
        // Max auto-replies to the same contact during a single session
        private const val MAX_REPLIES_PER_CONTACT = 2

        @Volatile
        private var instance: BusyModeManager? = null

        fun getInstance(context: Context): BusyModeManager =
            instance ?: synchronized(this) {
                instance ?: BusyModeManager(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache of recent auto-reply text fingerprints to prevent self-reply loops
    private val sentReplyFingerprints = CopyOnWriteArrayList<String>()

    // Per-sender rate limiting: Map of "pkg:sender" -> lastRepliedTimestamp
    private val lastRepliedPerSender = ConcurrentHashMap<String, Long>()

    // Per-sender reply count in current session: Map of "pkg:sender" -> count
    private val replyCountPerSender = ConcurrentHashMap<String, Int>()

    // Active session auto-reply logs
    private val sessionLogs = CopyOnWriteArrayList<AutoReplyLogItem>()

    // Acknowledgment/Ping-Pong words that must NOT trigger an auto-reply
    private val PING_PONG_WORDS = setOf(
        "ok", "k", "okay", "oki", "okey", "kk", "acha", "achha", "achaa",
        "thik", "theek", "thik hai", "theek hai", "cool", "sahi", "badhiya",
        "bye", "tata", "alvida", "good night", "gn", "good morning", "gm",
        "done", "got it", "understood", "samajh gaya", "samajh gayi",
        "thanks", "thank you", "ty", "tysm", "dhanyawad", "shukriya",
        "hmm", "hmmm", "haan", "ha", "yes", "no", "nahi",
        "👍", "👌", "🙏", "❤️", "😊", "👋", "🤝", "done bro", "ok bro"
    )

    init {
        loadHistory()
        createNotificationChannel()
    }

    // ─── State Management ────────────────────────────────────────────────────

    fun isBusyModeActive(): Boolean =
        prefs.getBoolean(KEY_BUSY_ACTIVE, false)

    fun getBusyReason(): String =
        prefs.getString(KEY_BUSY_REASON, "busy") ?: "busy"

    fun getUserName(): String =
        prefs.getString(KEY_USER_NAME, "Harshit") ?: "Harshit"

    fun setUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name.trim()).apply()
    }

    /**
     * Activates Busy Mode.
     * @param reason Custom reason (e.g. "driving kar raha hoon", "meeting me hoon", "studying")
     */
    fun enableBusyMode(reason: String? = null): String {
        val sanitizedReason = cleanReason(reason)
        prefs.edit()
            .putBoolean(KEY_BUSY_ACTIVE, true)
            .putString(KEY_BUSY_REASON, sanitizedReason)
            .putLong(KEY_BUSY_START_TIME, System.currentTimeMillis())
            .apply()

        // Reset per-session rate limits
        lastRepliedPerSender.clear()
        replyCountPerSender.clear()
        sessionLogs.clear()

        showBusyModeNotification(sanitizedReason)
        AppLogger.i(TAG, "🟢 Busy Mode ACTIVATED with reason: '$sanitizedReason'")
        return sanitizedReason
    }

    /**
     * Deactivates Busy Mode and returns a summary of all handled messages.
     */
    fun disableBusyMode(): BusyModeSummary {
        val startTime = prefs.getLong(KEY_BUSY_START_TIME, System.currentTimeMillis())
        val durationMinutes = ((System.currentTimeMillis() - startTime) / (1000 * 60)).coerceAtLeast(1)

        prefs.edit()
            .putBoolean(KEY_BUSY_ACTIVE, false)
            .putLong(KEY_BUSY_START_TIME, 0L)
            .apply()

        hideBusyModeNotification()

        val logsCopy = sessionLogs.toList()
        val uniqueSenders = logsCopy.map { "${it.sender} (${it.appName})" }.distinct()

        AppLogger.i(TAG, "🔴 Busy Mode DEACTIVATED. Replied to ${logsCopy.size} messages across ${uniqueSenders.size} senders.")

        return BusyModeSummary(
            sessionDurationMinutes = durationMinutes,
            totalRepliedCount = logsCopy.size,
            uniqueSenders = uniqueSenders,
            logs = logsCopy
        )
    }

    fun getRecentHistory(limit: Int = 20): List<AutoReplyLogItem> =
        sessionLogs.takeLast(limit).reversed()

    // ─── Autonomous Notification Processing & Direct Reply ───────────────────

    /**
     * Intercepts incoming notifications posted on Android system.
     * If Busy Mode is active and notification is a replyable chat message from
     * WhatsApp, Instagram, Telegram, SMS, etc., Myra contextually replies.
     */
    fun processIncomingNotification(sbn: StatusBarNotification) {
        if (!isBusyModeActive()) return

        val pkg = sbn.packageName ?: return

        // 1. Never inspect or reply to Myra's own notifications
        if (pkg == context.packageName) return

        val notif = sbn.notification ?: return

        // 2. Ignore group summary headers (e.g. "WhatsApp 3 new messages")
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }

        val extras = notif.extras ?: return

        // Extract title (Sender name or group name)
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: ""

        // Extract message body
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: ""

        // Extract MessagingStyle messages if text is generic (e.g. "2 new messages")
        if (text.isBlank() || text.matches(Regex("""^\d+\s+new messages?$""", RegexOption.IGNORE_CASE))) {
            @Suppress("DEPRECATION")
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (messages != null && messages.isNotEmpty()) {
                val lastMsg = messages.lastOrNull()
                if (lastMsg is android.os.Bundle) {
                    val bundleText = lastMsg.getCharSequence("text")?.toString()
                    if (!bundleText.isNullOrBlank()) text = bundleText
                    val sender = lastMsg.getCharSequence("sender")?.toString()
                    if (!sender.isNullOrBlank() && title.isBlank()) title = sender
                }
            }
        }

        if (title.isBlank() && text.isBlank()) return

        val appName = resolveAppName(pkg)

        // ─── CRITICAL SAFETY: Loop & Self-Reply Prevention ───────────────────
        if (!shouldAutoReply(pkg, title, text)) {
            return
        }

        // Extract RemoteInput action for Direct Reply
        val replyAction = extractReplyAction(notif)
        if (replyAction == null) {
            Log.d(TAG, "Notification from $appName ($title) does not support RemoteInput direct reply.")
            return
        }

        // Sync user name from active profile if possible
        try {
            val profiles = VoiceProfileManager.getInstance(context).getAllProfiles()
            val ownerProfile = profiles.firstOrNull { it.role == com.soltini.app.security.ProfileRole.OWNER }
            if (ownerProfile != null) {
                setUserName(ownerProfile.name)
            }
        } catch (_: Exception) {}

        // Generate contextual Hinglish response
        val busyReason = getBusyReason()
        val userName = getUserName()
        val autoReplyText = generateContextualReply(userName, busyReason, text)

        // Record fingerprint before dispatch to avoid race conditions
        recordSentFingerprint(autoReplyText)

        // Send reply directly via Android RemoteInput without opening the app
        val success = replyAction.sendReply(context, autoReplyText)
        if (success) {
            val senderKey = "$pkg:${title.lowercase().trim()}"
            lastRepliedPerSender[senderKey] = System.currentTimeMillis()
            replyCountPerSender[senderKey] = (replyCountPerSender[senderKey] ?: 0) + 1

            val logItem = AutoReplyLogItem(
                sender = title.ifBlank { "Contact" },
                appName = appName,
                packageName = pkg,
                incomingText = text,
                replyText = autoReplyText
            )
            sessionLogs.add(logItem)
            saveHistory()

            AppLogger.i(TAG, "🚀 [Busy Mode Auto-Reply] Sent to $title via $appName: \"$autoReplyText\"")
            updateBusyModeNotification()
        } else {
            AppLogger.w(TAG, "Failed to dispatch RemoteInput auto-reply to $appName ($title)")
        }
    }

    /**
     * Comprehensive multi-layer filter ensuring Myra never self-replies or loops.
     */
    private fun shouldAutoReply(pkg: String, title: String, text: String): Boolean {
        val titleLower = title.lowercase().trim()
        val textLower = text.lowercase().trim()
        val userNameLower = getUserName().lowercase().trim()

        // 1. Self-Sender check: Check if message is from the user themselves or any enrolled profile
        val enrolledNames = try {
            VoiceProfileManager.getInstance(context).getAllProfiles().map { it.name.lowercase().trim() }
        } catch (_: Exception) {
            emptyList()
        }
        val allSelfNames = (listOf("harshit", "you", "me", "tu", "myself", "आप", "main", "mein") + enrolledNames + userNameLower).distinct()

        for (selfName in allSelfNames) {
            if (selfName.isNotBlank()) {
                if (titleLower == selfName || titleLower.startsWith("$selfName:") || titleLower.startsWith("$selfName :")) {
                    Log.d(TAG, "Skipping auto-reply: message is from self / enrolled user ('$title')")
                    return false
                }
            }
        }

        // 2. Sent Echo check: If message text starts with "You:" or matches recently sent reply
        if (textLower.startsWith("you:") || textLower.startsWith("you :") ||
            textLower.startsWith("आप:") || textLower.startsWith("आप :")) {
            Log.d(TAG, "Skipping auto-reply: message text is outgoing echo ('$text')")
            return false
        }

        // Check against sent fingerprints (substring and exact matches)
        for (fingerprint in sentReplyFingerprints) {
            val fpLower = fingerprint.lowercase().trim()
            if (fpLower.length >= 10) {
                val snippetStart = fpLower.take(20)
                val snippetEnd = fpLower.takeLast(20)
                if (textLower.contains(snippetStart) || textLower.contains(snippetEnd)) {
                    Log.d(TAG, "Skipping auto-reply: message matches recent outgoing reply snippet")
                    return false
                }
            }
            if (textLower == fpLower || textLower.contains(fpLower) || fpLower.contains(textLower)) {
                Log.d(TAG, "Skipping auto-reply: message matches recent outgoing reply fingerprint")
                return false
            }
        }

        // 3. Ping-Pong / Acknowledgment suppression
        // If the other party just says "ok", "thik hai", "bye", do NOT reply again!
        val cleanedText = textLower.replace(Regex("""[.,!?;:'"~-]"""), "").trim()
        if (PING_PONG_WORDS.contains(cleanedText) || cleanedText.length <= 2) {
            Log.d(TAG, "Skipping auto-reply: incoming message is acknowledgment/ping-pong ('$cleanedText')")
            return false
        }

        // 4. System / Non-chat notices suppression
        if (textLower.contains("checking for new messages") ||
            textLower.contains("backup in progress") ||
            textLower.contains("missed call") ||
            textLower.contains("calling") ||
            textLower.contains("deleted this message") ||
            textLower.contains("typing...") ||
            textLower.contains("end-to-end encrypted")
        ) {
            Log.d(TAG, "Skipping auto-reply: system status message ('$text')")
            return false
        }

        // 5. Rate Limiting & Cooldown: Max 2 replies per contact per session, and 3 min cooldown
        val senderKey = "$pkg:$titleLower"
        val count = replyCountPerSender[senderKey] ?: 0
        if (count >= MAX_REPLIES_PER_CONTACT) {
            Log.d(TAG, "Skipping auto-reply: reached maximum replies ($count) for '$titleLower' in this busy session")
            return false
        }

        val lastReplied = lastRepliedPerSender[senderKey] ?: 0L
        val elapsed = System.currentTimeMillis() - lastReplied
        if (elapsed < SENDER_COOLDOWN_MS) {
            Log.d(TAG, "Skipping auto-reply: sender '$titleLower' is in cooldown (${(SENDER_COOLDOWN_MS - elapsed) / 1000}s remaining)")
            return false
        }

        return true
    }

    /**
     * Generates a contextually appropriate, polite Hindi/Hinglish reply.
     */
    private fun generateContextualReply(userName: String, busyReason: String, incomingText: String): String {
        val lower = incomingText.lowercase()

        // Reason phrasing
        val reasonPhrase = when {
            busyReason.contains("drive") || busyReason.contains("driving") -> "driving kar rahe hain"
            busyReason.contains("meet") || busyReason.contains("meeting") -> "ek meeting me hain"
            busyReason.contains("study") || busyReason.contains("padh") -> "padhai kar rahe hain"
            busyReason.contains("gym") || busyReason.contains("workout") -> "gym me hain"
            busyReason.contains("sleep") || busyReason.contains("so") -> "aaram kar rahe hain"
            busyReason.isNotBlank() && busyReason != "busy" -> "$busyReason me busy hain"
            else -> "abhi busy hain"
        }

        // Context 1: Call Request / "Can I call?"
        if (lower.contains("call karu") || lower.contains("call karein") || lower.contains("phone uthao") ||
            lower.contains("call me") || lower.contains("can i call") || lower.contains("call attend")
        ) {
            return "Hello! $userName abhi $reasonPhrase aur call attend nahi kar sakte. Free hote hi aapse call par baat karenge."
        }

        // Context 2: Urgent / Emergency
        if (lower.contains("urgent") || lower.contains("emergency") || lower.contains("jaldi") || lower.contains("kripya")) {
            return "Hello! $userName abhi $reasonPhrase aur phone unke paas nahi hai. Agar bohot zaroori ya emergency hai to direct phone call kar lijiye."
        }

        // Context 3: Location / Where are you / Free ho?
        if (lower.contains("kahan") || lower.contains("kidhar") || lower.contains("free") || lower.contains("kab free") || lower.contains("where")) {
            return "Hey! $userName abhi $reasonPhrase. Jaise hi free honge, aapse turant connect karenge."
        }

        // Context 4: Work / Project / Task inquiry
        if (lower.contains("project") || lower.contains("work") || lower.contains("file") || lower.contains("update") || lower.contains("kaam")) {
            return "Hello, $userName abhi $reasonPhrase. Aapka message un tak pahunch gaya hai, free hote hi update share karenge."
        }

        // Context 5: Casual greeting / question ("Bhai", "Sun", "Hello", "Hi")
        return "Hello! $userName abhi $reasonPhrase. Message mil gaya hai, thodi der me reply karenge."
    }

    private fun recordSentFingerprint(replyText: String) {
        sentReplyFingerprints.add(replyText)
        if (sentReplyFingerprints.size > 80) {
            sentReplyFingerprints.removeAt(0)
        }
    }

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

    private fun resolveAppName(packageName: String): String = when {
        packageName.contains("whatsapp") -> "WhatsApp"
        packageName.contains("instagram") -> "Instagram"
        packageName.contains("telegram") -> "Telegram"
        packageName.contains("messaging") || packageName.contains("mms") -> "SMS"
        packageName.contains("signal") -> "Signal"
        packageName.contains("messenger") -> "Messenger"
        else -> try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            "Chat"
        }
    }

    private fun cleanReason(reason: String?): String {
        if (reason.isNullOrBlank()) return "busy"
        return reason.trim()
            .replace(Regex("""^(main|me|abhi|i\s+am)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(me|mein)\s+(hoon|hu|hun)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(hoon|hu|hun)$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "busy" }
    }

    private fun saveHistory() {
        val array = JSONArray()
        sessionLogs.takeLast(50).forEach { array.put(it.toJsonObject()) }
        prefs.edit().putString(KEY_AUTO_REPLY_HISTORY, array.toString()).apply()
    }

    private fun loadHistory() {
        sessionLogs.clear()
        val raw = prefs.getString(KEY_AUTO_REPLY_HISTORY, null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                sessionLogs.add(AutoReplyLogItem.fromJsonObject(array.getJSONObject(i)))
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID_BUSY_MODE,
                "Myra Busy Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active status when Myra is auto-replying to messages"
                setShowBadge(false)
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun showBusyModeNotification(reason: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(context, CHANNEL_ID_BUSY_MODE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Myra Busy Mode Active")
                .setContentText("Auto-replying: $reason (WhatsApp, Insta, Telegram, SMS)")
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            nm.notify(NOTIFICATION_ID_BUSY_MODE, notif)
        } catch (e: Exception) {
            Log.w(TAG, "Could not post Busy Mode notification: ${e.message}")
        }
    }

    private fun updateBusyModeNotification() {
        if (!isBusyModeActive()) return
        try {
            val count = sessionLogs.size
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val notif = NotificationCompat.Builder(context, CHANNEL_ID_BUSY_MODE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Myra Busy Mode Active")
                .setContentText("Auto-replied to $count messages (${getBusyReason()})")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(NOTIFICATION_ID_BUSY_MODE, notif)
        } catch (_: Exception) {}
    }

    private fun hideBusyModeNotification() {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID_BUSY_MODE)
        } catch (_: Exception) {}
    }
}
