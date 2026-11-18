package ru.kardiomonitor.features.reminders.remindercheck

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Проверяет локальные условия каждые 15 минут; WorkManager переживает перезагрузку и смену пояса. */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
    private val check: ReminderCheck,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        check()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "patient-reminders"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
