package ru.kardiomonitor.features.rehab.progress

import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.rehab.domain.RehabActivityMinute

/** ACL между минутой мониторинга и минимальным входом расчётов реабилитации. */
internal fun ActivityMinute.toRehabActivityMinute(): RehabActivityMinute = RehabActivityMinute(
    steps = steps.value,
    heartRateBpm = heartRate?.bpm,
    isWorn = isWorn,
)
