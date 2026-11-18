package ru.kardiomonitor.features.rehab.progress

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.rehab.domain.RehabOverview
import ru.kardiomonitor.features.rehab.domain.RehabPlan
import ru.kardiomonitor.features.rehab.domain.RehabPlanRepository
import java.io.IOException
import java.time.Clock
import java.time.LocalDate

/** Состояние карточки реабилитации пациента. */
internal data class RehabProgressUiState(
    val isRegistered: Boolean = false,
    val overview: RehabOverview? = null,
    val problem: String? = null,
)

/** Пересчитывает прогресс при обновлении плана или локальной истории. */
internal class RehabProgressViewModel(
    registrations: ServerRegistrationRepository,
    plans: RehabPlanRepository,
    private val history: ActivityHistory,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<RehabProgressUiState> = combine(registrations.registration, plans.plan) { registration, plan ->
        Pair(registration != null, plan)
    }.flatMapLatest { (isRegistered, plan) -> statesFor(isRegistered, plan) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RehabProgressUiState())

    private fun statesFor(isRegistered: Boolean, plan: RehabPlan?): Flow<RehabProgressUiState> {
        if (!isRegistered || plan == null) return flowOf(RehabProgressUiState(isRegistered = isRegistered))
        return history.changes.onStart { emit(Unit) }.map { load(plan) }
    }

    private suspend fun load(plan: RehabPlan): RehabProgressUiState {
        val today = LocalDate.now(clock.withZone(plan.timeZone))
        val program = plan.current.program
        val lastDay = minOf(today, program.lastDate)
        val minutes = try {
            if (lastDay.isBefore(program.startDate)) emptyMap()
            else history.minutesBetween(program.startDate, lastDay, plan.timeZone)
        } catch (e: IOException) {
            Log.w(TAG, "не удалось рассчитать прогресс реабилитации", e)
            return RehabProgressUiState(isRegistered = true, problem = e.message)
        }
        val rehabMinutes = minutes.mapValues { (_, dayMinutes) -> dayMinutes.map { it.toRehabActivityMinute() } }
        return RehabProgressUiState(isRegistered = true, overview = RehabOverview.of(plan, today, rehabMinutes))
    }

    private companion object {
        const val TAG = "KardioBLE"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
