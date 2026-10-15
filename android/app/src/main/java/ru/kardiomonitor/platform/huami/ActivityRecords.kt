package ru.kardiomonitor.platform.huami

import ru.kardiomonitor.features.monitoring.domain.ActivityKind
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.Intensity
import ru.kardiomonitor.features.monitoring.domain.StepCount
import java.time.ZonedDateTime

/**
 * ACL: записи истории активности Mi Band 6 → [ActivityMinute]. Формат — docs/ble-protocol.md,
 * «История активности»: 8 байт на минуту `kind, intensity, steps, heart_rate, ?, sleep, deep_sleep, rem_sleep`.
 * Байты сна не расшифрованы и не используются.
 */
internal object ActivityRecords {
    private const val RECORD_SIZE = 8

    private const val KIND_NOT_WORN = 115
    private const val KIND_CHARGING = 118
    private const val KIND_SLEEP = 120

    fun parse(start: ZonedDateTime, raw: ByteArray): List<ActivityMinute> {
        val firstMinute = start.toInstant()
        return (0 until raw.size / RECORD_SIZE).map { i ->
            val o = i * RECORD_SIZE
            ActivityMinute(
                start = firstMinute.plusSeconds(i * 60L),
                steps = StepCount(raw[o + 2].toInt() and 0xFF),
                heartRate = HeartRate.fromMeasurement(raw[o + 3].toInt() and 0xFF),
                kind = activityKind(raw[o].toInt() and 0xFF),
                intensity = Intensity(raw[o + 1].toInt() and 0xFF),
            )
        }
    }

    private fun activityKind(code: Int): ActivityKind = when (code) {
        KIND_NOT_WORN -> ActivityKind.NotWorn
        KIND_CHARGING -> ActivityKind.Charging
        KIND_SLEEP -> ActivityKind.Sleep
        else -> ActivityKind.Other(code)
    }
}
