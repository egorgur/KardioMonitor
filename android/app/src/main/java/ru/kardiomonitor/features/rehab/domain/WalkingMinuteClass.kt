package ru.kardiomonitor.features.rehab.domain

/** Вид минуты ходьбы по положению пульса относительно индивидуальной зоны. */
enum class WalkingMinuteClass {
    Training,
    AboveZone,
    BelowZone,
    NoHeartRate,
}

/** null — браслет снят/заряжается либо шагов не больше индивидуального порога. */
fun walkingMinuteClass(
    minute: RehabActivityMinute,
    zone: TrainingHeartRateZone,
    walkingStepsThreshold: Int,
): WalkingMinuteClass? {
    if (!minute.isWorn || minute.steps <= walkingStepsThreshold) return null
    val heartRate = minute.heartRateBpm ?: return WalkingMinuteClass.NoHeartRate
    if (zone.contains(heartRate)) return WalkingMinuteClass.Training
    return if (heartRate > zone.upperBpm) WalkingMinuteClass.AboveZone else WalkingMinuteClass.BelowZone
}
