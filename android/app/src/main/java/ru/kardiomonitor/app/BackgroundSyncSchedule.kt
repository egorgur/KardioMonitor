package ru.kardiomonitor.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.monitoring.domain.SyncSettings
import ru.kardiomonitor.features.monitoring.historysync.HistorySyncWorker

/**
 * Связь контекстов на уровне композиционного корня (domain-model.md, «События предметной
 * области»): фоновая синхронизация стоит в расписании, пока браслет привязан и она включена;
 * «браслет отвязан» снимает её с расписания и сбрасывает настройки синхронизации.
 */
class BackgroundSyncSchedule(
    private val context: Context,
    private val bandRepository: BandRepository,
    private val syncSettings: SyncSettings,
    private val scope: CoroutineScope,
) {
    fun start() {
        val isPaired = bandRepository.band.map { it != null }.distinctUntilChanged()
        scope.launch {
            combine(isPaired, syncSettings.isBackgroundSyncEnabled) { paired, enabled -> paired && enabled }
                .distinctUntilChanged()
                .collect { shouldRun ->
                    if (shouldRun) HistorySyncWorker.schedule(context) else HistorySyncWorker.cancel(context)
                }
        }
        scope.launch {
            isPaired.drop(1).filter { paired -> !paired }.collect { syncSettings.reset() }
        }
    }
}
