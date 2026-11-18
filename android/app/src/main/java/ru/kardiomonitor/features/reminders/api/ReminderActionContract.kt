package ru.kardiomonitor.features.reminders.api

/** Внутренний контракт явных broadcast-действий системного уведомления. */
object ReminderActionContract {
    const val SNOOZE = "ru.kardiomonitor.action.SNOOZE_REMINDER"
    const val MUTE_TODAY = "ru.kardiomonitor.action.MUTE_REMINDERS_TODAY"
    const val KIND = "reminder_kind"
    const val RECEIVER = "ru.kardiomonitor.app.ReminderActionReceiver"
}
