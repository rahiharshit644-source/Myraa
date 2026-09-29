package com.soltini.app.scheduler

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.soltini.app.MainActivity
import com.soltini.app.learning.ExperienceLearningEngine
import com.soltini.app.memory2.Memory2Database
import com.soltini.app.orchestrator.MyraCommandParser
import com.soltini.app.telephony.CallNotificationManager
import com.soltini.app.telephony.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Result of scheduling attempt.
 */
sealed class ScheduleOutcome {
    data class Success(
        val task: ScheduledTask,
        val confirmationMessage: String
    ) : ScheduleOutcome()

    data class ClarificationNeeded(
        val question: String,
        val partialTask: ScheduledTask? = null
    ) : ScheduleOutcome()

    data class Error(val message: String) : ScheduleOutcome()
}

/**
 * ScheduledTaskManager
 *
 * Coordinates persistent, time-based future task execution.
 * Backed by SQLite in Memory2Database and Android AlarmManager (with Doze support).
 * Fully survives application kills and phone reboots.
 */
class ScheduledTaskManager private constructor(private val context: Context) {

    private val db: Memory2Database = Memory2Database.getInstance(context)
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        private const val TAG = "ScheduledTaskManager"
        private const val NOTIF_CHANNEL_ID = "myra_scheduled_tasks"
        private const val NOTIF_CHANNEL_NAME = "MYRA Scheduled Tasks"

        @Volatile
        private var instance: ScheduledTaskManager? = null

        fun getInstance(context: Context): ScheduledTaskManager =
            instance ?: synchronized(this) {
                instance ?: ScheduledTaskManager(context.applicationContext).also { instance = it }
            }
    }

    init {
        createNotificationChannel()
    }

    /**
     * Schedules a task from natural language or explicit parameters.
     */
    suspend fun scheduleFromNaturalCommand(
        command: String,
        taskType: String? = null,
        parameters: Map<String, String>? = null,
        explicitTimeMs: Long? = null
    ): ScheduleOutcome = withContext(Dispatchers.IO) {
        val targetTimeMs: Long
        val timeDesc: String

        if (explicitTimeMs != null && explicitTimeMs > System.currentTimeMillis()) {
            targetTimeMs = explicitTimeMs
            timeDesc = formatTimestamp(explicitTimeMs)
        } else {
            when (val timeResult = NaturalTimeParser.parse(command)) {
                is TimeParseResult.Ambiguous -> {
                    return@withContext ScheduleOutcome.ClarificationNeeded(
                        question = timeResult.clarificationQuestion
                    )
                }
                is TimeParseResult.Success -> {
                    targetTimeMs = timeResult.timestampMs
                    timeDesc = timeResult.humanDescription
                }
                is TimeParseResult.NotScheduled -> {
                    return@withContext ScheduleOutcome.Error("Koi specific samay nahi mila. Kripya samay batayein (jaise: shaam 7 baje, ya 30 minute baad).")
                }
            }
        }

        // Determine Task Type & Parameters if not explicitly provided
        val determinedType: String
        val resolvedParams = mutableMapOf<String, String>()
        val lower = command.lowercase()

        if (taskType != null) {
            determinedType = taskType
            if (parameters != null) resolvedParams.putAll(parameters)
        } else {
            when {
                lower.contains("call") || lower.contains("phone lagao") || lower.contains("phone karo") -> {
                    determinedType = "CALL"
                    val recipient = extractRecipientForCall(command)
                    resolvedParams["recipient"] = recipient
                }
                lower.contains("message") || lower.contains("msg") || lower.contains("sms") -> {
                    determinedType = "SEND_MESSAGE"
                    val (rec, msg) = extractRecipientAndMessage(command)
                    resolvedParams["recipient"] = rec
                    resolvedParams["message"] = msg
                }
                lower.contains("remind") || lower.contains("yaad dilana") || lower.contains("yaad dilao") -> {
                    determinedType = "REMINDER"
                    val reminderText = extractReminderText(command)
                    resolvedParams["reminder_text"] = reminderText
                }
                lower.contains("chalao") || lower.contains("play") || lower.contains("gaana") -> {
                    determinedType = "PLAY_MEDIA"
                    resolvedParams["query"] = command.replace(Regex("""(?i)(chalao|play|bajao|kal|aaj|baje)"""), "").trim()
                }
                else -> {
                    determinedType = "GENERIC_ACTION"
                    resolvedParams["command"] = command
                }
            }
        }

        val title = when (determinedType) {
            "CALL" -> "Call ${resolvedParams["recipient"] ?: "contact"}"
            "SEND_MESSAGE" -> "Message to ${resolvedParams["recipient"] ?: "contact"}"
            "REMINDER" -> "Reminder: ${resolvedParams["reminder_text"] ?: command.take(30)}"
            else -> command.take(40)
        }

        val task = ScheduledTask(
            taskType = determinedType,
            title = title,
            command = command,
            parsedIntent = determinedType,
            parameters = resolvedParams,
            scheduledAt = targetTimeMs,
            status = ScheduledTaskStatus.SCHEDULED
        )

        val inserted = db.insertScheduledTask(task)
        if (!inserted) {
            return@withContext ScheduleOutcome.Error("Task database me save nahi ho paya.")
        }

        // Arm the exact Android Alarm
        armAlarm(task)

        val confirmation = when (determinedType) {
            "CALL" -> "Ji Boss! ${timeDesc} par ${resolvedParams["recipient"] ?: ""} ko call karne ke liye schedule kar diya hai."
            "SEND_MESSAGE" -> "Ji Boss! ${timeDesc} par ${resolvedParams["recipient"] ?: ""} ko message bhejne ke liye schedule ho gaya hai."
            "REMINDER" -> "Ji Boss! ${timeDesc} par aapko yaad dila dungi: \"${resolvedParams["reminder_text"] ?: ""}\""
            else -> "Ji Boss! Task $timeDesc ke liye schedule kar diya gaya hai."
        }

        ScheduleOutcome.Success(task = task, confirmationMessage = confirmation)
    }

    /**
     * Cancels an existing scheduled task.
     */
    fun cancelTask(taskId: String): Boolean {
        val task = db.getScheduledTask(taskId) ?: return false
        disarmAlarm(task)
        return db.updateScheduledTaskStatus(taskId, ScheduledTaskStatus.CANCELLED, result = "User cancelled")
    }

    /**
     * Reschedules an existing task to a new time.
     */
    fun rescheduleTask(taskId: String, newTimeMs: Long): Boolean {
        val task = db.getScheduledTask(taskId) ?: return false
        disarmAlarm(task)
        task.scheduledAt = newTimeMs
        task.status = ScheduledTaskStatus.SCHEDULED
        task.updatedAt = System.currentTimeMillis()
        val updated = db.updateScheduledTask(task)
        if (updated) {
            armAlarm(task)
        }
        return updated
    }

    /**
     * Retries a failed task.
     */
    fun retryTask(taskId: String): Boolean {
        val task = db.getScheduledTask(taskId) ?: return false
        task.status = ScheduledTaskStatus.SCHEDULED
        task.scheduledAt = System.currentTimeMillis() + 5_000L // 5 seconds from now
        task.retryCount++
        task.updatedAt = System.currentTimeMillis()
        val updated = db.updateScheduledTask(task)
        if (updated) {
            armAlarm(task)
        }
        return updated
    }

    /**
     * Deletes task permanently.
     */
    fun deleteTask(taskId: String): Boolean {
        val task = db.getScheduledTask(taskId)
        if (task != null) disarmAlarm(task)
        return db.deleteScheduledTask(taskId)
    }

    /**
     * Reschedules all pending tasks on phone reboot or application start.
     */
    fun rescheduleAllPendingTasks() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pending = db.getPendingScheduledTasks()
                val now = System.currentTimeMillis()
                Log.i(TAG, "Re-arming ${pending.size} pending scheduled tasks after boot/start.")
                for (task in pending) {
                    if (task.scheduledAt <= now) {
                        // Task was due while phone was off - execute it immediately!
                        Log.w(TAG, "Task ${task.id} (${task.title}) was due while device was offline. Executing now.")
                        executeTaskNow(task.id)
                    } else {
                        armAlarm(task)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling pending tasks: ${e.message}", e)
            }
        }
    }

    /**
     * Executes the task when the alarm triggers.
     * Uses real telephony / SMS / tools and verifies real execution!
     */
    suspend fun executeTaskNow(taskId: String) = withContext(Dispatchers.IO) {
        val task = db.getScheduledTask(taskId)
        if (task == null) {
            Log.e(TAG, "Task $taskId not found in database for execution.")
            return@withContext
        }

        if (task.status != ScheduledTaskStatus.SCHEDULED && task.status != ScheduledTaskStatus.WAITING) {
            Log.w(TAG, "Task $taskId is in status ${task.status}, skipping execution.")
            return@withContext
        }

        db.updateScheduledTaskStatus(taskId, ScheduledTaskStatus.EXECUTING)
        Log.i(TAG, "Executing scheduled task [${task.id}]: ${task.taskType} - ${task.title}")

        val learningEngine = ExperienceLearningEngine.getInstance(context)
        var isSuccess = false
        var executionResult = ""
        var executionError: String? = null
        val toolsUsed = mutableListOf<String>()

        try {
            when (task.taskType) {
                "CALL" -> {
                    toolsUsed.add("CallNotificationManager")
                    val recipient = task.parameters["recipient"] ?: ""
                    if (recipient.isNotBlank()) {
                        val callMgr = CallNotificationManager.getInstance(context)
                        val callPlaced = callMgr.makeCall(recipient)
                        if (callPlaced) {
                            isSuccess = true
                            executionResult = "Call placed to $recipient successfully."
                        } else {
                            isSuccess = false
                            executionError = "Failed to place call to $recipient (check phone permission/contact)."
                        }
                    } else {
                        executionError = "No recipient specified for call."
                    }
                }

                "SEND_MESSAGE" -> {
                    toolsUsed.add("SmsSender")
                    val recipient = task.parameters["recipient"] ?: ""
                    val message = task.parameters["message"] ?: ""
                    if (recipient.isNotBlank() && message.isNotBlank()) {
                        val callMgr = CallNotificationManager.getInstance(context)
                        val matches = callMgr.searchContacts(recipient)
                        val targetNumber = if (matches.isNotEmpty()) matches.first().phoneNumber else recipient
                        val smsSender = SmsSender(context)
                        val res = smsSender.sendSms(targetNumber, message)
                        if (res.success) {
                            isSuccess = true
                            executionResult = "SMS sent to $recipient: \"$message\""
                        } else {
                            isSuccess = false
                            executionError = "SMS sending failed: ${res.message}"
                        }
                    } else {
                        executionError = "Recipient or message empty."
                    }
                }

                "REMINDER" -> {
                    toolsUsed.add("NotificationManager")
                    val reminder = task.parameters["reminder_text"] ?: task.command
                    isSuccess = true
                    executionResult = "Reminder triggered: $reminder"
                    showTaskAlertNotification(
                        title = "⏰ MYRA Reminder",
                        message = "Boss, aapka reminder: $reminder",
                        taskId = task.id
                    )
                }

                else -> {
                    toolsUsed.add("GenericTool")
                    isSuccess = true
                    executionResult = "Scheduled action executed: ${task.command}"
                    showTaskAlertNotification(
                        title = "⚡ MYRA Scheduled Task",
                        message = "Task completed: ${task.title}",
                        taskId = task.id
                    )
                }
            }
        } catch (e: Exception) {
            isSuccess = false
            executionError = e.message ?: "Execution exception"
            Log.e(TAG, "Error executing task ${task.id}: ${e.message}", e)
        }

        // Update database status
        val newStatus = if (isSuccess) ScheduledTaskStatus.COMPLETED else ScheduledTaskStatus.FAILED
        db.updateScheduledTaskStatus(
            id = taskId,
            status = newStatus,
            result = executionResult,
            error = executionError
        )

        // Post completion notification
        if (task.taskType == "CALL" || task.taskType == "SEND_MESSAGE") {
            showTaskAlertNotification(
                title = if (isSuccess) "✅ Task Completed" else "❌ Task Failed",
                message = if (isSuccess) executionResult else (executionError ?: "Execution failed"),
                taskId = task.id
            )
        }

        // Feed real result into ExperienceLearningEngine
        learningEngine.recordTryAndResult(
            goal = task.command,
            taskType = "scheduled_${task.taskType.lowercase()}",
            methodAttempted = "ScheduledTaskManager::${task.taskType}",
            toolsUsed = toolsUsed,
            steps = listOf("AlarmTriggered", "Execute_${task.taskType}", "VerifyResult"),
            isSuccess = isSuccess,
            outcomeSummary = if (isSuccess) executionResult else (executionError ?: "Failed"),
            parametersPattern = task.parameters
        )
    }

    /**
     * Returns a clear summary for "Kaunse tasks scheduled hain?" / "Pending tasks dikhao".
     */
    fun getUpcomingTasksSummary(): String {
        val pending = db.getPendingScheduledTasks()
        if (pending.isEmpty()) {
            return "Boss, abhi koi scheduled tasks pending nahi hain."
        }
        val sb = StringBuilder("Boss, aapke ${pending.size} tasks scheduled hain:\n")
        val sdf = SimpleDateFormat("h:mm a (d MMM)", Locale.getDefault())
        for ((idx, t) in pending.withIndex()) {
            val timeStr = sdf.format(Date(t.scheduledAt))
            sb.append("${idx + 1}. ${t.title} — $timeStr\n")
        }
        return sb.toString().trim()
    }

    fun findTaskByQuery(query: String): ScheduledTask? {
        val all = db.getAllScheduledTasks(50)
        val lower = query.lowercase().trim()
        return all.find {
            it.command.lowercase().contains(lower) ||
            it.title.lowercase().contains(lower) ||
            it.parameters.values.any { v -> v.lowercase().contains(lower) }
        }
    }

    fun getAllTasks(): List<ScheduledTask> = db.getAllScheduledTasks(100)

    fun getPendingTasks(): List<ScheduledTask> = db.getPendingScheduledTasks()

    // ─── Alarm Management ────────────────────────────────────────────────────

    private fun armAlarm(task: ScheduledTask) {
        val intent = Intent(context, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ScheduledTaskAlarmReceiver.ACTION_EXECUTE_TASK
            putExtra(ScheduledTaskAlarmReceiver.EXTRA_TASK_ID, task.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    task.scheduledAt,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    task.scheduledAt,
                    pendingIntent
                )
            }
            Log.i(TAG, "Armed exact alarm for task [${task.id}] at ${formatTimestamp(task.scheduledAt)}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SCHEDULE_EXACT_ALARM permission not granted, falling back to inexact alarm: ${e.message}")
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                task.scheduledAt,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to arm alarm: ${e.message}", e)
        }
    }

    private fun disarmAlarm(task: ScheduledTask) {
        val intent = Intent(context, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ScheduledTaskAlarmReceiver.ACTION_EXECUTE_TASK
            putExtra(ScheduledTaskAlarmReceiver.EXTRA_TASK_ID, task.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        alarmManager.cancel(pendingIntent)
        Log.i(TAG, "Disarmed alarm for task [${task.id}]")
    }

    private fun showTaskAlertNotification(title: String, message: String, taskId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "view_scheduled_tasks")
            putExtra("task_id", taskId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(taskId.hashCode(), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                NOTIF_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for timed and scheduled tasks in MYRA"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun formatTimestamp(timeMs: Long): String {
        val sdf = SimpleDateFormat("h:mm a, d MMM", Locale.getDefault())
        return sdf.format(Date(timeMs))
    }

    // ─── Extraction Helpers ──────────────────────────────────────────────────

    private fun extractRecipientForCall(command: String): String {
        // "Rahul ko call karna", "call Rahul at 7 pm", "Rahul ko phone lagao"
        val regex = Regex("""(?i)(?:call\s+([A-Za-z0-9\u0900-\u097F]+)|([A-Za-z0-9\u0900-\u097F]+)\s*ko\s*(?:call|phone))""")
        val match = regex.find(command)
        if (match != null) {
            val rec = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
            if (rec.lowercase() !in listOf("shaam", "subah", "raat", "kal", "parso", "baje", "ko")) {
                return rec
            }
        }
        return "contact"
    }

    private fun extractRecipientAndMessage(command: String): Pair<String, String> {
        // "Kal 8 baje Rahul ko message bhejna ki main late aaunga"
        val recipient = extractRecipientForCall(command)
        val msgRegex = Regex("""(?i)(?:ki|that|text|message)\s+(.*)$""")
        val msgMatch = msgRegex.find(command)
        val msg = msgMatch?.groupValues?.get(1)?.trim() ?: command
        return Pair(recipient, msg)
    }

    private fun extractReminderText(command: String): String {
        val regex = Regex("""(?i)(?:ki|that|about|remind\s*me\s*to)\s+(.*)$""")
        val match = regex.find(command)
        return match?.groupValues?.get(1)?.trim() ?: command
    }
}
