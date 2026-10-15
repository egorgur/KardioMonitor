package ru.kardiomonitor.platform.huami

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import ru.kardiomonitor.features.band.domain.BandCredentials
import ru.kardiomonitor.features.band.domain.BandLink
import ru.kardiomonitor.features.band.domain.BatteryLevel
import ru.kardiomonitor.features.band.domain.ConnectionProblem
import ru.kardiomonitor.features.band.domain.ConnectionStage
import ru.kardiomonitor.features.band.domain.HeartRateMeasurementInterval
import ru.kardiomonitor.features.band.domain.OpenResult
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.ActivitySource
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.StepCount
import ru.kardiomonitor.platform.ble.GattClient
import java.io.IOException
import java.time.ZonedDateTime

/**
 * Антикоррупционный слой браслета Mi Band 6: единственный владелец GATT-соединения
 * (ADR-0003) и единственное место, где байты протокола и исключения GATT переводятся в
 * доменные типы контекстов «Браслет» ([BandLink]) и «Мониторинг» ([ActivitySource]).
 * [scope] — scope приложения: в нём живут подтверждения канала 0x0017 и остановка измерения.
 */
class HuamiBand(private val context: Context, private val scope: CoroutineScope) : BandLink, ActivitySource {
    private val gatt = GattClient(context)
    @Volatile private var band: MiBand6? = null

    @Volatile override var onConnectionLost: (() -> Unit)? = null

    init {
        gatt.onDisconnected = {
            band = null
            onConnectionLost?.invoke()
        }
    }

    override suspend fun open(credentials: BandCredentials, onStage: (ConnectionStage) -> Unit): OpenResult {
        onStage(ConnectionStage.Searching)
        if (!hasBluetoothPermissions()) return OpenResult.Refused(ConnectionProblem.NoPermission)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return OpenResult.Refused(ConnectionProblem.NoBluetooth)
        if (!adapter.isEnabled) return OpenResult.Refused(ConnectionProblem.BluetoothOff)
        return try {
            connect(adapter, credentials, onStage)
        } catch (e: AuthException) {
            close()
            OpenResult.Refused(ConnectionProblem.AuthRejected(e.message.orEmpty()))
        } catch (e: IOException) {
            close()
            OpenResult.Refused(ConnectionProblem.LinkFailed(e.message ?: e.toString()))
        } catch (e: SecurityException) {
            Log.w(GattClient.TAG, "Android отозвал разрешение на Bluetooth", e)
            close()
            OpenResult.Refused(ConnectionProblem.NoPermission)
        }
    }

    private suspend fun connect(
        adapter: BluetoothAdapter,
        credentials: BandCredentials,
        onStage: (ConnectionStage) -> Unit,
    ): OpenResult {
        val device = gatt.findDevice(adapter, credentials.address.value)
            ?: return OpenResult.Refused(ConnectionProblem.NotFound)

        onStage(ConnectionStage.Connecting)
        gatt.connect(device)

        onStage(ConnectionStage.Authenticating)
        val opened = MiBand6(gatt, scope)
        opened.authenticate(credentials.key.bytes())
        band = opened
        return OpenResult.Opened
    }

    override fun close() {
        band = null
        gatt.disconnect()
    }

    override suspend fun readBattery(): BatteryLevel {
        val percent = requireOpen().readBattery()
        if (percent !in 0..100) throw IOException("браслет сообщил заряд вне диапазона: $percent")
        return BatteryLevel(percent)
    }

    override suspend fun writeHeartRateMeasurementInterval(interval: HeartRateMeasurementInterval) {
        val minutes = when (interval) {
            HeartRateMeasurementInterval.Off -> 0
            is HeartRateMeasurementInterval.Every -> interval.minutes
        }
        requireOpen().setHeartRateInterval(minutes)
    }

    /** Подтверждение `03` не отправляется — история остаётся на браслете (ADR-0002). */
    override suspend fun minutesSince(
        start: ZonedDateTime,
        onProgress: (minutesReceived: Int) -> Unit,
    ): List<ActivityMinute> = requireOpen().fetchActivity(start, onProgress)

    /** Браслет прекращает непрерывное измерение без пинга, поэтому пинг — раз в 12 с. */
    override fun liveHeartRate(): Flow<HeartRate?> = callbackFlow {
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

    override fun stepsToday(): Flow<StepCount> = callbackFlow {
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

    private fun hasBluetoothPermissions(): Boolean = BLUETOOTH_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        /** docs/ble-protocol.md, «Пульс»: без пинга измерение прекращается через ~15 с. */
        const val HEART_RATE_PING_INTERVAL_MS = 12_000L

        val BLUETOOTH_PERMISSIONS: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION) // BLE-сканирование до Android 12
        }
    }
}
