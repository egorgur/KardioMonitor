package ru.kardiomonitor.features.band.domain

import java.time.Instant

/** Журнал заряда: заряд браслета при каждом подключении — чтобы оценить реальный расход в сутки. */
interface BatteryLog {
    suspend fun record(level: BatteryLevel, at: Instant)
}
