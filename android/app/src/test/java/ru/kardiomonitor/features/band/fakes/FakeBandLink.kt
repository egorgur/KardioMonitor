package ru.kardiomonitor.features.band.fakes

import ru.kardiomonitor.features.band.domain.BandCredentials
import ru.kardiomonitor.features.band.domain.BandLink
import ru.kardiomonitor.features.band.domain.BatteryLevel
import ru.kardiomonitor.features.band.domain.ConnectionProblem
import ru.kardiomonitor.features.band.domain.ConnectionStage
import ru.kardiomonitor.features.band.domain.HeartRateMeasurementInterval
import ru.kardiomonitor.features.band.domain.OpenResult
import java.io.IOException

/** Браслет без Bluetooth: открывается, если [refusal] не задан; помнит, что в него записали. */
class FakeBandLink(
    var refusal: ConnectionProblem? = null,
    var battery: BatteryLevel = BatteryLevel(78),
) : BandLink {
    override var onConnectionLost: (() -> Unit)? = null

    var isOpen: Boolean = false
        private set
    var openCount: Int = 0
        private set
    val writtenIntervals: MutableList<HeartRateMeasurementInterval> = mutableListOf()

    override suspend fun open(credentials: BandCredentials, onStage: (ConnectionStage) -> Unit): OpenResult {
        openCount++
        onStage(ConnectionStage.Searching)
        refusal?.let { return OpenResult.Refused(it) }
        onStage(ConnectionStage.Authenticating)
        isOpen = true
        return OpenResult.Opened
    }

    override fun close() {
        isOpen = false
    }

    /** Сбой связи при чтении заряда сразу после авторизации. */
    var batteryFailure: IOException? = null

    override suspend fun readBattery(): BatteryLevel {
        batteryFailure?.let { throw it }
        return battery
    }

    override suspend fun writeHeartRateMeasurementInterval(interval: HeartRateMeasurementInterval) {
        writtenIntervals += interval
    }

    /** Браслет ушёл из зоны связи. */
    fun loseConnection() {
        isOpen = false
        onConnectionLost?.invoke()
    }
}
