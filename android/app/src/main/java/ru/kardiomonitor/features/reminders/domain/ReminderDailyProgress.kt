package ru.kardiomonitor.features.reminders.domain

/** Минимальный ACL результата нормы, нужный правилам напоминаний. */
data class ReminderDailyProgress(
    val trainingMinutes: Int,
    val targetTrainingMinutes: Int,
    val steps: Int,
    val targetSteps: Int,
    val isMet: Boolean,
) {
    val remainingTrainingMinutes: Int get() = (targetTrainingMinutes - trainingMinutes).coerceAtLeast(0)
    val remainingSteps: Int get() = (targetSteps - steps).coerceAtLeast(0)
}
