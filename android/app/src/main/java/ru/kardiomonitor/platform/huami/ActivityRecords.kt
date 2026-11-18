package ru.kardiomonitor.platform.huami

import ru.kardiomonitor.features.monitoring.domain.ActivityKind
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.Intensity
import ru.kardiomonitor.features.monitoring.domain.StepCount

/**
 * ACL: записи истории активности Mi Band 6 → [ActivityMinute]. Формат — docs/ble-protocol.md,
 * «История активности»: 8 байт на минуту `kind, intensity, steps, heart_rate, ?, sleep, deep_sleep, rem_sleep`.
 * Байты сна не расшифрованы и не используются.
 */
internal object ActivityRecords {
    private const val KIND_NOT_WORN = 115
    private const val KIND_CHARGING = 118
    private const val KIND_SLEEP = 120

    fun fromProtocol(record: ActivityRecord): ActivityMinute = ActivityMinute(
        start = record.recordedAt,
        steps = StepCount(record.steps),
        heartRate = HeartRate.fromMeasurement(record.heartRate),
        kind = activityKind(record.kind),
        intensity = Intensity(record.intensity),
    )

    private fun activityKind(code: Int): ActivityKind = when (code) {
        KIND_NOT_WORN -> ActivityKind.NotWorn
        KIND_CHARGING -> ActivityKind.Charging
        KIND_SLEEP -> ActivityKind.Sleep
        else -> ActivityKind.Other(code)
    }
}
