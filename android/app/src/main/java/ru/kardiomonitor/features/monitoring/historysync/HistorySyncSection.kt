package ru.kardiomonitor.features.monitoring.historysync

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.api.BandPairing
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.monitoring.domain.ActivitySource
import ru.kardiomonitor.features.monitoring.domain.SyncSettings
import ru.kardiomonitor.features.monitoring.domain.HistorySyncEvents
import ru.kardiomonitor.platform.ui.SectionCard

/**
 * Точка входа слайса «Синхронизация истории» на экране: итог последней синхронизации,
 * кнопка «Синхронизировать», переключатель фоновой синхронизации. Фон — [HistorySyncWorker].
 */
@Composable
fun HistorySyncSection(
    connection: BandConnection,
    pairing: BandPairing,
    source: ActivitySource,
    history: ActivityHistory,
    settings: SyncSettings,
    events: HistorySyncEvents = HistorySyncEvents.None,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel {
        HistorySyncViewModel(
            connection,
            SyncHistory(connection, pairing, source, history, settings, events = events),
            settings,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    HistorySyncContent(
        state = state,
        onSync = viewModel::sync,
        onBackgroundSyncChange = viewModel::setBackgroundSyncEnabled,
        modifier = modifier,
    )
}

@Composable
private fun HistorySyncContent(
    state: HistorySyncUiState,
    onSync: () -> Unit,
    onBackgroundSyncChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard("Синхронизация", modifier) {
        state.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(state.lastSync, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Синхронизировать в фоне каждые 30 мин", modifier = Modifier.weight(1f))
            Switch(checked = state.isBackgroundSyncEnabled, onCheckedChange = onBackgroundSyncChange)
        }
        if (state.isConnected) {
            Button(onClick = onSync, enabled = state.canSync) { Text("Синхронизировать") }
        }
    }
}
