package ru.kardiomonitor.platform.xiaomi

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.kardiomonitor.bandprotocol.xiaomi.XiaomiReadOnlyClient
import ru.kardiomonitor.features.band.domain.BandCredentials
import ru.kardiomonitor.features.band.domain.BandProtocol
import ru.kardiomonitor.features.band.domain.BatteryLevel
import ru.kardiomonitor.features.band.domain.ConnectionProblem
import ru.kardiomonitor.features.band.domain.ConnectionStage
import ru.kardiomonitor.features.band.domain.HeartRateMeasurementInterval
import ru.kardiomonitor.features.band.domain.OpenResult
import ru.kardiomonitor.features.band.domain.ReportedHeartRateSettings
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.StepCount
import ru.kardiomonitor.platform.band.BandDriver
import java.io.IOException
import java.time.ZonedDateTime

/** Только проверенные read-only операции обычного M2345B1 / 2.3.98. */
class XiaomiBand(private val context: Context) : BandDriver {
    private val client = XiaomiReadOnlyClient(context)
    @Volatile override var onConnectionLost: (() -> Unit)? = null

    override val monitoringUnavailableReason: String =
        "Band 9: полная история, суточные шаги и безопасная остановка live HR не подтверждены"

    init {
        client.onConnectionLost = { onConnectionLost?.invoke() }
    }

    override suspend fun open(credentials: BandCredentials, onStage: (ConnectionStage) -> Unit): OpenResult {
        close()
        if (credentials.protocol != BandProtocol.XiaomiBand9Spp) {
            return OpenResult.Refused(ConnectionProblem.LinkFailed("неверный протокол браслета"))
        }
        if (!hasPermissions()) return OpenResult.Refused(ConnectionProblem.NoPermission)
        return try {
            openReadOnly(credentials, onStage)
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (_: SecurityException) {
            close()
            OpenResult.Refused(ConnectionProblem.NoPermission)
        } catch (_: IOException) {
            close()
            OpenResult.Refused(ConnectionProblem.LinkFailed("SPP: проверьте ключ, модель, прошивку и доступ Mi Fitness"))
        }
    }

    private suspend fun openReadOnly(credentials: BandCredentials, onStage: (ConnectionStage) -> Unit): OpenResult {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return OpenResult.Refused(ConnectionProblem.NoBluetooth)
        if (!adapter.isEnabled) return OpenResult.Refused(ConnectionProblem.BluetoothOff)
        onStage(ConnectionStage.Connecting)
        val bytes = credentials.key.bytes()
        try {
            client.open(credentials.address.value, bytes)
        } finally {
            bytes.fill(0)
        }
        return OpenResult.Opened
    }

    override fun close() = client.close()

    override suspend fun readBattery(): BatteryLevel? {
        val percent = client.readBattery() ?: return null
        if (percent !in 0..100) throw IOException("SPP: неподдерживаемый ответ заряда")
        return BatteryLevel(percent)
    }

    override suspend fun readHeartRateSettings(): ReportedHeartRateSettings {
        val settings = client.readHeartRateSettings()
        return ReportedHeartRateSettings(settings.disabled, settings.intervalMinutes)
    }

    override suspend fun writeHeartRateMeasurementInterval(interval: HeartRateMeasurementInterval): Unit =
        throw IOException("Band 9: запись настроек не проверена и отключена")

    override suspend fun minutesSince(start: ZonedDateTime, onProgress: (Int) -> Unit): List<ActivityMinute> =
        throw IOException(monitoringUnavailableReason)

    override fun liveHeartRate(): Flow<HeartRate?> = flow { throw IOException(monitoringUnavailableReason) }

    override fun stepsToday(): Flow<StepCount> = flow { throw IOException(monitoringUnavailableReason) }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
