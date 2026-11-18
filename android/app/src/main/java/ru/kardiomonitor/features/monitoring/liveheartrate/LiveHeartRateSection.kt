package ru.kardiomonitor.features.monitoring.liveheartrate

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.platform.ui.SectionCard

/**
 * Точка входа слайса «Пульс сейчас»: непрерывное измерение по запросу (для тренировок).
 * Видна, пока браслет подключён. Значения публикует общий [LiveHeartRateMeasurement].
 */
@Composable
fun LiveHeartRateSection(
    connection: BandConnection,
    measurement: LiveHeartRateMeasurement,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel { LiveHeartRateViewModel(connection, measurement) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.isAvailable) LiveHeartRateContent(state, onToggle = viewModel::toggle, modifier = modifier)
}

@Composable
private fun LiveHeartRateContent(state: LiveHeartRateUiState, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard("Пульс сейчас", modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(state.value, fontSize = 56.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("уд/мин", modifier = Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.isMeasuring && state.heartRate == null) {
            Text("Измерение… (5–15 с, браслет должен быть на руке)", style = MaterialTheme.typography.bodySmall)
        }
        if (state.isRequestedByClinic) {
            Text("Измерение запрошено клиникой. Остановить его можно в постоянном уведомлении.")
        }
        state.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onToggle, enabled = !state.isRequestedByClinic) {
            Text(if (state.isMeasuring) "Остановить" else "Измерять")
        }
    }
}
