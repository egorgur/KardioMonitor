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
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(KardioWorkerFactory(container)).build()
}
