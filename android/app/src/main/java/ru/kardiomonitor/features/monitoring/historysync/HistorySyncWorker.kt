package ru.kardiomonitor.features.monitoring.historysync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.kardiomonitor.features.monitoring.api.ActivityHistoryRefresh
import java.util.concurrent.TimeUnit

/**
 * Точка входа слайса для фона: синхронизация истории раз в 30 минут. Android может откладывать
 * запуск в режиме энергосбережения (Doze), поэтому интервал не строгий. Использует открытое
 * соединение, если оно есть; иначе подключается и отключается сам (ADR-0003).
 * Создаётся фабрикой Worker-ов композиционного корня.
 */
class HistorySyncWorker(
    context: Context,
    params: WorkerParameters,
    private val historyRefresh: ActivityHistoryRefresh,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Ошибка — в итоге синхронизации. Не повторяем сразу: браслета может не быть рядом,
        // следующая попытка — через 30 минут.
        historyRefresh.refresh()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "history-sync"

        /** Работа прототипа (класс ru.kardiomonitor.SyncWorker): снимается при планировании. */
        private const val LEGACY_WORK_NAME = "band-sync"
        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(LEGACY_WORK_NAME)
            val request = PeriodicWorkRequestBuilder<HistorySyncWorker>(30, TimeUnit.MINUTES).build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(LEGACY_WORK_NAME)
            workManager.cancelUniqueWork(WORK_NAME)
        }
    }
}
