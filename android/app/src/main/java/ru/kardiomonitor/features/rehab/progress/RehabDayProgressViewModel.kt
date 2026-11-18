package ru.kardiomonitor.features.rehab.progress

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.rehab.domain.DailyTargetProgress
import ru.kardiomonitor.features.rehab.domain.RehabPlan
import ru.kardiomonitor.features.rehab.domain.RehabPlanRepository
import ru.kardiomonitor.features.rehab.domain.RehabStage
import java.time.LocalDate

internal sealed interface RehabDayProgressUiState {
    data object Unavailable : RehabDayProgressUiState

    data class Available(
        val dayNumber: Int,
        val durationDays: Int,
        val stage: RehabStage,
        val progress: DailyTargetProgress,
    ) : RehabDayProgressUiState
}

/** Считает назначение и результат выбранного пациентского дня только из локальных данных. */
internal class RehabDayProgressViewModel(
    day: LocalDate,
    plans: RehabPlanRepository,
    history: ActivityHistory,
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<RehabDayProgressUiState> = plans.plan
        .flatMapLatest { plan -> stateFor(day, plan, history) }
        .catch { error ->
            Log.e("RehabDayProgress", "Не удалось рассчитать выбранный день", error)
            emit(RehabDayProgressUiState.Unavailable)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RehabDayProgressUiState.Unavailable)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun stateFor(
        day: LocalDate,
        plan: RehabPlan?,
        history: ActivityHistory,
    ): Flow<RehabDayProgressUiState> {
        val revision = plan?.revisionOn(day) ?: return flow { emit(RehabDayProgressUiState.Unavailable) }
        val program = plan.programOn(day) ?: return flow { emit(RehabDayProgressUiState.Unavailable) }
        val dayNumber = program.dayNumber(day) ?: return flow { emit(RehabDayProgressUiState.Unavailable) }
        val stage = program.stageOn(day) ?: return flow { emit(RehabDayProgressUiState.Unavailable) }
        return history.changes
            .map { Unit }
            .onStart { emit(Unit) }
            .flatMapLatest {
                flow {
                    val minutes = history.minutesOf(day, plan.timeZone).map { it.toRehabActivityMinute() }
                    emit(
                        RehabDayProgressUiState.Available(
                            dayNumber = dayNumber,
                            durationDays = program.durationDays,
                            stage = stage,
                            progress = DailyTargetProgress.of(
                                minutes = minutes,
                                targets = stage.targets,
                                walkingStepsThreshold = revision.walkingStepsThreshold,
                                isFinal = day.isBefore(LocalDate.now(plan.timeZone)),
                            ),
                        ),
                    )
                }
            }
    }
}
