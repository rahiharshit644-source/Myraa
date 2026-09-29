package com.soltini.app.telephony

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Result of attempting to send an SMS.
 */
data class SmsSendResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * SmsSender
 *
 * Real Android SMS dispatcher.
 * Supports direct SMS sending via SmsManager when SEND_SMS permission is granted,
 * or fallback to opening the system SMS app via Intent.ACTION_SENDTO with pre-filled content.
 */
class SmsSender(private val context: Context) {

    companion object {
        private const val TAG = "SmsSender"
        private val recentlySent = java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Long>(50, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                    return size > 50
                }
            }
        )
    }

    /**
     * Sends an SMS to [phoneNumber] with [messageText].
     * Returns a structured [SmsSendResult] reflecting real execution status.
     * Prevents duplicate dispatches during Agentic Loop retries.
     */
    fun sendSms(phoneNumber: String, messageText: String): SmsSendResult {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "").trim()
        val cleanText = messageText.trim()

        if (cleanNumber.isBlank()) {
            return SmsSendResult(
                success = false,
                method = "none",
                message = "Phone number is empty or invalid."
            )
        }

        if (cleanText.isBlank()) {
            return SmsSendResult(
                success = false,
                method = "none",
                message = "Message text cannot be empty."
            )
        }

        // Idempotent duplicate check: Prevents duplicate dispatches during retries within 60s
        val dedupeKey = "$cleanNumber::$cleanText"
        val lastSent = recentlySent[dedupeKey]
        val now = System.currentTimeMillis()
        if (lastSent != null && (now - lastSent) < 60_000L) {
            Log.i(TAG, "Duplicate SMS dispatch suppressed for $cleanNumber within 60s window")
            return SmsSendResult(
                success = true,
                method = "idempotent_cached",
                message = "SMS pehle hi successfully bhej diya gaya hai (duplicate prevent kiya gaya)."
            )
        }

        val hasSendSmsPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        // 1. Direct SMS sending via SmsManager if permission granted
        if (hasSendSmsPerm) {
            try {
                val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                val parts = smsManager.divideMessage(cleanText)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(cleanNumber, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(cleanNumber, null, cleanText, null, null)
                }

                recentlySent[dedupeKey] = now
                Log.i(TAG, "Direct SMS successfully dispatched to $cleanNumber")
                return SmsSendResult(
                    success = true,
                    method = "direct_sms",
                    message = "SMS sent to $cleanNumber: \"$cleanText\""
                )
            } catch (e: Exception) {
                Log.e(TAG, "Direct SMS sending failed with exception: ${e.message}", e)
                return SmsSendResult(
                    success = false,
                    method = "send_exception",
                    message = "SMS send failed: ${e.message}"
                )
            }
        }

        // 2. Fallback: If SEND_SMS permission is not granted, launch SMS composer but do NOT report false success
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$cleanNumber")
                putExtra("sms_body", cleanText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Launched SMS app intent for $cleanNumber (SEND_SMS not granted)")
            SmsSendResult(
                success = false,
                method = "permission_denied_opened_app",
                message = "SEND_SMS permission grant nahi hai. Kripya app me send button dabayein."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch SMS intent: ${e.message}", e)
            SmsSendResult(
                success = false,
                method = "none",
                message = "Could not send SMS: SEND_SMS permission missing (${e.message})"
            )
        }
    }
}
