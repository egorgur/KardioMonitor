package ru.kardiomonitor.app

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import ru.kardiomonitor.features.monitoring.historysync.HistorySyncWorker
import ru.kardiomonitor.features.band.statusrefresh.BandStatusWorker
import ru.kardiomonitor.features.monitoring.liveheartrate.RequestedLiveHeartRateWorker
import ru.kardiomonitor.features.reminders.remindercheck.ReminderWorker

/** Создаёт Worker-ы с зависимостями из [AppContainer]; неизвестные классы — по умолчанию (null). */
class KardioWorkerFactory(private val container: AppContainer) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        HistorySyncWorker::class.java.name -> HistorySyncWorker(
            appContext,
            workerParameters,
            container.activityHistoryRefresh,
        )
        BandStatusWorker::class.java.name -> BandStatusWorker(appContext, workerParameters, container.bandStatusRefresh)
        ReminderWorker::class.java.name -> ReminderWorker(appContext, workerParameters, container.reminderCheck)
        RequestedLiveHeartRateWorker::class.java.name -> RequestedLiveHeartRateWorker(
            appContext,
            workerParameters,
            container.requestedLiveHeartRateRunner,
            container.liveHeartRateRequestNotification,
        )
        else -> null
    }
}
