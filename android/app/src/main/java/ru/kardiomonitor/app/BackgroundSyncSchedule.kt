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
import ru.kardiomonitor.features.band.domain.BandProtocol
import ru.kardiomonitor.features.band.domain.backgroundWorkIdentity
import ru.kardiomonitor.features.band.statusrefresh.BandStatusWorker
import ru.kardiomonitor.features.monitoring.domain.SyncSettings
import ru.kardiomonitor.features.monitoring.historysync.HistorySyncWorker

/**
 * Связь контекстов на уровне композиционного корня (domain-model.md, «События предметной
 * области»): для Band 6 — история, для Band 9 — только проверка связи/заряда/настроек.
 * Смена протокола снимает несовместимую работу, отвязка и выключение фона снимают обе.
 * «Браслет отвязан» также сбрасывает настройки синхронизации.
 */
class BackgroundSyncSchedule(
    private val context: Context,
    private val bandRepository: BandRepository,
    private val syncSettings: SyncSettings,
    private val scope: CoroutineScope,
    private val historySchedule: () -> Unit = { HistorySyncWorker.schedule(context) },
    private val historyCancel: () -> Unit = { HistorySyncWorker.cancel(context) },
    private val statusSchedule: (String) -> Unit = { BandStatusWorker.schedule(context, it) },
    private val statusCancel: () -> Unit = { BandStatusWorker.cancel(context) },
) {
    fun start() {
        val isPaired = bandRepository.band.map { it != null }.distinctUntilChanged()
        scope.launch {
            combine(bandRepository.band, syncSettings.isBackgroundSyncEnabled) { band, enabled ->
                band.takeIf { enabled }
            }.distinctUntilChanged().collect { band ->
                when (band?.credentials?.protocol) {
                    BandProtocol.MiBand6Ble -> {
                        statusCancel()
                        historySchedule()
                    }
                    BandProtocol.XiaomiBand9Spp -> {
                        historyCancel()
                        statusSchedule(band.backgroundWorkIdentity)
                    }
                    null -> {
                        historyCancel()
                        statusCancel()
                    }
                }
            }
        }
        scope.launch {
            isPaired.drop(1).filter { paired -> !paired }.collect { syncSettings.reset() }
        }
    }
}
