package ru.kardiomonitor.features.monitoring.dailysummary

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.DailySummary
import ru.kardiomonitor.platform.ui.HeartRatePoint
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Состояние секции итогов дня: заголовок дня, итоги и ряды для графиков. */
internal data class DailySummaryUiState(
    val day: LocalDate,
    val title: String,
    val canShowNextDay: Boolean,
    val summary: DailySummary,
    val heartRatePoints: List<HeartRatePoint> = emptyList(),
    val hourlySteps: List<Int> = List(HOURS_PER_DAY) { 0 },
)

private const val HOURS_PER_DAY = 24

/** Итоги выбранного дня; перечитывает день, когда история пополнилась (синхронизация с экрана или в фоне). */
internal class DailySummaryViewModel(private val history: ActivityHistory) : ViewModel() {
    private val selectedDay = MutableStateFlow(LocalDate.now())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<DailySummaryUiState> = selectedDay
        .flatMapLatest { day -> history.changes.onStart { emit(Unit) }.map { load(day) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyState(selectedDay.value))

    fun showDay(day: LocalDate) {
        if (day.isAfter(LocalDate.now())) return
        selectedDay.value = day
    }

    private suspend fun load(day: LocalDate): DailySummaryUiState {
        val minutes = try {
            history.minutesOf(day)
        } catch (e: IOException) {
            Log.w(TAG, "не удалось прочитать историю за $day", e)
            return emptyState(day)
        }
        val dayStart = day.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val minuteOfDay = { minute: ActivityMinute -> ((minute.start.epochSecond - dayStart.epochSecond) / 60).toInt() }
        return emptyState(day).copy(
            summary = DailySummary.of(minutes),
            heartRatePoints = minutes.mapNotNull { m -> m.heartRate?.let { HeartRatePoint(minuteOfDay(m), it.bpm) } },
            hourlySteps = hourlySteps(minutes.map { minuteOfDay(it) to it.steps.value }),
        )
    }

    private fun hourlySteps(stepsByMinute: List<Pair<Int, Int>>): List<Int> {
        val hours = IntArray(HOURS_PER_DAY)
        for ((minute, steps) in stepsByMinute) {
            if (minute in 0 until HOURS_PER_DAY * 60) hours[minute / 60] += steps
        }
        return hours.toList()
    }

    private fun emptyState(day: LocalDate): DailySummaryUiState {
        val today = LocalDate.now()
        val title = when (day) {
            today -> "Сегодня"
            today.minusDays(1) -> "Вчера"
            else -> day.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")))
        }
        return DailySummaryUiState(day, title, canShowNextDay = day < today, summary = DailySummary.of(emptyList()))
    }

    private companion object {
        const val TAG = "KardioBLE"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
