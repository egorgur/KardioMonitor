package ru.kardiomonitor.features.band.connection

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
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.api.ConnectionState
import ru.kardiomonitor.features.band.api.SessionUnavailableException
import ru.kardiomonitor.features.band.domain.Band
import ru.kardiomonitor.features.band.domain.BandLink
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.band.domain.BatteryLog
import ru.kardiomonitor.features.band.domain.ConnectionProblem
import ru.kardiomonitor.features.band.domain.ConnectionStage
import ru.kardiomonitor.features.band.domain.OpenResult
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Точка входа слайса для композиционного корня: единственный сеанс связи с браслетом
 * (ADR-0003). Подключение и сценарии в сеансе выполняются по очереди ([sessionMutex]).
 * При каждом подключении сохраняет заряд и записывает выбранную частоту измерения пульса;
 * выбор частоты при открытом соединении записывается сразу. [scope] — scope приложения.
 */
class SingleBandConnection(
    private val link: BandLink,
    private val repository: BandRepository,
    private val batteryLog: BatteryLog,
    private val scope: CoroutineScope,
) : BandConnection {
    private val sessionMutex = Mutex()
    private val current = MutableStateFlow(ConnectionState())
    private val holds = AtomicInteger(0)

    @Volatile private var isOpen = false
    /** Пользователь нажал «Подключить» — соединение держим, пока он не отключит. */
    @Volatile private var isUserSession = false
    private var connectJob: Job? = null
    private var knownBand: Band? = null

    override val state: StateFlow<ConnectionState> = current.asStateFlow()

    init {
        link.onConnectionLost = ::onConnectionLost
        scope.launch { repository.band.collect(::onBandChanged) }
    }

    override fun connect() {
        if (connectJob?.isActive == true) return
        isUserSession = true
        current.update { it.copy(problem = null) }
        connectJob = scope.launch {
            sessionMutex.withLock {
                if (isOpen) {
                    current.update { it.copy(isBackground = false) }
                } else {
                    open(isBackground = false)?.let(::fail)
                }
            }
        }
    }

    override fun disconnect() {
        isUserSession = false
        connectJob?.cancel()
        close()
    }

    override fun closeIfIdle() {
        if (holds.get() == 0) disconnect()
    }

    override fun dismissProblem() {
        current.update { it.copy(problem = null) }
    }

    override suspend fun <T> withSession(isBackground: Boolean, block: suspend () -> T): T =
        sessionMutex.withLock {
            if (isOpen) return@withLock block()
            try {
                open(isBackground)?.let { problem -> throw SessionUnavailableException(problem, problem.text()) }
                block()
            } finally {
                if (!isUserSession) close()
            }
        }

    override suspend fun <T> holdOpen(block: suspend () -> T): T {
        holds.incrementAndGet()
        try {
            return block()
        } finally {
            holds.decrementAndGet()
        }
    }

    /** Открывает соединение; null — открыто, иначе — почему нет. Вызывать под [sessionMutex]. */
    private suspend fun open(isBackground: Boolean): ConnectionProblem? {
        val band = repository.band.value ?: return ConnectionProblem.NotPaired
        current.update { it.copy(isBackground = isBackground) }
        val result = link.open(band.credentials) { stage -> current.update { it.copy(stage = stage) } }
        if (result is OpenResult.Refused) {
            close()
            return result.problem
        }
        isOpen = true
        Log.i(TAG, "авторизация успешна (${if (isBackground) "фон" else "экран"})")
        return try {
            val battery = link.readBattery()
            batteryLog.record(battery, Instant.now())
            current.update { it.copy(stage = ConnectionStage.Connected, battery = battery) }
            applyHeartRateMeasurementInterval()
            null
        } catch (e: IOException) {
            close()
            ConnectionProblem.LinkFailed(e.message ?: e.toString())
        }
    }

    private suspend fun applyHeartRateMeasurementInterval() {
        val interval = repository.band.value?.heartRateMeasurementInterval ?: return
        try {
            link.writeHeartRateMeasurementInterval(interval)
            Log.i(TAG, "частота пульса записана в браслет: $interval")
            current.update { it.copy(appliedInterval = interval) }
        } catch (e: IOException) {
            Log.w(TAG, "не удалось записать частоту пульса", e)
            current.update { it.copy(problem = ConnectionProblem.SettingsNotApplied(e.message ?: e.toString())) }
        }
    }

    /** Новая привязка начинает сеанс с чистого состояния; новая частота при открытом соединении пишется сразу. */
    private suspend fun onBandChanged(band: Band?) {
        val previous = knownBand
        knownBand = band
        when {
            band?.credentials != previous?.credentials -> current.value = ConnectionState(stage = current.value.stage)
            band?.heartRateMeasurementInterval != previous?.heartRateMeasurementInterval && isOpen ->
                applyHeartRateMeasurementInterval()
        }
    }

    private fun onConnectionLost() {
        isOpen = false
        val wasUserSession = isUserSession
        isUserSession = false
        current.update {
            it.copy(
                stage = ConnectionStage.Disconnected,
                isBackground = false,
                problem = if (wasUserSession) it.problem ?: ConnectionProblem.Lost else it.problem,
            )
        }
    }

    private fun close() {
        link.close()
        isOpen = false
        current.update { it.copy(stage = ConnectionStage.Disconnected, isBackground = false) }
    }

    private fun fail(problem: ConnectionProblem) {
        isUserSession = false
        close()
        current.update { it.copy(problem = problem) }
    }

    private companion object {
        const val TAG = "KardioBLE"
    }
}
