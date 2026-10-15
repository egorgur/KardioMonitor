package ru.kardiomonitor.ble

import ru.kardiomonitor.features.monitoring.domain.ActivityKind
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.Intensity
import ru.kardiomonitor.features.monitoring.domain.StepCount
import java.time.Instant

/** Перевод записи протокола в доменную минуту активности (будущий ACL `platform.huami`). */
fun ActivitySample.toActivityMinute(): ActivityMinute = ActivityMinute(
    start = Instant.ofEpochSecond(epochSecond),
    steps = StepCount(steps),
    heartRate = heartRate?.let(HeartRate::fromMeasurement),
    kind = activityKind(kind),
    intensity = Intensity(intensity),
)

/** Коды типа активности Mi Band 6 — docs/ble-protocol.md, «История активности». */
fun activityKind(code: Int): ActivityKind = when (code) {
    MiBand6.KIND_NOT_WORN -> ActivityKind.NotWorn
    MiBand6.KIND_CHARGING -> ActivityKind.Charging
    MiBand6.KIND_SLEEP -> ActivityKind.Sleep
    else -> ActivityKind.Other(code)
}
