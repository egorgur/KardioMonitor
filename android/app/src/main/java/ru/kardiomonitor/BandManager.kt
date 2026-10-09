package ru.kardiomonitor

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.kardiomonitor.ble.AuthException
import ru.kardiomonitor.ble.GattClient
import ru.kardiomonitor.ble.MiBand6
import ru.kardiomonitor.data.BandConfig
import ru.kardiomonitor.data.BandSettings
import ru.kardiomonitor.data.LastSync
import ru.kardiomonitor.data.SampleStore
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

enum class Connection { DISCONNECTED, SEARCHING, CONNECTING, AUTHENTICATING, CONNECTED }

data class BandState(
    val connection: Connection = Connection.DISCONNECTED,
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
    private val gatt = GattClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionMutex = Mutex()

    @Volatile private var band: MiBand6? = null
    /** Пользователь нажал «Подключить» — соединение держим, пока он не отключит. */
    @Volatile private var userSession = false
    private var connectJob: Job? = null
    private var heartRateJob: Job? = null

    private val _state = MutableStateFlow(
        BandSettings.heartRateInterval(context).let { interval ->
            BandState(hrInterval = interval, hrIntervalPending = interval != null, lastSync = BandSettings.lastSync(context))
        }
    )
    val state: StateFlow<BandState> = _state

    init {
        gatt.onDisconnected = {
            heartRateJob?.cancel()
            band = null
            val wasUser = userSession
            userSession = false
            _state.update {
                it.copy(
                    connection = Connection.DISCONNECTED, background = false, liveHeartRateOn = false,
                    liveHeartRate = null, syncing = false,
                    error = if (wasUser) it.error ?: "Соединение с браслетом потеряно" else it.error,
                )
            }
        }
    }

    // ---------- Подключение ----------

    /** Подключение по кнопке. Если фоновая синхронизация уже подключена, соединение просто остаётся открытым. */
    fun connect(config: BandConfig) {
        if (connectJob?.isActive == true) return
        userSession = true
        _state.update { it.copy(error = null) }
        connectJob = scope.launch {
            try {
                sessionMutex.withLock {
                    val existing = band
                    if (existing != null) {
                        _state.update { it.copy(background = false) }
                    } else {
                        sync(open(config, background = false), background = false)
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

    private suspend fun open(config: BandConfig, background: Boolean): MiBand6 {
        _state.update { it.copy(connection = Connection.SEARCHING, background = background) }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: error("На телефоне нет Bluetooth")
        if (!adapter.isEnabled) error("Bluetooth выключен")
        val device = gatt.findDevice(adapter, config.mac)
            ?: error("Браслет не найден. Он рядом? Mi Fitness не держит соединение?")

        _state.update { it.copy(connection = Connection.CONNECTING) }
        gatt.connect(device)

        _state.update { it.copy(connection = Connection.AUTHENTICATING) }
        val b = MiBand6(gatt, scope)
        b.authenticate(config.key)
        Log.i(GattClient.TAG, "авторизация успешна (${if (background) "фон" else "экран"})")
        band = b

        val battery = b.readBattery()
        val steps = b.readSteps()
        withContext(Dispatchers.IO) { store.insertBattery(Instant.now().epochSecond, battery) }
        b.subscribeSteps { s -> _state.update { it.copy(stepsToday = s) } }
        _state.update { it.copy(connection = Connection.CONNECTED, battery = battery, stepsToday = steps) }
        applyHeartRateInterval(b)
        return b
    }

    private fun close() {
        heartRateJob?.cancel()
        gatt.disconnect()
        band = null
        _state.update {
            it.copy(connection = Connection.DISCONNECTED, background = false, liveHeartRateOn = false,
                liveHeartRate = null, syncing = false)
        }
    }

    private fun fail(message: String) {
        userSession = false
        close()
        _state.update { it.copy(error = message) }
    }

    // ---------- Синхронизация ----------

    fun syncNow() {
        scope.launch {
            try {
                sessionMutex.withLock { band?.let { sync(it, background = false) } }
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
        val existing = band
        if (existing != null) {
            sync(existing, background = true)
            return@withLock
        }
        try {
            sync(open(config, background = true), background = true)
        } catch (e: Exception) {
            recordSync(LastSync(Instant.now().epochSecond, "ошибка: ${e.message}", background = true))
            throw e
        } finally {
            if (!userSession) close()
        }
    }

    private suspend fun sync(b: MiBand6, background: Boolean) {
        _state.update { it.copy(syncing = true, syncStatus = "Синхронизация…") }
        val zone = ZoneId.systemDefault()
        val since = withContext(Dispatchers.IO) { store.lastTimestamp() }
            ?.let { Instant.ofEpochSecond(it + 60).atZone(zone) }
            ?: ZonedDateTime.now(zone).minusDays(7)
        val samples = b.fetchActivity(since) { progress ->
            _state.update { it.copy(syncStatus = "Синхронизация: $progress") }
        }
        withContext(Dispatchers.IO) { store.insert(samples) }
        val summary = if (samples.isEmpty()) "новых данных нет" else "загружено ${samples.size} мин"
        Log.i(GattClient.TAG, "синхронизация (${if (background) "фон" else "экран"}): $summary")
        _state.update { it.copy(syncing = false, syncStatus = null, dataVersion = it.dataVersion + 1) }
        recordSync(LastSync(Instant.now().epochSecond, summary, background))
    }

    private fun recordSync(sync: LastSync) {
        BandSettings.setLastSync(context, sync)
        _state.update { it.copy(lastSync = sync) }
    }

    // ---------- Настройки браслета ----------

    /** Выбор частоты фонового пульса: сохраняется сразу, в браслет пишется при наличии соединения. */
    fun setHeartRateInterval(minutes: Int) {
        BandSettings.setHeartRateInterval(context, minutes)
        _state.update { it.copy(hrInterval = minutes, hrIntervalPending = true) }
        val b = band ?: return
        scope.launch { applyHeartRateInterval(b) }
    }

    private suspend fun applyHeartRateInterval(b: MiBand6) {
        val minutes = _state.value.hrInterval ?: return
        try {
            b.setHeartRateInterval(minutes)
            Log.i(GattClient.TAG, "частота пульса записана в браслет: $minutes мин")
            _state.update { if (it.hrInterval == minutes) it.copy(hrIntervalPending = false) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(GattClient.TAG, "не удалось записать частоту пульса", e)
            _state.update { it.copy(error = "Не удалось задать частоту пульса: ${e.message}") }
        }
    }

    // ---------- Пульс в реальном времени ----------

    fun toggleLiveHeartRate() {
        val b = band ?: return
        if (heartRateJob?.isActive == true) {
            heartRateJob?.cancel()
            _state.update { it.copy(liveHeartRateOn = false, liveHeartRate = null) }
            scope.launch {
                try {
                    b.stopHeartRate()
                } catch (e: IOException) {
                    Log.w(GattClient.TAG, "не удалось остановить измерение пульса", e)
                }
            }
            return
        }
        heartRateJob = scope.launch {
            try {
                _state.update { it.copy(liveHeartRateOn = true) }
                b.startHeartRate { bpm -> _state.update { it.copy(liveHeartRate = bpm.takeIf { v -> v > 0 }) } }
                while (isActive) {
                    delay(12_000)
                    b.pingHeartRate()
                }
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
        @Volatile private var instance: BandManager? = null

        fun get(context: Context): BandManager =
            instance ?: synchronized(this) {
                instance ?: BandManager(context.applicationContext).also { instance = it }
            }
    }
}
