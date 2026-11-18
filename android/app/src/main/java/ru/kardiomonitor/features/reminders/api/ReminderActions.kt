package ru.kardiomonitor.features.reminders.api

import ru.kardiomonitor.features.reminders.domain.ReminderKind

/** Действия из системного уведомления: отложить вид или выключить все до следующего дня. */
interface ReminderActions {
    fun snooze(kind: ReminderKind)

    fun muteToday()
}
