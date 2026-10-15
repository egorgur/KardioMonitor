package ru.kardiomonitor.features.monitoring.liveheartrate

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.monitoring.domain.ActivitySource
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateFeed
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateReading
import java.io.IOException
import java.time.Clock

/** Состояние секции «Пульс сейчас». */
internal data class LiveHeartRateUiState(
    val isAvailable: Boolean = false,
    val isMeasuring: Boolean = false,
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
    private val connection: BandConnection,
    private val source: ActivitySource,
    private val feed: LiveHeartRateFeed,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val measurement = MutableStateFlow(LiveHeartRateUiState())
    private var job: Job? = null

    val state: StateFlow<LiveHeartRateUiState> = combine(connection.state, measurement) { band, current ->
        current.copy(isAvailable = band.isConnected)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LiveHeartRateUiState())

    init {
        // Соединение потеряно или закрыто — измерение прекращается вместе с ним.
        viewModelScope.launch {
            connection.state.collect { if (!it.isConnected) stop() }
        }
    }

    fun toggle() {
        if (job?.isActive == true) stop() else start()
    }

    private fun start() {
        measurement.value = LiveHeartRateUiState(isMeasuring = true)
        job = viewModelScope.launch {
            try {
                // Пока идёт измерение, свёрнутое приложение не закрывает соединение (тренировка с выключенным экраном).
                connection.holdOpen {
                    source.liveHeartRate().collect { heartRate ->
                        measurement.update { it.copy(heartRate = heartRate) }
                        // Событие «пульс сейчас измерен» — для передачи врачу (контекст clinic)
                        heartRate?.let { feed.publish(LiveHeartRateReading(clock.instant(), it)) }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "измерение пульса прервано", e)
                measurement.value = LiveHeartRateUiState(problem = "Пульс: ${e.message}")
            }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        measurement.update { it.copy(isMeasuring = false, heartRate = null) }
    }

    private companion object {
        const val TAG = "KardioBLE"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
