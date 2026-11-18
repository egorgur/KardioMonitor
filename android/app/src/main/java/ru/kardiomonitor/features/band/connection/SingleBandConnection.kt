package ru.kardiomonitor.features.band.connection

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * При подключении сохраняет известный заряд. Частоту пишет только Band 6;
 * у Band 9 — только GET с независимыми nullable-полями. [scope] — scope приложения.
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
    /** Приложение свёрнуто во время [holdOpen] — закрыть соединение, когда удержание закончится. */
    @Volatile private var isCloseRequested = false
    private var connectJob: Job? = null
    private var knownBand: Band? = repository.band.value

    override val state: StateFlow<ConnectionState> = current.asStateFlow()

    init {
        link.onConnectionLost = ::onConnectionLost
        scope.launch { repository.band.collect(::onBandChanged) }
    }

    override fun connect() {
        if (connectJob?.isActive == true) return
        isUserSession = true
        isCloseRequested = false
        current.update { it.copy(problem = null) }
        connectJob = scope.launch {
            sessionMutex.withLock {
                if (isOpen) {
                    current.update { it.copy(isBackground = false) }
                } else {
                    try {
                        open(isBackground = false)?.let(::fail)
                    } catch (e: CancellationException) {
                        close()
                        throw e
                    }
                }
            }
        }
    }

    override fun disconnect() {
        isUserSession = false
        isCloseRequested = false
        connectJob?.cancel()
        close()
    }

    override fun closeIfIdle() {
        if (holds.get() == 0) disconnect() else isCloseRequested = true
    }

    override fun dismissProblem() {
        current.update { it.copy(problem = null) }
    }

    override suspend fun <T> withSession(isBackground: Boolean, block: suspend () -> T): T = holdOpen {
        sessionMutex.withLock { inSession(isBackground, block) }
    }

    override suspend fun <T> withSessionIf(
        isBackground: Boolean,
        isAllowed: () -> Boolean,
        block: suspend () -> T,
    ): T? = holdOpen {
        sessionMutex.withLock { if (isAllowed()) inSession(isBackground, block) else null }
    }

    private suspend fun <T> inSession(isBackground: Boolean, block: suspend () -> T): T {
        try {
            if (!isOpen) {
                open(isBackground)?.let { problem -> throw SessionUnavailableException(problem, problem.text()) }
            }
            return block()
        } finally {
            if (!isUserSession) close()
        }
    }

    override suspend fun <T> holdOpen(block: suspend () -> T): T {
        holds.incrementAndGet()
        try {
            return block()
        } finally {
            if (holds.decrementAndGet() == 0 && isCloseRequested) disconnect()
        }
    }

    override suspend fun <T> withOpenSession(block: suspend () -> T): T = holdOpen {
        sessionMutex.withLock {
            if (!isOpen) {
                throw SessionUnavailableException(ConnectionProblem.NotConnected, ConnectionProblem.NotConnected.text())
            }
            block()
        }
    }

    /** Открывает соединение; null — открыто, иначе — почему нет. Вызывать под [sessionMutex]. */
    private suspend fun open(isBackground: Boolean): ConnectionProblem? {
        val band = repository.band.value ?: return ConnectionProblem.NotPaired
        current.update {
            it.copy(isBackground = isBackground, battery = null, appliedInterval = null, reportedHeartRateSettings = null)
        }
        val result = try {
            link.open(band.credentials) { stage -> current.update { it.copy(stage = stage) } }
        } catch (e: IOException) {
            close()
            return ConnectionProblem.LinkFailed(e.message ?: "не удалось открыть соединение")
        }
        if (result is OpenResult.Refused) {
            close()
            return result.problem
        }
        isOpen = true
        Log.i(TAG, "авторизация успешна (${if (isBackground) "фон" else "экран"})")
        return readStatus(band)
    }

    private suspend fun readStatus(band: Band): ConnectionProblem? = try {
        ensureCurrentBand(band)
        val battery = link.readBattery()
        val reportedSettings = link.readHeartRateSettings()
        ensureCurrentBand(band)
        battery?.let { batteryLog.record(it, Instant.now()) }
        current.update {
            it.copy(stage = ConnectionStage.Connected, battery = battery, reportedHeartRateSettings = reportedSettings)
        }
        applyHeartRateMeasurementInterval()
        null
    } catch (e: IOException) {
        close()
        ConnectionProblem.LinkFailed(e.message ?: e.toString())
    }

    private suspend fun ensureCurrentBand(band: Band) {
        currentCoroutineContext().ensureActive()
        if (repository.band.value?.credentials != band.credentials || !isOpen) {
            throw IOException("привязка или соединение изменились во время подключения")
        }
    }

    private suspend fun applyHeartRateMeasurementInterval() {
        val band = repository.band.value ?: return
        if (!band.credentials.protocol.canWriteMeasurementInterval) return
        val interval = band.heartRateMeasurementInterval ?: return
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
            band?.credentials != previous?.credentials -> sessionMutex.withLock {
                val shouldReconnect = isOpen && isUserSession && band != null
                if (isOpen) close()
                current.value = ConnectionState(stage = current.value.stage)
                if (shouldReconnect) open(isBackground = false)?.let(::fail)
            }
            band?.heartRateMeasurementInterval != previous?.heartRateMeasurementInterval && isOpen ->
                sessionMutex.withLock { if (isOpen) applyHeartRateMeasurementInterval() }
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
