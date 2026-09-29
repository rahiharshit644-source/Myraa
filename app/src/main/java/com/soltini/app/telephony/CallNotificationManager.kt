package com.soltini.app.telephony

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.soltini.app.agent.SoltiniAccessibilityService
import org.json.JSONObject

/**
 * Resolved Contact representation.
 */
data class ContactMatch(
    val name: String,
    val phoneNumber: String
)

/**
 * Detailed result of answering/rejecting a call.
 */
data class CallActionResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * Current Phone Call Snapshot.
 */
data class CurrentCallInfo(
    val isIncoming: Boolean,
    val callerName: String?,
    val phoneNumber: String?,
    val callState: String, // "RINGING", "OFFHOOK", "IDLE"
    val isDefaultDialer: Boolean
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("isIncoming", isIncoming)
        put("callerName", callerName ?: "")
        put("phoneNumber", phoneNumber ?: "")
        put("callState", callState)
        put("isDefaultDialer", isDefaultDialer)
    }
}

/**
 * CallNotificationManager
 *
 * Central coordinator for:
 * 1. Detecting incoming phone calls and caller identity (via Telephony + NotificationListener + Broadcast).
 * 2. Announcing incoming calls aloud via Myra's voice ("Boss, [Caller] ka call aa raha hai...").
 * 3. Answering incoming calls hands-free by voice ("call uthao" -> answerCall()).
 * 4. Rejecting incoming calls hands-free by voice ("call kaat do" -> rejectCall()).
 * 5. Querying active call state and caller identity ("Kaun call kar raha hai?").
 */
class CallNotificationManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CallNotificationMgr"

        @Volatile
        private var instance: CallNotificationManager? = null

        fun getInstance(context: Context): CallNotificationManager {
            return instance ?: synchronized(this) {
                instance ?: CallNotificationManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var onIncomingCallDetected: ((callerName: String, phoneNumber: String?) -> Unit)? = null

    @Volatile
    var isRinging: Boolean = false
        private set

    @Volatile
    var currentCallerName: String? = null
        private set

    @Volatile
    var currentPhoneNumber: String? = null
        private set

    @Volatile
    var currentCallState: String = "IDLE"
        private set

    @Volatile
    var lastOutgoingCallTime: Long = 0L
        private set

    @Volatile
    var isOutgoingCallActive: Boolean = false
        private set

    fun markOutgoingCallInitiated(numberOrName: String? = null) {
        lastOutgoingCallTime = System.currentTimeMillis()
        isOutgoingCallActive = true
        isRinging = false
        currentCallState = "OFFHOOK"
        Log.i(TAG, "Outgoing call initiated to $numberOrName, suppressing incoming call alerts for outgoing dialing")
    }

    private var answerPendingIntent: PendingIntent? = null
    private var rejectPendingIntent: PendingIntent? = null
    private var lastAnnouncedCaller: String? = null
    private var lastAnnouncedTime: Long = 0L

    private val ttsSpeaker = TtsSpeaker(context)
    private var telephonyCallback: Any? = null
    private var isListening = false

    /**
     * Checks if MYRA is the default dialer / phone app.
     */
    fun isDefaultDialer(): Boolean {
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage == context.packageName
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Snapshot of the current call state and caller identity.
     * Uses Telecom/Telephony APIs, ContactsContract, active notifications, and CallLog.
     */
    fun getCurrentCallInfo(): CurrentCallInfo {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasPhonePerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        var liveState = currentCallState
        if (hasPhonePerm && tm != null) {
            liveState = try {
                when (tm.callState) {
                    TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
                    TelephonyManager.CALL_STATE_IDLE -> "IDLE"
                    else -> currentCallState
                }
            } catch (_: Exception) {
                currentCallState
            }
        }

        val ringing = (liveState == "RINGING" || isRinging) && liveState != "IDLE"

        var name = currentCallerName
        var number = currentPhoneNumber

        // If ringing but caller identity not established, query active notifications and recent call log
        if (ringing && (name.isNullOrBlank() || name.equals("Unknown Caller", ignoreCase = true))) {
            // Check active notifications
            try {
                val notifListener = com.soltini.app.notifications.SoltiniNotificationListener.getInstance()
                if (notifListener != null) {
                    val active = notifListener.activeNotifications
                    for (sbn in active) {
                        val notif = sbn.notification ?: continue
                        if (notif.category == Notification.CATEGORY_CALL ||
                            sbn.packageName.contains("dialer", ignoreCase = true) ||
                            sbn.packageName.contains("telecom", ignoreCase = true) ||
                            sbn.packageName.contains("phone", ignoreCase = true)) {
                            val title = notif.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                            val text = notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                            if (!title.isNullOrBlank() && !title.contains("call", ignoreCase = true)) {
                                name = resolveContactName(title)
                                if (name != title && number.isNullOrBlank()) number = title
                                break
                            } else if (!text.isNullOrBlank() && !text.contains("call", ignoreCase = true)) {
                                name = resolveContactName(text)
                                if (name != text && number.isNullOrBlank()) number = text
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Active notification caller inspection failed: ${e.message}")
            }

            // Check CallLog for latest ringing call within last 30 seconds
            if (name.isNullOrBlank() || name.equals("Unknown Caller", ignoreCase = true)) {
                val hasCallLogPerm = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_CALL_LOG
                ) == PackageManager.PERMISSION_GRANTED
                if (hasCallLogPerm) {
                    try {
                        val projection = arrayOf(
                            android.provider.CallLog.Calls.NUMBER,
                            android.provider.CallLog.Calls.CACHED_NAME,
                            android.provider.CallLog.Calls.DATE
                        )
                        context.contentResolver.query(
                            android.provider.CallLog.Calls.CONTENT_URI,
                            projection,
                            null,
                            null,
                            "${android.provider.CallLog.Calls.DATE} DESC"
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val dateIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.DATE)
                                val callTime = if (dateIdx != -1) cursor.getLong(dateIdx) else 0L
                                if (System.currentTimeMillis() - callTime < 30_000L) {
                                    val numIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                                    val nameIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
                                    val logNum = if (numIdx != -1) cursor.getString(numIdx) else null
                                    val logName = if (nameIdx != -1) cursor.getString(nameIdx) else null
                                    if (!logName.isNullOrBlank()) {
                                        name = logName
                                    } else if (!logNum.isNullOrBlank()) {
                                        name = resolveContactName(logNum)
                                    }
                                    if (number.isNullOrBlank()) number = logNum
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "CallLog query failed: ${e.message}")
                    }
                }
            }
        }

        return CurrentCallInfo(
            isIncoming = ringing,
            callerName = name,
            phoneNumber = number,
            callState = liveState,
            isDefaultDialer = isDefaultDialer()
        )
    }

    /**
     * Searches Android Contacts for [nameQuery].
     * Returns a list of all matching contacts with name and formatted number.
     */
    fun searchContacts(nameQuery: String): List<ContactMatch> {
        val trimmed = nameQuery.trim()
        if (trimmed.isBlank()) return emptyList()

        // If input contains 7+ digits and looks like a raw phone number, return it directly
        val digitsOnly = trimmed.replace(Regex("[^0-9+]"), "")
        if (digitsOnly.length >= 7 && (trimmed.startsWith("+") || trimmed.all { it.isDigit() || it.isWhitespace() || it == '-' })) {
            return listOf(ContactMatch(name = trimmed, phoneNumber = digitsOnly))
        }

        val hasContactsPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContactsPerm) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return if (digitsOnly.length >= 5) listOf(ContactMatch(name = trimmed, phoneNumber = digitsOnly)) else emptyList()
        }

        val matches = mutableListOf<ContactMatch>()
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$trimmed%")

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx != -1) cursor.getString(nameIdx) ?: "" else ""
                    val rawNum = if (numIdx != -1) cursor.getString(numIdx) ?: "" else ""
                    val cleanNum = rawNum.replace(Regex("[^0-9+]"), "")
                    if (name.isNotBlank() && cleanNum.isNotBlank()) {
                        // Avoid duplicates
                        if (matches.none { it.name.equals(name, ignoreCase = true) && it.phoneNumber == cleanNum }) {
                            matches.add(ContactMatch(name = name, phoneNumber = cleanNum))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "searchContacts error for query '$trimmed': ${e.message}", e)
        }

        return matches
    }

    /**
     * Resolves contact display name from phone number if READ_CONTACTS is granted.
     */
    fun resolveContactName(phoneNumber: String?): String {
        if (phoneNumber.isNullOrBlank()) return "Unknown Caller"

        val hasContactsPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPerm) {
            return phoneNumber
        }

        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) {
                            return name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve contact name: ${e.message}")
        }
        return phoneNumber
    }

    /**
     * Starts listening to phone state changes.
     */
    fun startListening() {
        if (isListening) return

        val hasPhonePerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPhonePerm) {
            Log.d(TAG, "READ_PHONE_STATE not granted yet; telephony listener registration deferred.")
            return
        }

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state, null)
                    }
                }
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
                telephonyCallback = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state, phoneNumber)
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                telephonyCallback = listener
            }
            isListening = true
            Log.i(TAG, "Telephony call state listener registered successfully")
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_PHONE_STATE permission denied or revoked: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register telephony listener: ${e.message}")
        }
    }

    fun stopListening() {
        if (!isListening) return
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let {
                    telephonyManager?.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                (telephonyCallback as? PhoneStateListener)?.let {
                    telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (_: Exception) {}
        telephonyCallback = null
        isListening = false
    }

    private fun handleCallState(state: Int, phoneNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val now = System.currentTimeMillis()
                if (isOutgoingCallActive || (now - lastOutgoingCallTime < 60_000L)) {
                    Log.i(TAG, "Ignoring CALL_STATE_RINGING because outgoing call is active / dialing (${now - lastOutgoingCallTime}ms ago)")
                    return
                }
                currentCallState = "RINGING"
                val name = resolveContactName(phoneNumber)
                onIncomingCall(name, phoneNumber)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                currentCallState = "IDLE"
                onCallEnded()
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                currentCallState = "OFFHOOK"
                onCallOffhook()
            }
        }
    }

    /**
     * Triggered when an incoming call is detected (from TelephonyCallback, BroadcastReceiver, or NotificationListener).
     */
    fun onIncomingCall(callerName: String, phoneNumber: String?) {
        val now = System.currentTimeMillis()
        if (isOutgoingCallActive || (now - lastOutgoingCallTime < 60_000L)) {
            Log.i(TAG, "Suppressed false incoming call alert for '$callerName': outgoing call in progress or initiated ${now - lastOutgoingCallTime}ms ago")
            return
        }
        // Debounce if same caller announced within 8 seconds
        if (isRinging && callerName.equals(lastAnnouncedCaller, ignoreCase = true) && (now - lastAnnouncedTime < 8000)) {
            Log.d(TAG, "Skipping duplicate incoming call alert for $callerName")
            return
        }

        isRinging = true
        currentCallState = "RINGING"
        currentCallerName = callerName
        currentPhoneNumber = phoneNumber
        lastAnnouncedCaller = callerName
        lastAnnouncedTime = now

        val isKnownContact = callerName.isNotBlank() &&
                !callerName.equals("Unknown Caller", ignoreCase = true) &&
                !callerName.equals("Incoming Call", ignoreCase = true) &&
                callerName != phoneNumber

        val announcement = if (isKnownContact) {
            "Boss, $callerName ka call aa raha hai."
        } else {
            "Boss, unknown number se call aa raha hai."
        }

        Log.i(TAG, "🚨 INCOMING CALL DETECTED: Caller='$callerName', Phone='$phoneNumber', Known=$isKnownContact")

        // Dispatch to listener (BackgroundVoiceService -> Gemini Live WebSocket)
        val listener = onIncomingCallDetected
        if (listener != null) {
            listener.invoke(callerName, phoneNumber)
        } else {
            // When no listener or offline, do not speak with robotic TTS (only Myra speaks when online)
            Log.i(TAG, "Incoming call detected but voice listener is null; robotic TTS suppressed.")
        }
    }

    fun onCallOffhook() {
        isRinging = false
        currentCallState = "OFFHOOK"
        Log.i(TAG, "Call answered / in progress (OFFHOOK)")
    }

    fun onCallEnded() {
        isRinging = false
        isOutgoingCallActive = false
        currentCallState = "IDLE"
        currentCallerName = null
        currentPhoneNumber = null
        answerPendingIntent = null
        rejectPendingIntent = null
        lastAnnouncedCaller = null
        Log.i(TAG, "Call ended / IDLE")
    }

    fun speakViaTts(text: String) {
        ttsSpeaker.speak(text)
    }

    /**
     * Called by SoltiniNotificationListener when a notification is posted.
     * Detects incoming call notifications (dialer, WhatsApp, etc.) and extracts actions.
     */
    fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        val notif = sbn.notification ?: return
        val isCallCategory = notif.category == Notification.CATEGORY_CALL
        val actions = notif.actions

        val title = notif.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: notif.extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: ""
        val text = notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: notif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""
        val subText = notif.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val combined = "$title $text $subText".lowercase()

        var hasAnswer = false
        var hasDecline = false

        if (actions != null) {
            for (action in actions) {
                val aTitle = action.title?.toString()?.lowercase() ?: ""
                if (aTitle.contains("answer") || aTitle.contains("accept") || aTitle.contains("receive") || aTitle.contains("attend")) {
                    answerPendingIntent = action.actionIntent
                    hasAnswer = true
                } else if (aTitle.contains("decline") || aTitle.contains("reject") || aTitle.contains("dismiss") || aTitle.contains("end") || aTitle.contains("hang up")) {
                    rejectPendingIntent = action.actionIntent
                    hasDecline = true
                }
            }
        }

        // Outgoing or ongoing call indicators
        val isExplicitOutgoingOrOngoing = combined.contains("calling") ||
                combined.contains("dialing") ||
                combined.contains("outgoing") ||
                combined.contains("call in progress") ||
                combined.contains("ongoing call") ||
                combined.contains("connected") ||
                combined.contains("on hold") ||
                combined.contains("connecting") ||
                combined.contains("ringing") ||
                combined.contains("कॉल की जा रही") ||
                combined.contains("डायल") ||
                combined.contains("चल रही कॉल") ||
                isOutgoingCallActive ||
                (System.currentTimeMillis() - lastOutgoingCallTime < 60_000L)

        // If it's an outgoing or ongoing call notification, or lacks an Answer action,
        // it can never be an incoming ringing call!
        if (isExplicitOutgoingOrOngoing || !hasAnswer) {
            if (isCallCategory || sbn.packageName.contains("dialer") || sbn.packageName.contains("phone") || sbn.packageName.contains("telecom") || sbn.packageName.contains("whatsapp")) {
                Log.d(TAG, "Call notification is for outgoing or ongoing call (not incoming): title='$title', text='$text'")
                markOutgoingCallInitiated(title)
                onCallOffhook()
            }
            return
        }

        // A genuine incoming call notification MUST have an Answer action button and must not be an outgoing call
        if (hasAnswer && !isExplicitOutgoingOrOngoing) {
            val callerRaw = title.trim().ifBlank { text.trim().ifBlank { "Incoming Call" } }
            val callerClean = callerRaw
                .replace(Regex("""^(?:incoming\s+call\s+from|incoming\s+call|call\s+from)\s*""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { "Incoming Call" }

            Log.i(TAG, "Incoming call notification intercepted: caller=$callerClean, pkg=${sbn.packageName}")
            onIncomingCall(callerClean, null)
        }
    }

    fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification) {
        val notif = sbn.notification
        if (notif?.category == Notification.CATEGORY_CALL) {
            onCallEnded()
        }
    }

    /**
     * Polls TelephonyManager call state for up to [timeoutMs] to verify state transition.
     */
    private fun verifyCallStateBecomes(expectedState: Int, timeoutMs: Long = 800L): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return true
        val hasPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            // Cannot read state directly without permission, assume true if action dispatched
            return true
        }

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                val state = tm.callState
                if (state == expectedState) return true
            } catch (_: Exception) {
                return true
            }
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                break
            }
        }
        return try {
            tm.callState == expectedState
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Answers the ringing phone call with layered execution and verified outcome.
     * Verifies actual Android call state transition to OFFHOOK before reporting success.
     */
    fun answerCallDetailed(): CallActionResult {
        Log.i(TAG, "Attempting to answer call...")

        // Layer 1: TelecomManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                val hasPerm = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ANSWER_PHONE_CALLS
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPerm && telecom != null) {
                    telecom.acceptRingingCall()
                    Log.i(TAG, "TelecomManager.acceptRingingCall() invoked, verifying state...")
                    if (verifyCallStateBecomes(TelephonyManager.CALL_STATE_OFFHOOK, 800L)) {
                        isRinging = false
                        currentCallState = "OFFHOOK"
                        return CallActionResult(true, "telecom_manager", "Call receive ho gaya hai.")
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "TelecomManager answer SecurityException (ANSWER_PHONE_CALLS / Default Dialer): ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager answer failed: ${e.message}")
        }

        // Layer 2: PendingIntent from In-Call Notification
        if (answerPendingIntent != null) {
            try {
                answerPendingIntent?.send()
                Log.i(TAG, "Notification answer PendingIntent sent, verifying state...")
                if (verifyCallStateBecomes(TelephonyManager.CALL_STATE_OFFHOOK, 800L)) {
                    isRinging = false
                    currentCallState = "OFFHOOK"
                    return CallActionResult(true, "notification_intent", "Call receive ho gaya hai (Notification Intent).")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Notification answer PendingIntent failed: ${e.message}")
            }
        }

        // Layer 3: AccessibilityService Click
        val a11y = SoltiniAccessibilityService.getInstance()
        if (a11y != null) {
            val clicked = a11y.clickCallButton(isAnswer = true)
            if (clicked) {
                Log.i(TAG, "Accessibility click dispatched, verifying state...")
                if (verifyCallStateBecomes(TelephonyManager.CALL_STATE_OFFHOOK, 800L)) {
                    isRinging = false
                    currentCallState = "OFFHOOK"
                    return CallActionResult(true, "accessibility_click", "Call receive ho gaya hai (Accessibility Click).")
                }
            }
        }

        // Layer 4: MediaButton HEADSETHOOK KeyEvent
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
                Log.i(TAG, "Dispatched HEADSETHOOK KeyEvent, verifying state...")
                if (verifyCallStateBecomes(TelephonyManager.CALL_STATE_OFFHOOK, 800L)) {
                    isRinging = false
                    currentCallState = "OFFHOOK"
                    return CallActionResult(true, "media_button_event", "Call receive ho gaya hai (Media Button).")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaKeyEvent answer failed: ${e.message}")
        }

        // Real restriction diagnosis — do not fake success
        val hasAnswerPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
        val isDialer = isDefaultDialer()
        val a11yActive = SoltiniAccessibilityService.getInstance() != null

        val diagnosis = when {
            !hasAnswerPerm -> "Android restriction: ANSWER_PHONE_CALLS permission grant nahi hai. Settings me jakar Myra ko permission dein."
            !isDialer && !a11yActive -> "Android restriction: Direct call receive karne ke liye Myra ko 'Default Phone App' banayein ya 'Accessibility Service' on karein."
            !a11yActive -> "Android restriction: Call notification ya screen action accessible nahi hai. Myra Accessibility Service on karein."
            else -> "Android restriction: System dialer ne call accept karne ki ijazat nahi di. Kripya phone screen se manually receive karein."
        }
        return CallActionResult(false, "restricted", diagnosis)
    }

    fun answerCall(): Boolean = answerCallDetailed().success

    /**
     * Rejects / declines the ringing phone call with layered execution and verified outcome.
     * Verifies actual Android call state transition to IDLE before reporting success.
     */
    fun rejectCallDetailed(): CallActionResult {
        Log.i(TAG, "Attempting to reject call...")

        // Layer 1: TelecomManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                val hasPerm = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ANSWER_PHONE_CALLS
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPerm && telecom != null) {
                    val ended = telecom.endCall()
                    Log.i(TAG, "TelecomManager.endCall() -> $ended, verifying state...")
                    if (verifyCallStateBecomes(TelephonyManager.CALL_STATE_IDLE, 800L)) {
                        isRinging = false
                        currentCallState = "IDLE"
                        return CallActionResult(true, "telecom_manager", "Call cut / reject kar diya gaya hai.")
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "TelecomManager endCall SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager.endCall failed: ${e.message}")
        }

        // Layer 2: PendingIntent from In-Call Notification
        if (rejectPendingIntent != null) {
            try {
                rejectPendingIntent?.send()
                Log.i(TAG, "Notification decline PendingIntent sent, verifying state...")
                if (verifyCallStateBecomes(TelephonyManager.CALL_STATE_IDLE, 800L)) {
                    isRinging = false
                    currentCallState = "IDLE"
                    return CallActionResult(true, "notification_intent", "Call cut / reject kar diya gaya hai (Notification Intent).")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Notification decline PendingIntent failed: ${e.message}")
            }
        }

        // Layer 3: AccessibilityService Click
        val a11y = SoltiniAccessibilityService.getInstance()
        if (a11y != null) {
            val clicked = a11y.clickCallButton(isAnswer = false)
            if (clicked) {
                Log.i(TAG, "Accessibility decline clicked, verifying state...")
                if (verifyCallStateBecomes(TelephonyManager.CALL_STATE_IDLE, 800L)) {
                    isRinging = false
                    currentCallState = "IDLE"
                    return CallActionResult(true, "accessibility_click", "Call cut / reject kar diya gaya hai (Accessibility Click).")
                }
            }
        }

        // Real restriction diagnosis — do not fake success
        val hasAnswerPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
        val isDialer = isDefaultDialer()
        val a11yActive = SoltiniAccessibilityService.getInstance() != null

        val diagnosis = when {
            !hasAnswerPerm -> "Android restriction: ANSWER_PHONE_CALLS permission grant nahi hai. Settings me jakar Myra ko permission dein."
            !isDialer && !a11yActive -> "Android restriction: Direct call reject karne ke liye Myra ko 'Default Phone App' banayein ya 'Accessibility Service' on karein."
            !a11yActive -> "Android restriction: Incoming call notification ya decline button accessible nahi hai. Myra Accessibility Service on karein."
            else -> "Android restriction: System dialer ne call end karne ki ijazat nahi di. Kripya phone screen se manually reject karein."
        }
        return CallActionResult(false, "restricted", diagnosis)
    }

    fun rejectCall(): Boolean = rejectCallDetailed().success

    /**
     * Places a phone call to a contact or number.
     */
    fun makeCall(target: String): Boolean {
        if (target.isBlank()) return false
        val trimmedTarget = target.trim()
        // FIX: previously "target.any { it.isDigit() }" meant ANY stray digit anywhere in the
        // string (e.g. STT noise like "Rahul7") caused the whole mixed string to be dialed
        // literally instead of doing a contact lookup — this is what caused calls to random
        // wrong numbers. Now we only treat it as a raw phone number if it consists ENTIRELY
        // of digits/+/spaces/dashes/parentheses (i.e. it actually looks like a phone number).
        val looksLikePhoneNumber = trimmedTarget.matches(Regex("""^[+\d][\d\s\-()]*$"""))
        val number = if (looksLikePhoneNumber) {
            trimmedTarget
        } else {
            lookupNumberForContact(trimmedTarget) ?: run {
                Log.w(TAG, "No contact found for '$trimmedTarget' and it is not a valid number — aborting call")
                return false
            }
        }

        markOutgoingCallInitiated(number)

        return try {
            val hasCallPerm = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
            val intent = Intent(action, Uri.parse("tel:${Uri.encode(number.replace(" ", ""))}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Initiated call to $number")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to make call: ${e.message}", e)
            false
        }
    }

    private fun lookupNumberForContact(contactName: String): String? {
        val hasContactsPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContactsPerm) return null

        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$contactName%")
            // FIX: previously took whatever row the unordered cursor returned first, which could
            // be a totally different contact whose name merely contained the query as a substring
            // (e.g. searching "Aman" could match "Amanpreet", "Chetamani", etc. before the real
            // "Aman"). Now we collect ALL matches and prefer an exact (case-insensitive) name
            // match; only fall back to the first partial match if there's no exact one.
            var exactMatchNumber: String? = null
            var firstPartialMatchNumber: String? = null
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val numIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (numIndex == -1) continue
                    val number = cursor.getString(numIndex) ?: continue
                    val displayName = if (nameIndex != -1) cursor.getString(nameIndex) else null
                    if (firstPartialMatchNumber == null) firstPartialMatchNumber = number
                    if (exactMatchNumber == null && displayName != null &&
                        displayName.equals(contactName, ignoreCase = true)
                    ) {
                        exactMatchNumber = number
                    }
                }
            }
            if (exactMatchNumber != null) return exactMatchNumber
            if (firstPartialMatchNumber != null) return firstPartialMatchNumber
        } catch (e: Exception) {
            Log.w(TAG, "Contact search failed for $contactName: ${e.message}")
        }
        return null
    }

    fun shutdown() {
        stopListening()
        ttsSpeaker.shutdown()
    }
}
