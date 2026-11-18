package ru.kardiomonitor.features.band.measurementinterval

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.band.domain.HeartRateMeasurementInterval
import ru.kardiomonitor.platform.ui.SectionCard

/** Точка входа слайса «Частота измерения пульса»: выбор 1, 5, 10, 30 мин или «Выкл». */
@Composable
fun MeasurementIntervalSection(
    repository: BandRepository,
    connection: BandConnection,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel { MeasurementIntervalViewModel(repository, connection) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    MeasurementIntervalContent(state, onSelect = viewModel::select, modifier = modifier)
}

@Composable
private fun MeasurementIntervalContent(
    state: MeasurementIntervalUiState,
    onSelect: (HeartRateMeasurementInterval) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard("Частота измерения пульса", modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (choice in state.choices) {
                FilterChip(
                    selected = choice.isSelected,
                    onClick = { onSelect(choice.interval) },
                    label = { Text(choice.label) },
                )
            }
        }
        Text(state.hint, style = MaterialTheme.typography.bodySmall)
        state.status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.choices.isNotEmpty()) {
            Text(
                "Mi Fitness при подключении к браслету может вернуть свою настройку — " +
                    "приложение повторно записывает выбранную частоту при каждом подключении.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
