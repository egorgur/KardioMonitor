package ru.kardiomonitor.features.reminders.domain

import java.time.LocalTime

/** Один раз после заданного местного времени, пока норма дня не выполнена. */
data class DailyTargetReminderSettings(
    val enabled: Boolean = true,
    val time: LocalTime = LocalTime.of(18, 0),
)
