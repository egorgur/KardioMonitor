package ru.kardiomonitor.features.monitoring.stepstoday

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.monitoring.domain.ActivitySource
import ru.kardiomonitor.features.monitoring.domain.StepCount
import java.io.IOException

/** Шаги за сегодня с браслета — пока он подключён; null — значения нет. */
internal class StepsTodayViewModel(connection: BandConnection, private val source: ActivitySource) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val stepsToday: StateFlow<StepCount?> = connection.state
        .map { it.isConnected }
        .distinctUntilChanged()
        .flatMapLatest { isConnected -> if (isConnected && source.monitoringUnavailableReason == null) followSteps() else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private fun followSteps(): Flow<StepCount?> = source.stepsToday()
        .map<StepCount, StepCount?> { it }
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "не удалось получить шаги за сегодня", e)
            emit(null)
        }

    private companion object {
        const val TAG = "KardioBLE"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
