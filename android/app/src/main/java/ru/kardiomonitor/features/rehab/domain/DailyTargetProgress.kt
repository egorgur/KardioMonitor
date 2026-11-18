package ru.kardiomonitor.features.rehab.domain

/** Фактические тренировочные минуты и шаги выбранного дня против индивидуальных норм. */
data class DailyTargetProgress(
    val targets: DailyTargets,
    val trainingMinutes: Int,
    val steps: Int,
    val walkingMinutes: Int,
    val walkingMinutesWithoutHeartRate: Int,
    val hasData: Boolean,
    val isFinal: Boolean,
) {
    val isMet: Boolean
        get() = hasData && trainingMinutes >= targets.trainingMinutes && steps >= targets.steps

    companion object {
        fun of(
            minutes: List<RehabActivityMinute>,
            targets: DailyTargets,
            walkingStepsThreshold: Int,
            isFinal: Boolean,
        ): DailyTargetProgress {
            val classes = minutes.map { walkingMinuteClass(it, targets.trainingZone, walkingStepsThreshold) }
            return DailyTargetProgress(
                targets = targets,
                trainingMinutes = classes.count { it == WalkingMinuteClass.Training },
                steps = minutes.sumOf { it.steps },
                walkingMinutes = classes.count { it != null },
                walkingMinutesWithoutHeartRate = classes.count { it == WalkingMinuteClass.NoHeartRate },
                hasData = minutes.isNotEmpty(),
                isFinal = isFinal,
            )
        }
    }
}
