package ru.kardiomonitor.features.monitoring.historysync

import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.api.BandPairing
import ru.kardiomonitor.features.monitoring.api.ActivityHistoryRefresh
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.monitoring.domain.ActivitySource
import ru.kardiomonitor.features.monitoring.domain.SyncSettings
import ru.kardiomonitor.features.monitoring.domain.HistorySyncEvents
import kotlin.time.Duration.Companion.minutes

/** Опубликованный адаптер фоновой синхронизации для сценариев, которым нужны свежие минуты. */
class BackgroundActivityHistoryRefresh(
    connection: BandConnection,
    pairing: BandPairing,
    source: ActivitySource,
    history: ActivityHistory,
    settings: SyncSettings,
    events: HistorySyncEvents = HistorySyncEvents.None,
) : ActivityHistoryRefresh {
    private val syncHistory = SyncHistory(connection, pairing, source, history, settings, events = events)

    override suspend fun refresh(): Boolean = syncHistory(isBackground = true, timeout = TIMEOUT) is SyncResult.Downloaded

    private companion object {
        val TIMEOUT = 3.minutes
    }
}
