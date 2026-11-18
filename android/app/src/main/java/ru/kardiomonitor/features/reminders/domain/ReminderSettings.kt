package ru.kardiomonitor.features.reminders.domain

/** Общие настройки локальных напоминаний пациента. */
data class ReminderSettings(
    val move: MoveReminderSettings = MoveReminderSettings(),
    val dailyTarget: DailyTargetReminderSettings = DailyTargetReminderSettings(),
    val wearBand: IntervalReminderSettings = IntervalReminderSettings(enabled = false),
) {
    val hasEnabledReminders: Boolean get() = move.schedule.enabled || dailyTarget.enabled || wearBand.enabled
}
