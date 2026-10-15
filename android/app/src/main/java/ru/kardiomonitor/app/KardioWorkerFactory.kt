package ru.kardiomonitor.app

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import ru.kardiomonitor.SyncWorker

/** Создаёт Worker-ы с зависимостями из [AppContainer]; неизвестные классы — по умолчанию (null). */
class KardioWorkerFactory(private val container: AppContainer) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        SyncWorker::class.java.name -> SyncWorker(appContext, workerParameters, container.bandManager)
        else -> null
    }
}
