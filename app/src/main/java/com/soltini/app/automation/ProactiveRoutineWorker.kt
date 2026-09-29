package com.soltini.app.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.soltini.app.MainActivity
import com.soltini.app.R
import com.soltini.app.memory2.Memory2Database
import com.soltini.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ProactiveRoutineWorker
 *
 * Runs scheduled background tasks 5 minutes ahead of regular user habits,
 * preparing briefings, weather summaries, or daily routines proactively.
 */
class ProactiveRoutineWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "ProactiveRoutineWorker"
        const val CHANNEL_ID = "myra_proactive_briefings"
        const val CHANNEL_NAME = "MYRA Proactive Briefings"
        const val NOTIFICATION_ID = 4040

        const val KEY_TASK_TYPE = "task_type"
        const val KEY_PROMPT_TEXT = "prompt_text"
        const val KEY_TARGET_HOUR = "target_hour"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val taskType = inputData.getString(KEY_TASK_TYPE) ?: "daily_briefing"
            val promptText = inputData.getString(KEY_PROMPT_TEXT)
                ?: "Boss, aapka daily morning briefing ready hai. Tap to interact."

            AppLogger.i(TAG, "Executing proactive routine for: $taskType")

            // Create Notification Channel if needed
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Proactive automated reminders and habit-based briefings"
                }
                notificationManager.createNotificationChannel(channel)
            }

            // Prepare open intent
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("action", "proactive_briefing")
                putExtra("task_type", taskType)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val title = when (taskType) {
                "weather_lookup" -> "🌤️ Weather Update"
                "daily_briefing" -> "🌅 Morning Briefing Ready"
                "news_briefing" -> "📰 Daily News Digest"
                else -> "⚡ MYRA Proactive Briefing"
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(promptText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(promptText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)

            // Record execution in memory
            try {
                val db = Memory2Database.getInstance(context)
                db.recordExperience(
                    taskType = "proactive_$taskType",
                    toolSequence = listOf("ProactiveRoutineWorker", "NotificationManager"),
                    isSuccess = true
                )
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not record proactive execution in memory: ${e.message}")
            }

            Result.success()
        } catch (e: Exception) {
            AppLogger.e(TAG, "ProactiveRoutineWorker error: ${e.message}")
            Result.failure()
        }
    }
}
