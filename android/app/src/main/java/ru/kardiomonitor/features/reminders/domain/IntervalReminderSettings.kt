package ru.kardiomonitor.features.reminders.domain

import java.time.DayOfWeek
import java.time.LocalTime

/** Расписание интервального вида в местном времени телефона. */
data class IntervalReminderSettings(
    val enabled: Boolean,
    val interval: ReminderInterval = ReminderInterval.OneHour,
    val window: ReminderWindow = ReminderWindow(LocalTime.of(9, 0), LocalTime.of(20, 0)),
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
) {
    init {
        require(days.isNotEmpty()) { "нужно выбрать хотя бы один день недели" }
    }
}
