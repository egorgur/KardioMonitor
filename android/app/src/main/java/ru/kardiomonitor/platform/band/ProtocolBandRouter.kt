package ru.kardiomonitor.platform.band

import kotlinx.coroutines.flow.Flow
import ru.kardiomonitor.features.band.domain.BandCredentials
import ru.kardiomonitor.features.band.domain.BandProtocol
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.band.domain.BatteryLevel
import ru.kardiomonitor.features.band.domain.ConnectionStage
import ru.kardiomonitor.features.band.domain.HeartRateMeasurementInterval
import ru.kardiomonitor.features.band.domain.OpenResult
import ru.kardiomonitor.features.band.domain.ReportedHeartRateSettings
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.StepCount
import java.io.IOException
import java.time.ZonedDateTime

/** Выбор только по сохранённому протоколу: после ошибки никакого fallback или чужих команд. */
class ProtocolBandRouter(
    private val repository: BandRepository,
    private val band6: BandDriver,
    private val band9: BandDriver,
) : BandDriver {
    @Volatile private var active: BandDriver? = null
    @Volatile private var activeCredentials: BandCredentials? = null
    @Volatile override var onConnectionLost: (() -> Unit)? = null

    init {
        band6.onConnectionLost = { lost(band6) }
        band9.onConnectionLost = { lost(band9) }
    }

    override val monitoringUnavailableReason: String?
        get() = if (repository.band.value?.credentials?.protocol?.canMonitorActivity == false) {
            BAND9_MONITORING_UNAVAILABLE
        } else null

    override suspend fun open(credentials: BandCredentials, onStage: (ConnectionStage) -> Unit): OpenResult {
        close()
        val driver = when (credentials.protocol) {
            BandProtocol.MiBand6Ble -> band6
            BandProtocol.XiaomiBand9Spp -> band9
        }
        activeCredentials = credentials
        active = driver
        var opened = false
        try {
            val result = driver.open(credentials, onStage)
            if (active !== driver) throw IOException("подключение отменено")
            if (result == OpenResult.Opened) requireOpen()
            opened = result == OpenResult.Opened
            return result
        } finally {
            if (!opened) close()
        }
    }

    override fun close() {
        active = null
        activeCredentials = null
        band6.close()
        band9.close()
    }

    override suspend fun readBattery(): BatteryLevel? = requireOpen().readBattery()

    override suspend fun readHeartRateSettings(): ReportedHeartRateSettings? = requireOpen().readHeartRateSettings()

    override suspend fun writeHeartRateMeasurementInterval(interval: HeartRateMeasurementInterval) {
        if (repository.band.value?.credentials?.protocol?.canWriteMeasurementInterval != true) {
            throw IOException("запись настроек этого протокола не проверена")
        }
        requireOpen().writeHeartRateMeasurementInterval(interval)
    }

    override suspend fun minutesSince(start: ZonedDateTime, onProgress: (Int) -> Unit): List<ActivityMinute> {
        requireMonitoring()
        return requireOpen().minutesSince(start, onProgress)
    }

    override fun liveHeartRate(): Flow<HeartRate?> {
        requireMonitoring()
        return requireOpen().liveHeartRate()
    }

    override fun stepsToday(): Flow<StepCount> {
        requireMonitoring()
        return requireOpen().stepsToday()
    }

    private fun requireMonitoring() {
        monitoringUnavailableReason?.let { throw IOException(it) }
    }

    private fun requireOpen(): BandDriver {
        val driver = active ?: throw IOException("нет соединения с браслетом")
        if (activeCredentials != repository.band.value?.credentials) {
            close()
            throw IOException("привязка браслета изменилась — подключитесь заново")
        }
        return driver
    }

    private fun lost(driver: BandDriver) {
        if (active !== driver) return
        active = null
        driver.close()
        onConnectionLost?.invoke()
    }

    private companion object {
        const val BAND9_MONITORING_UNAVAILABLE =
            "Band 9: время и значения полной истории, суточные шаги и остановка live HR ещё не подтверждены"
    }
}
