package ru.kardiomonitor.features.monitoring.liveheartrate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.monitoring.domain.HeartRate

/** Состояние секции «Пульс сейчас». */
internal data class LiveHeartRateUiState(
    val isAvailable: Boolean = false,
    val isMeasuring: Boolean = false,
    val isRequestedByClinic: Boolean = false,
    val heartRate: HeartRate? = null,
    val problem: String? = null,
) {
    /** Значение крупно: «—» — не измеряется, «…» — замер идёт. */
    val value: String
        get() = when {
            !isMeasuring -> "—"
            heartRate == null -> "…"
            else -> heartRate.bpm.toString()
        }
}

internal class LiveHeartRateViewModel(
    connection: BandConnection,
    private val measurement: LiveHeartRateMeasurement,
) : ViewModel() {
    val state: StateFlow<LiveHeartRateUiState> = combine(connection.state, measurement.state) { band, current ->
        LiveHeartRateUiState(
            isAvailable = band.isConnected,
            isMeasuring = current.mode != null,
            isRequestedByClinic = current.mode == LiveHeartRateMeasurement.Mode.Requested,
            heartRate = current.heartRate,
            problem = current.problem,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LiveHeartRateUiState())

    fun toggle() = measurement.toggleManual()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
