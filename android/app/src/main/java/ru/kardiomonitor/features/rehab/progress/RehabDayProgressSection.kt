package ru.kardiomonitor.features.rehab.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.rehab.domain.RehabPlanRepository
import ru.kardiomonitor.platform.ui.SectionCard
import java.time.LocalDate

/** Норма реабилитации и локально рассчитанный результат выбранного дня. */
@Composable
fun RehabDayProgressSection(
    day: LocalDate,
    plans: RehabPlanRepository,
    history: ActivityHistory,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel(key = day.toString()) { RehabDayProgressViewModel(day, plans, history) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state is RehabDayProgressUiState.Available) {
        RehabDayProgressCard(state as RehabDayProgressUiState.Available, modifier)
    }
}

@Composable
private fun RehabDayProgressCard(state: RehabDayProgressUiState.Available, modifier: Modifier = Modifier) {
    SectionCard("Норма реабилитации", modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("День ${state.dayNumber} из ${state.durationDays}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Этап: дни ${state.stage.firstDay}–${state.stage.lastDay}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    when {
                        !state.progress.hasData -> "Нет данных"
                        state.progress.isMet -> "Выполнено"
                        state.progress.isFinal -> "Не выполнено"
                        else -> "Предварительно"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.progress.isMet) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            TargetRow(
                label = "Тренировка ${state.stage.targets.trainingZone.lowerBpm}–${state.stage.targets.trainingZone.upperBpm} уд/мин",
                actual = state.progress.trainingMinutes,
                target = state.stage.targets.trainingMinutes,
                suffix = "мин",
            )
            TargetRow(
                label = "Шаги за день",
                actual = state.progress.steps,
                target = state.stage.targets.steps,
                suffix = "",
            )
            if (state.progress.walkingMinutesWithoutHeartRate > 0) {
                Text(
                    "В ${state.progress.walkingMinutesWithoutHeartRate} мин ходьбы нет пульса — они не засчитаны как тренировка.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TargetRow(label: String, actual: Int, target: Int, suffix: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            "$actual / $target${if (suffix.isEmpty()) "" else " $suffix"}",
            style = MaterialTheme.typography.titleSmall,
        )
    }
}
