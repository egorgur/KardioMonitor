package ru.kardiomonitor.platform.huami

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import ru.kardiomonitor.features.band.domain.ConnectionStage
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.StepCount
import ru.kardiomonitor.platform.ble.GattClient
import java.io.IOException
import java.time.ZonedDateTime

/**
 * Антикоррупционный слой браслета Mi Band 6: единственный владелец GATT-соединения
 * (ADR-0003) и единственное место, где байты протокола переводятся в доменные типы.
 * [scope] — scope приложения: в нём живут подтверждения канала 0x0017 и остановка измерения.
 */
class HuamiBand(private val context: Context, private val scope: CoroutineScope) {
    private val gatt = GattClient(context)
    @Volatile private var band: MiBand6? = null

    /** Вызывается при потере соединения (не при [close]). */
    @Volatile var onConnectionLost: (() -> Unit)? = null

    init {
        gatt.onDisconnected = {
            band = null
            onConnectionLost?.invoke()
        }
    }

    /**
     * Ищет браслет, подключается и проходит авторизацию; стадии сообщает в [onStage].
     * Браслет не найден или Bluetooth выключен — [IllegalStateException] с текстом для пользователя,
     * авторизация отклонена — [AuthException], сбой связи — [IOException].
     */
    suspend fun open(mac: String, key: ByteArray, onStage: (ConnectionStage) -> Unit) {
        onStage(ConnectionStage.Searching)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: error("На телефоне нет Bluetooth")
        if (!adapter.isEnabled) error("Bluetooth выключен")
        val device = gatt.findDevice(adapter, mac)
            ?: error("Браслет не найден. Он рядом? Mi Fitness не держит соединение?")

        onStage(ConnectionStage.Connecting)
        gatt.connect(device)

        onStage(ConnectionStage.Authenticating)
        val opened = MiBand6(gatt, scope)
        opened.authenticate(key)
        band = opened
    }

    fun close() {
        band = null
        gatt.disconnect()
    }

    suspend fun readBattery(): Int = requireOpen().readBattery()

    /** Частота фонового измерения пульса, минуты; 0 — выключено. */
    suspend fun setHeartRateInterval(minutes: Int) {
        requireOpen().setHeartRateInterval(minutes)
    }

    /**
     * Минуты активности с [since]. Подтверждение `03` не отправляется — история остаётся на
     * браслете (ADR-0002).
     */
    suspend fun minutesSince(since: ZonedDateTime, onProgress: (minutesReceived: Int) -> Unit): List<ActivityMinute> =
        requireOpen().fetchActivity(since, onProgress)

    /**
     * Пульс сейчас — непрерывное измерение, пока поток собирают. null — замер ещё идёт или
     * браслет не на руке. Браслет прекращает измерение без пинга, поэтому пинг — раз в 12 с.
     */
    fun liveHeartRate(): Flow<HeartRate?> = callbackFlow {
        val opened = requireOpen()
        opened.startHeartRate { bpm -> trySend(HeartRate.fromMeasurement(bpm)) }
        val pinging = launch {
            while (true) {
                delay(HEART_RATE_PING_INTERVAL_MS)
                opened.pingHeartRate()
            }
        }
        awaitClose {
            pinging.cancel()
            scope.launch { stopHeartRate(opened) }
        }
    }

    /** Шаги за сегодня: текущее значение, затем обновления от браслета. */
    fun stepsToday(): Flow<StepCount> = callbackFlow {
        val opened = requireOpen()
        send(StepCount(opened.readSteps()))
        opened.subscribeSteps { steps -> trySend(StepCount(steps)) }
        awaitClose()
    }

    private suspend fun stopHeartRate(opened: MiBand6) {
        try {
            opened.stopHeartRate()
        } catch (e: IOException) {
            Log.w(GattClient.TAG, "не удалось остановить измерение пульса", e)
        }
    }

    private fun requireOpen(): MiBand6 = band ?: throw IOException("нет соединения с браслетом")

    private companion object {
        /** docs/ble-protocol.md, «Пульс»: без пинга измерение прекращается через ~15 с. */
        const val HEART_RATE_PING_INTERVAL_MS = 12_000L
    }
}
