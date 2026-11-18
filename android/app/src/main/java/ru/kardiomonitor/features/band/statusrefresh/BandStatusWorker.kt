package ru.kardiomonitor.features.band.statusrefresh

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Периодическая ограниченная проверка Band 9; не создаёт историю или клинические события. */
class BandStatusWorker(
    context: Context,
    params: WorkerParameters,
    private val refresh: BandStatusRefresh,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val identity = inputData.getString(BINDING) ?: return Result.failure()
        if (!refresh.canRefresh(identity)) return Result.success()
        try {
            setForeground(BandStatusNotification(applicationContext).foregroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Log.w("KardioBLE", "фоновая проверка Band 9 недоступна: foreground-запуск не разрешён")
            return Result.success()
        }
        refresh.refresh(identity)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "band-read-only-status"
        private const val BINDING = "binding"

        fun schedule(context: Context, identity: String) {
            val request = PeriodicWorkRequestBuilder<BandStatusWorker>(30, TimeUnit.MINUTES)
                .setInputData(Data.Builder().putString(BINDING, identity).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
