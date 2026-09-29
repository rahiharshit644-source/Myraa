package com.soltini.app.telephony

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * IncomingCallReceiver
 *
 * BroadcastReceiver listening for TelephonyManager.ACTION_PHONE_STATE_CHANGED
 * and Intent.ACTION_NEW_OUTGOING_CALL to accurately distinguish genuine incoming calls
 * from outgoing dialing/ringback tones.
 */
class IncomingCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IncomingCallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val callManager = CallNotificationManager.getInstance(context)

        // 1. Intercept outgoing call intent broadcast
        @Suppress("DEPRECATION")
        if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
            val dialedNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
            Log.i(TAG, "NEW_OUTGOING_CALL broadcast received: dialed=$dialedNumber")
            callManager.markOutgoingCallInitiated(dialedNumber)
            return
        }

        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        try {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            Log.i(TAG, "PHONE_STATE broadcast: state=$stateStr")

            when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    val now = System.currentTimeMillis()

                    // CRITICAL FIX: If user initiated an outgoing call within last 60 seconds or outgoing is active,
                    // this RINGING is carrier ringback tone or network alert for the outgoing call!
                    if (callManager.isOutgoingCallActive || (now - callManager.lastOutgoingCallTime < 60_000L)) {
                        Log.i(TAG, "Suppressed false incoming call alert during outgoing call (${now - callManager.lastOutgoingCallTime}ms ago)")
                        return
                    }

                    // Also check recent CallLog to confirm not an outgoing call
                    if (isRecentCallOutgoing(context)) {
                        Log.i(TAG, "CallLog indicates recent call is OUTGOING; suppressing incoming alert.")
                        callManager.markOutgoingCallInitiated()
                        return
                    }

                    @Suppress("DEPRECATION")
                    var incomingNumber = try {
                        intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    } catch (_: Exception) {
                        null
                    }
                    var callerName = callManager.resolveContactName(incomingNumber)
                    if (callerName == "Unknown Caller" || callerName.isBlank()) {
                        val callInfo = callManager.getCurrentCallInfo()
                        if (!callInfo.callerName.isNullOrBlank() && !callInfo.callerName.equals("Unknown Caller", ignoreCase = true)) {
                            callerName = callInfo.callerName
                            if (incomingNumber.isNullOrBlank()) incomingNumber = callInfo.phoneNumber
                        }
                    }
                    Log.i(TAG, "Incoming ringing call from: $callerName ($incomingNumber)")
                    callManager.onIncomingCall(callerName, incomingNumber)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    Log.i(TAG, "Call idle / ended")
                    callManager.onCallEnded()
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    Log.i(TAG, "Call offhook / in-progress")
                    callManager.onCallOffhook()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling PHONE_STATE broadcast: ${e.message}")
        }
    }

    private fun isRecentCallOutgoing(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            val projection = arrayOf(CallLog.Calls.TYPE, CallLog.Calls.DATE)
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                    val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                    val callDate = if (dateIdx != -1) cursor.getLong(dateIdx) else 0L
                    val callType = if (typeIdx != -1) cursor.getInt(typeIdx) else 0
                    if (System.currentTimeMillis() - callDate < 45_000L && callType == CallLog.Calls.OUTGOING_TYPE) {
                        return true
                    }
                }
                false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
