package ru.kardiomonitor.features.rehab.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.rehab.domain.DailyTargetProgress
import ru.kardiomonitor.features.rehab.domain.RehabOverview
import ru.kardiomonitor.features.rehab.domain.RehabPlanRepository
import ru.kardiomonitor.platform.ui.SectionCard
import ru.kardiomonitor.platform.ui.StatTile

/** Карточка текущего прогресса программы и выполнения сегодняшней нормы. */
@Composable
fun RehabProgressSection(
    registrations: ServerRegistrationRepository,
    plans: RehabPlanRepository,
    history: ActivityHistory,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel { RehabProgressViewModel(registrations, plans, history) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.isRegistered) RehabProgressContent(state, onRefresh, modifier)
}

@Composable
private fun RehabProgressContent(state: RehabProgressUiState, onRefresh: () -> Unit, modifier: Modifier) {
    SectionCard("Реабилитация", modifier) {
        val overview = state.overview
        if (overview == null) {
            Text(
                state.problem ?: "План пока не загружен. Проверьте интернет и обновите его.",
                color = if (state.problem == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        } else {
            ProgramProgress(overview)
            TodayProgress(overview.todayProgress)
        }
        OutlinedButton(onClick = onRefresh) { Text("Обновить план") }
    }
}

@Composable
private fun ProgramProgress(overview: RehabOverview) {
    val day = overview.programDay
    val title = when {
        overview.isCompleted -> "Программа завершена"
        day == null -> "Программа ещё не началась"
        else -> "День $day из ${overview.durationDays}"
    }
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (day != null) {
        LinearProgressIndicator(
            progress = { day.toFloat() / overview.durationDays },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text("Норма выполнена в ${overview.metDays} днях из ${overview.completedDays} завершённых")
    overview.currentStage?.let { stage ->
        Text(
            "Этап: дни ${stage.firstDay}–${stage.lastDay}, зона ${stage.targets.trainingZone.lowerBpm}–" +
                "${stage.targets.trainingZone.upperBpm} уд/мин",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TodayProgress(progress: DailyTargetProgress?) {
    if (progress == null) return
    Text("Норма на сегодня", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        StatTile("Тренировочные минуты", "${progress.trainingMinutes}/${progress.targets.trainingMinutes}")
        StatTile("Шаги", "${progress.steps}/${progress.targets.steps}")
    }
    val outcome = when {
        !progress.hasData -> "Сегодня данных пока нет"
        progress.isMet -> "Сегодняшняя норма выполнена"
        else -> "День не закончился — результат предварительный"
    }
    Text(outcome, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (progress.walkingMinutesWithoutHeartRate > 0) MissingHeartRateHint(progress)
}

@Composable
private fun MissingHeartRateHint(progress: DailyTargetProgress) {
    Text(
        "Пульс не измерен в ${progress.walkingMinutesWithoutHeartRate} из ${progress.walkingMinutes} минут ходьбы. " +
            "Для точного подсчёта включите измерение пульса раз в минуту.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
