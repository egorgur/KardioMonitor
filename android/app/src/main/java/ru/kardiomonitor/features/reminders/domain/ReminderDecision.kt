package ru.kardiomonitor.features.reminders.domain

/** Решение локального правила с данными для нейтрального текста уведомления. */
data class ReminderDecision(val kind: ReminderKind, val details: Details) {
    sealed interface Details {
        data class Move(val steps: Int, val thresholdSteps: Int) : Details
        data class DailyTarget(val remainingTrainingMinutes: Int, val remainingSteps: Int) : Details
        data class WearBand(val notWornMinutes: Int) : Details
    }
}
