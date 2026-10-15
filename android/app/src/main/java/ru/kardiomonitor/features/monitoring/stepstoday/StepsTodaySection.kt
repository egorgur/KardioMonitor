package ru.kardiomonitor.features.monitoring.stepstoday

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.monitoring.domain.ActivitySource
import ru.kardiomonitor.platform.ui.StatTile

/** Точка входа слайса «Шаги сегодня»: показатель с браслета, обновляется по мере ходьбы. */
@Composable
fun StepsTodaySection(connection: BandConnection, source: ActivitySource) {
    val viewModel = viewModel { StepsTodayViewModel(connection, source) }
    val steps by viewModel.stepsToday.collectAsStateWithLifecycle()
    StatTile("Шагов сегодня", steps?.value?.toString() ?: "—")
}
