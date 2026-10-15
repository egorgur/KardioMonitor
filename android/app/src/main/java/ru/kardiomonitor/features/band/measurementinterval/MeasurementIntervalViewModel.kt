package ru.kardiomonitor.features.band.measurementinterval

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.api.ConnectionState
import ru.kardiomonitor.features.band.domain.Band
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.band.domain.HeartRateMeasurementInterval

/** Вариант частоты в интерфейсе. */
internal data class IntervalChoice(val interval: HeartRateMeasurementInterval, val label: String, val isSelected: Boolean)

/** Состояние секции частоты измерения пульса. [status] — null, пока частота из приложения не задавалась. */
internal data class MeasurementIntervalUiState(
    val choices: List<IntervalChoice> = emptyList(),
    val hint: String = "",
    val status: String? = null,
)

internal class MeasurementIntervalViewModel(
    private val repository: BandRepository,
    connection: BandConnection,
) : ViewModel() {
    val state: StateFlow<MeasurementIntervalUiState> = combine(repository.band, connection.state, ::toUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MeasurementIntervalUiState())

    /** Выбор сохраняется сразу; в браслет его записывает сеанс связи — сейчас или при подключении. */
    fun select(interval: HeartRateMeasurementInterval) {
        val band = repository.band.value ?: return
        repository.save(band.withHeartRateMeasurementInterval(interval))
    }

    private fun toUiState(band: Band?, connection: ConnectionState): MeasurementIntervalUiState {
        val selected = band?.heartRateMeasurementInterval
        return MeasurementIntervalUiState(
            choices = HeartRateMeasurementInterval.CHOICES.map { IntervalChoice(it, label(it), it == selected) },
            hint = hint(selected),
            status = status(selected, connection),
        )
    }

    private fun label(interval: HeartRateMeasurementInterval): String = when (interval) {
        HeartRateMeasurementInterval.Off -> "Выкл"
        is HeartRateMeasurementInterval.Every -> "${interval.minutes} мин"
    }

    private fun hint(selected: HeartRateMeasurementInterval?): String = when (selected) {
        null -> "Сейчас действует настройка из Mi Fitness."
        HeartRateMeasurementInterval.Off ->
            "Пульс не будет записываться в историю. Измерение «Пульс сейчас» продолжит работать."
        HeartRateMeasurementInterval.Every(1) -> "Подробная картина пульса за сутки. Браслет разряжается быстрее всего."
        HeartRateMeasurementInterval.Every(5) -> "Баланс между подробностью данных и зарядом браслета."
        else -> "Экономит заряд, но короткие подъёмы пульса можно пропустить."
    }

    private fun status(selected: HeartRateMeasurementInterval?, connection: ConnectionState): String? = when {
        selected == null -> null
        selected == connection.appliedInterval -> "✓ Записано в браслет"
        connection.isConnected -> "Записывается в браслет…"
        else -> "Будет записано в браслет при подключении"
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
