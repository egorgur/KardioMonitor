package ru.kardiomonitor.features.reminders.domain

/** Условие «Пора пройтись»: расписание и нормированный порог шагов за час. */
data class MoveReminderSettings(
    val schedule: IntervalReminderSettings = IntervalReminderSettings(enabled = true),
    val stepsPerHour: Int = 250,
) {
    init {
        require(stepsPerHour in 1..10_000) { "порог шагов должен быть от 1 до 10000" }
    }
}
