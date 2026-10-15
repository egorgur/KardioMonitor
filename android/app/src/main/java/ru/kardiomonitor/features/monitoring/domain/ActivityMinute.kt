package ru.kardiomonitor.features.monitoring.domain

import java.time.Instant

/**
 * Минута активности — запись браслета за одну минуту. Неизменяемая; ключ — [start],
 * время начала минуты.
 *
 * @property heartRate null — в эту минуту замера пульса не было.
 */
data class ActivityMinute(
    val start: Instant,
    val steps: StepCount,
    val heartRate: HeartRate?,
    val kind: ActivityKind,
    val intensity: Intensity,
) {
    val isWorn: Boolean get() = kind.isWorn
}
