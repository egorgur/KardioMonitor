package ru.kardiomonitor.features.band.fakes

import ru.kardiomonitor.features.band.domain.BatteryLevel
import ru.kardiomonitor.features.band.domain.BatteryLog
import java.time.Instant

/** Журнал заряда в памяти. */
class FakeBatteryLog : BatteryLog {
    val records: MutableList<BatteryLevel> = mutableListOf()

    override suspend fun record(level: BatteryLevel, at: Instant) {
        records += level
    }
}
