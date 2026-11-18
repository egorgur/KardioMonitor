package ru.kardiomonitor.features.rehab.domain

/** Нормы одного дня программы: зона пульса, тренировочные минуты и все шаги. */
data class DailyTargets(
    val trainingZone: TrainingHeartRateZone,
    val trainingMinutes: Int,
    val steps: Int,
) {
    init {
        require(trainingMinutes in 1..MAX_MINUTES) { "тренировочных минут должно быть от 1 до $MAX_MINUTES" }
        require(steps in 0..MAX_STEPS) { "шагов должно быть от 0 до $MAX_STEPS" }
    }

    private companion object {
        const val MAX_MINUTES = 24 * 60
        const val MAX_STEPS = 100_000
    }
}
