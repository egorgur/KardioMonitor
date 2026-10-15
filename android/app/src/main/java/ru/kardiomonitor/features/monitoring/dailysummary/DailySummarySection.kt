package ru.kardiomonitor.features.monitoring.dailysummary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.monitoring.domain.DailySummary
import ru.kardiomonitor.platform.ui.HeartRateChart
import ru.kardiomonitor.platform.ui.SectionCard
import ru.kardiomonitor.platform.ui.StatTile
import ru.kardiomonitor.platform.ui.StepsChart
import java.time.LocalDate

/** Точка входа слайса «Итоги дня»: навигация по дням, итоги, график пульса и шаги по часам. */
@Composable
fun DailySummarySection(history: ActivityHistory, modifier: Modifier = Modifier) {
    val viewModel = viewModel { DailySummaryViewModel(history) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    DailySummaryContent(state, onShowDay = viewModel::showDay, modifier = modifier)
}

@Composable
private fun DailySummaryContent(
    state: DailySummaryUiState,
    onShowDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onShowDay(state.day.minusDays(1)) }) { Text("‹ Назад") }
            Text(state.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onShowDay(state.day.plusDays(1)) }, enabled = state.canShowNextDay) {
                Text("Вперёд ›")
            }
        }
        SummaryCard(state.summary)
        SectionCard("Пульс, уд/мин") { HeartRateChart(state.heartRatePoints) }
        SectionCard("Шаги по часам") { StepsChart(state.hourlySteps) }
    }
}

@Composable
private fun SummaryCard(summary: DailySummary) {
    SectionCard("Итоги дня") {
        if (!summary.hasData) {
            Text(
                "Нет данных за этот день. Подключите браслет и синхронизируйте.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val min = summary.minHeartRate
        val max = summary.maxHeartRate
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile("Шаги", summary.steps.value.toString())
            StatTile("Пульс ср.", summary.averageHeartRate?.bpm?.toString() ?: "—")
            StatTile("Мин–макс", if (min == null || max == null) "—" else "${min.bpm}–${max.bpm}")
        }
        val recorded = summary.recordedMinutes
        Text(
            "Записано ${recorded / 60} ч ${recorded % 60} мин, пульс есть в ${summary.heartRateCoveragePercent}% минут" +
                if (summary.notWornMinutes > 0) ", браслет снят ${summary.notWornMinutes} мин" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
