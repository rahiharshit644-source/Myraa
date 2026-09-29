package com.soltini.app.automation

import android.content.Context
import androidx.work.*
import com.soltini.app.memory2.Memory2Database
import com.soltini.app.util.AppLogger
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * ProactiveRoutineScheduler
 *
 * Scans Memory2Database for habit patterns and recurring tasks.
 * Uses Android WorkManager to schedule jobs 5 minutes prior to peak habit times.
 */
object ProactiveRoutineScheduler {

    private const val TAG = "ProactiveRoutineScheduler"
    private const val WORK_PREFIX = "myra_proactive_"

    /**
     * Schedules a routine to run 5 minutes before [targetHour]:[targetMinute] every day.
     */
    fun scheduleRoutine(
        context: Context,
        taskType: String,
        targetHour: Int,
        targetMinute: Int,
        promptText: String
    ) {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // Subtract 5 minutes for proactive trigger
            add(Calendar.MINUTE, -5)
        }

        // If time already passed today, schedule for tomorrow
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        val initialDelayMs = target.timeInMillis - now.timeInMillis
        val initialDelayMinutes = TimeUnit.MILLISECONDS.toMinutes(initialDelayMs).coerceAtLeast(1)

        val inputData = Data.Builder()
            .putString(ProactiveRoutineWorker.KEY_TASK_TYPE, taskType)
            .putString(ProactiveRoutineWorker.KEY_PROMPT_TEXT, promptText)
            .putInt(ProactiveRoutineWorker.KEY_TARGET_HOUR, targetHour)
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<ProactiveRoutineWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
            .setInputData(inputData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("proactive_routine")
            .build()

        val uniqueWorkName = "$WORK_PREFIX$taskType"
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            uniqueWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWorkRequest
        )

        AppLogger.i(TAG, "Scheduled proactive $taskType routine: triggers at ${target.get(Calendar.HOUR_OF_DAY)}:${target.get(Calendar.MINUTE)} (in $initialDelayMinutes mins)")
    }

    /**
     * Inspects Memory2Database experiences to detect repeatedly triggered tasks (e.g. weather, briefing)
     * and automatically sets up proactive 5-minute pre-emptive triggers.
     */
    fun analyzeHabitsAndSchedule(context: Context, memory2Db: Memory2Database) {
        try {
            val experiences = memory2Db.getAllExperiences()
            val now = Calendar.getInstance()

            for (exp in experiences) {
                // If user has repeatedly used a task (e.g., weather_lookup or news)
                if (exp.successCount >= 2 && exp.taskType.isNotBlank()) {
                    val taskName = exp.taskType.lowercase()
                    if (taskName.contains("weather") || taskName.contains("briefing") || taskName.contains("news") || taskName.contains("schedule")) {
                        // Extract hour from last used timestamp
                        val lastUsedCal = Calendar.getInstance().apply {
                            timeInMillis = exp.lastUsedAt
                        }
                        val peakHour = lastUsedCal.get(Calendar.HOUR_OF_DAY)
                        val peakMinute = lastUsedCal.get(Calendar.MINUTE)

                        val prompt = when {
                            taskName.contains("weather") -> "Boss, aapka daily morning weather update ready hai."
                            taskName.contains("news") -> "Boss, aapka top headlines digest ready hai."
                            else -> "Boss, aapka daily $taskName summary ready hai."
                        }

                        scheduleRoutine(
                            context = context,
                            taskType = exp.taskType,
                            targetHour = peakHour,
                            targetMinute = peakMinute,
                            promptText = prompt
                        )
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error analyzing habits for proactive scheduling: ${e.message}")
        }
    }

    /**
     * Cancels a scheduled proactive routine.
     */
    fun cancelRoutine(context: Context, taskType: String) {
        WorkManager.getInstance(context).cancelUniqueWork("$WORK_PREFIX$taskType")
        AppLogger.i(TAG, "Cancelled proactive routine: $taskType")
    }

    const val PERIODIC_ANALYSIS_WORK_NAME = "myra_periodic_habit_analysis"

    /**
     * Initializes proactive habit analysis:
     * 1. Triggers an immediate habit scan in the background.
     * 2. Registers a recurring WorkManager job to re-analyze user habits every 12 hours.
     */
    fun initPeriodicHabitAnalysis(context: Context) {
        val appContext = context.applicationContext

        // 1. Immediate habit analysis pass
        Thread {
            try {
                val db = Memory2Database.getInstance(appContext)
                analyzeHabitsAndSchedule(appContext, db)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Initial habit analysis error: ${e.message}")
            }
        }.start()

        // 2. Periodic background job every 12 hours
        try {
            val periodicWork = PeriodicWorkRequestBuilder<HabitAnalysisWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .addTag("habit_analysis")
                .build()

            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                PERIODIC_ANALYSIS_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )
            AppLogger.i(TAG, "Periodic habit analysis scheduled (every 12 hours)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error enqueuing periodic habit analysis: ${e.message}")
        }
    }
}

/**
 * Background worker that triggers habit analysis periodically (every 12-24 hours).
 */
class HabitAnalysisWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val db = Memory2Database.getInstance(context)
            ProactiveRoutineScheduler.analyzeHabitsAndSchedule(context, db)
            AppLogger.i("HabitAnalysisWorker", "Habit analysis successfully executed by WorkManager")
            Result.success()
        } catch (e: Exception) {
            AppLogger.e("HabitAnalysisWorker", "Habit analysis failed: ${e.message}")
            Result.retry()
        }
    }
}
