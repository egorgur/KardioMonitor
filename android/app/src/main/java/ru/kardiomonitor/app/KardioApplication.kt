package ru.kardiomonitor.app

import android.app.Application
import androidx.work.Configuration

/**
 * Держит [AppContainer] и отдаёт WorkManager фабрику Worker-ов с зависимостями.
 * Стандартная инициализация WorkManager отключена в манифесте — иначе фабрика не подключится.
 */
class KardioApplication : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Подписка должна существовать до первой ручной или фоновой попытки синхронизации.
        container.liveHeartRateRequestSynchronizer.start()
        container.backgroundSyncSchedule.start()
        container.reminderSchedule.start()
        // Подписки на события «история пополнилась» и «пульс сейчас измерен» — отправка врачу
        container.minuteUploader.start()
        container.liveHeartRateSharing.start()
        container.rehabPlanSynchronizer.start()
        container.patientAlertSynchronizer.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(KardioWorkerFactory(container)).build()
}
