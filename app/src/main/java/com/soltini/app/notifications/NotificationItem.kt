package com.soltini.app.notifications

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Extracted Direct Reply action wrapper containing PendingIntent and RemoteInput spec.
 */
data class ExtractedReplyAction(
    val pendingIntent: PendingIntent,
    val remoteInputs: Array<RemoteInput>
) {
    fun sendReply(context: Context, replyText: String): Boolean {
        if (remoteInputs.isEmpty()) return false
        val primaryInput = remoteInputs.first()
        val intent = Intent()
        val bundle = Bundle().apply {
            putCharSequence(primaryInput.resultKey, replyText)
        }
        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
        return try {
            pendingIntent.send(context, 0, intent)
            Log.i("ExtractedReplyAction", "Direct reply dispatched successfully: \"$replyText\"")
            true
        } catch (e: Exception) {
            Log.e("ExtractedReplyAction", "Failed to dispatch direct reply: ${e.message}", e)
            false
        }
    }
}

/**
 * Single captured notification entry in the local repository.
 */
data class NotificationItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val canReply: Boolean = false,
    val isRead: Boolean = false,
    val replyAction: ExtractedReplyAction? = null
) {
    fun getTimeAgo(): String {
        val diffMs = System.currentTimeMillis() - timestamp
        val minutes = (diffMs / (1000 * 60)).toInt()
        return when {
            minutes < 1 -> "just now"
            minutes == 1 -> "1 minute ago"
            minutes < 60 -> "$minutes minutes ago"
            else -> {
                val hours = minutes / 60
                if (hours == 1) "1 hour ago" else "$hours hours ago"
            }
        }
    }
}

sealed class ReplyResult {
    data class Success(val recipient: String, val appName: String, val replyText: String) : ReplyResult()
    data class Failure(val reason: String) : ReplyResult()
}
