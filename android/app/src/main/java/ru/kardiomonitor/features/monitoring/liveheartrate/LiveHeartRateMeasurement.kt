package ru.kardiomonitor.features.monitoring.liveheartrate

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.monitoring.domain.ActivitySource
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateFeed
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateReading
import java.io.IOException
import java.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Единственный владелец ручного и запрошенного клиникой непрерывного измерения. */
class LiveHeartRateMeasurement(
    private val connection: BandConnection,
    private val source: ActivitySource,
    private val feed: LiveHeartRateFeed,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()
    private val current = MutableStateFlow(State())
    private var manualJob: Job? = null
    internal val state: StateFlow<State> = current.asStateFlow()

    internal fun toggleManual() {
        if (manualJob?.isActive == true) stopManual() else startManual()
    }

    suspend fun runRequested(durationSeconds: Long) {
        source.monitoringUnavailableReason?.let { throw IOException(it) }
        stopManual()
        mutex.withLock {
            current.value = State(mode = Mode.Requested)
            try {
                connection.withSession(isBackground = true) {
                    withTimeoutOrNull(durationSeconds.seconds) { collectReadings() }
                }
            } finally {
                current.value = State()
            }
        }
    }

    private fun startManual() {
        if (current.value.mode == Mode.Requested) return
        source.monitoringUnavailableReason?.let {
            current.value = State(problem = it)
            return
        }
        manualJob = scope.launch {
            mutex.withLock {
                current.value = State(mode = Mode.Manual)
                try {
                    connection.holdOpen { collectReadings() }
                } catch (e: IOException) {
                    Log.w(TAG, "измерение пульса прервано", e)
                    current.value = State(problem = "Пульс: ${e.message}")
                } finally {
                    if (current.value.mode == Mode.Manual) current.value = State()
                }
            }
        }
    }

    private fun stopManual() {
        manualJob?.cancel()
        manualJob = null
        if (current.value.mode == Mode.Manual) current.value = State()
    }

    private suspend fun collectReadings() {
        source.liveHeartRate().collect { heartRate ->
            current.update { it.copy(heartRate = heartRate) }
            heartRate?.let { feed.publish(LiveHeartRateReading(clock.instant(), it)) }
        }
    }

    internal data class State(
        val mode: Mode? = null,
        val heartRate: HeartRate? = null,
        val problem: String? = null,
    )

    internal enum class Mode { Manual, Requested }

    private companion object {
        const val TAG = "KardioBLE"
    }
}
