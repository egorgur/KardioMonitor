package ru.kardiomonitor.features.monitoring.domain

import kotlinx.coroutines.flow.StateFlow

/** Настройки синхронизации истории: фоновая синхронизация и итог последней синхронизации. */
interface SyncSettings {
    /** Синхронизировать в фоне каждые 30 минут; по умолчанию включено. */
    val isBackgroundSyncEnabled: StateFlow<Boolean>

    val lastSync: StateFlow<LastSync?>

    fun setBackgroundSyncEnabled(enabled: Boolean)

    fun recordSync(sync: LastSync)

    /** Браслет отвязан: настройки возвращаются к исходным. */
    fun reset()
}
