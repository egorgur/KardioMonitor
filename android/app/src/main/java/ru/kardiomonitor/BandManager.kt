package ru.kardiomonitor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.kardiomonitor.data.BandConfig
import ru.kardiomonitor.data.BandSettings
import ru.kardiomonitor.data.LastSync
import ru.kardiomonitor.data.SampleStore
import ru.kardiomonitor.features.band.domain.ConnectionStage
import ru.kardiomonitor.platform.huami.AuthException
import ru.kardiomonitor.platform.huami.HuamiBand
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

data class BandState(
    val connection: ConnectionStage = ConnectionStage.Disconnected,
    /** Соединение открыто фоновой синхронизацией, а не кнопкой «Подключить». */
    val background: Boolean = false,
    val error: String? = null,
    val battery: Int? = null,
    val stepsToday: Int? = null,
    val liveHeartRate: Int? = null,
    val liveHeartRateOn: Boolean = false,
    val syncing: Boolean = false,
    val syncStatus: String? = null,
    val lastSync: LastSync? = null,
    /** Частота фонового пульса, заданная из приложения (мин, 0 — выкл); null — действует настройка Mi Fitness. */
    val hrInterval: Int? = null,
    /** Выбрана, но ещё не записана в браслет — применится при подключении. */
    val hrIntervalPending: Boolean = false,
    /** Увеличивается после записи новых данных в базу — экран перечитывает день. */
    val dataVersion: Int = 0,
)

/**
 * Единственное соединение с браслетом на всё приложение. Браслет держит одно соединение,
 * поэтому экран и фоновая синхронизация работают через этот объект и не мешают друг другу:
 * подключение и синхронизация выполняются по очереди (sessionMutex).
 */
class BandManager private constructor(private val context: Context) {
    val store = SampleStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val band = HuamiBand(context, scope)
    private val sessionMutex = Mutex()

    @Volatile private var isOpen = false
    /** Пользователь нажал «Подключить» — соединение держим, пока он не отключит. */
    @Volatile private var userSession = false
    private var connectJob: Job? = null
    private var heartRateJob: Job? = null
    private var stepsJob: Job? = null

    private val _state = MutableStateFlow(
        BandSettings.heartRateInterval(context).let { interval ->
            BandState(hrInterval = interval, hrIntervalPending = interval != null, lastSync = BandSettings.lastSync(context))
        }
    )
    val state: StateFlow<BandState> = _state

    init {
        band.onConnectionLost = {
            heartRateJob?.cancel()
            stepsJob?.cancel()
            isOpen = false
            val wasUser = userSession
            userSession = false
            _state.update {
                it.copy(
                    connection = ConnectionStage.Disconnected, background = false, liveHeartRateOn = false,
                    liveHeartRate = null, syncing = false,
                    error = if (wasUser) it.error ?: "Соединение с браслетом потеряно" else it.error,
                )
            }
        }
    }

    /** Подключение по кнопке. Если фоновая синхронизация уже подключена, соединение просто остаётся открытым. */
    fun connect(config: BandConfig) {
        if (connectJob?.isActive == true) return
        userSession = true
        _state.update { it.copy(error = null) }
        connectJob = scope.launch {
            try {
                sessionMutex.withLock {
                    if (isOpen) {
                        _state.update { it.copy(background = false) }
                    } else {
                        open(config, background = false)
                        sync(background = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                fail("Ошибка авторизации: ${e.message}. Проверьте ключ.")
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            }
        }
    }

    fun disconnect() {
        userSession = false
        connectJob?.cancel()
        close()
    }

    private suspend fun open(config: BandConfig, background: Boolean) {
        _state.update { it.copy(background = background) }
        band.open(config.mac, config.key) { stage -> _state.update { it.copy(connection = stage) } }
        Log.i(TAG, "авторизация успешна (${if (background) "фон" else "экран"})")
        isOpen = true

        val battery = band.readBattery()
        withContext(Dispatchers.IO) { store.insertBattery(Instant.now().epochSecond, battery) }
        _state.update { it.copy(connection = ConnectionStage.Connected, battery = battery) }
        stepsJob = scope.launch { followStepsToday() }
        applyHeartRateInterval()
    }

    private suspend fun followStepsToday() {
        try {
            band.stepsToday().collect { steps -> _state.update { it.copy(stepsToday = steps.value) } }
        } catch (e: IOException) {
            Log.w(TAG, "не удалось получить шаги за сегодня", e)
        }
    }

    private fun close() {
        heartRateJob?.cancel()
        stepsJob?.cancel()
        band.close()
        isOpen = false
        _state.update {
            it.copy(connection = ConnectionStage.Disconnected, background = false, liveHeartRateOn = false,
                liveHeartRate = null, syncing = false)
        }
    }

    private fun fail(message: String) {
        userSession = false
        close()
        _state.update { it.copy(error = message) }
    }

    fun syncNow() {
        scope.launch {
            try {
                sessionMutex.withLock { if (isOpen) sync(background = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(syncing = false, syncStatus = "Ошибка синхронизации: ${e.message}") }
            }
        }
    }

    /**
     * Вызывается из SyncWorker. Если соединение уже открыто — синхронизирует через него,
     * иначе подключается, забирает данные и отключается (если за это время пользователь
     * не нажал «Подключить»). Ошибки пробрасываются вызывающему.
     */
    suspend fun backgroundSync(config: BandConfig) = sessionMutex.withLock {
        if (isOpen) {
            sync(background = true)
            return@withLock
        }
        try {
            open(config, background = true)
            sync(background = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordSync(LastSync(Instant.now().epochSecond, "ошибка: ${e.message}", background = true))
            throw e
        } finally {
            if (!userSession) close()
        }
    }

    private suspend fun sync(background: Boolean) {
        _state.update { it.copy(syncing = true, syncStatus = "Синхронизация…") }
        val zone = ZoneId.systemDefault()
        val since = withContext(Dispatchers.IO) { store.lastTimestamp() }
            ?.let { Instant.ofEpochSecond(it + 60).atZone(zone) }
            ?: ZonedDateTime.now(zone).minusDays(7)
        val minutes = band.minutesSince(since) { received ->
            _state.update { it.copy(syncStatus = "Синхронизация: получено $received мин") }
        }
        withContext(Dispatchers.IO) { store.insert(minutes) }
        val summary = if (minutes.isEmpty()) "новых данных нет" else "загружено ${minutes.size} мин"
        Log.i(TAG, "синхронизация (${if (background) "фон" else "экран"}): $summary")
        _state.update { it.copy(syncing = false, syncStatus = null, dataVersion = it.dataVersion + 1) }
        recordSync(LastSync(Instant.now().epochSecond, summary, background))
    }

    private fun recordSync(sync: LastSync) {
        BandSettings.setLastSync(context, sync)
        _state.update { it.copy(lastSync = sync) }
    }

    /** Выбор частоты фонового пульса: сохраняется сразу, в браслет пишется при наличии соединения. */
    fun setHeartRateInterval(minutes: Int) {
        BandSettings.setHeartRateInterval(context, minutes)
        _state.update { it.copy(hrInterval = minutes, hrIntervalPending = true) }
        if (isOpen) scope.launch { applyHeartRateInterval() }
    }

    private suspend fun applyHeartRateInterval() {
        val minutes = _state.value.hrInterval ?: return
        try {
            band.setHeartRateInterval(minutes)
            Log.i(TAG, "частота пульса записана в браслет: $minutes мин")
            _state.update { if (it.hrInterval == minutes) it.copy(hrIntervalPending = false) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "не удалось записать частоту пульса", e)
            _state.update { it.copy(error = "Не удалось задать частоту пульса: ${e.message}") }
        }
    }

    fun toggleLiveHeartRate() {
        if (!isOpen) return
        if (heartRateJob?.isActive == true) {
            heartRateJob?.cancel()
            _state.update { it.copy(liveHeartRateOn = false, liveHeartRate = null) }
            return
        }
        heartRateJob = scope.launch {
            try {
                _state.update { it.copy(liveHeartRateOn = true) }
                band.liveHeartRate().collect { heartRate -> _state.update { it.copy(liveHeartRate = heartRate?.bpm) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(liveHeartRateOn = false, error = "Пульс: ${e.message}") }
            }
        }
    }

    fun forget() {
        disconnect()
        BandConfig.save(context, null)
        BandSettings.clear(context)
        _state.value = BandState()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    companion object {
        private const val TAG = "KardioBLE"
        @Volatile private var instance: BandManager? = null

        fun get(context: Context): BandManager =
            instance ?: synchronized(this) {
                instance ?: BandManager(context.applicationContext).also { instance = it }
            }
    }
}
