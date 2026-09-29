package com.soltini.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ScheduledTaskAlarmReceiver
 *
 * Catches exact AlarmManager alarms even when device is in Doze / sleep mode.
 * Acquires a brief WakeLock and triggers execution in ScheduledTaskManager.
 */
class ScheduledTaskAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduledTaskReceiver"
        const val ACTION_EXECUTE_TASK = "com.soltini.app.scheduler.EXECUTE_TASK"
        const val EXTRA_TASK_ID = "extra_task_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_EXECUTE_TASK) return

        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        Log.i(TAG, "Alarm triggered for scheduled task: $taskId")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Soltini:ScheduledTaskWakeLock:$taskId"
        )
        wakeLock.acquire(60_000L) // 1 minute max

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = ScheduledTaskManager.getInstance(context)
                manager.executeTaskNow(taskId)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing scheduled task $taskId: ${e.message}", e)
            } finally {
                try {
                    if (wakeLock.isHeld) wakeLock.release()
                } catch (_: Exception) {}
                pendingResult.finish()
            }
        }
    }
}
