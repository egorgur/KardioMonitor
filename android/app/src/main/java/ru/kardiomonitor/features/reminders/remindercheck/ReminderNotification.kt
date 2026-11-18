package ru.kardiomonitor.features.reminders.remindercheck

import ru.kardiomonitor.features.reminders.domain.ReminderDecision

/** Граница системных уведомлений для проверяемого сценария. */
fun interface ReminderNotification {
    fun show(decision: ReminderDecision): Boolean
}
