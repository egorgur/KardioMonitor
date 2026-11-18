package ru.kardiomonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.kardiomonitor.features.reminders.api.ReminderActionContract
import ru.kardiomonitor.features.reminders.domain.ReminderKind

/** Принимает только явные PendingIntent приложения и передаёт действия в ручной DI. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actions = (context.applicationContext as KardioApplication).container.reminderActions
        when (intent.action) {
            ReminderActionContract.SNOOZE -> reminderKind(intent)?.let(actions::snooze)
            ReminderActionContract.MUTE_TODAY -> actions.muteToday()
        }
    }

    private fun reminderKind(intent: Intent): ReminderKind? {
        val value = intent.getStringExtra(ReminderActionContract.KIND) ?: return null
        return ReminderKind.entries.firstOrNull { it.name == value }
    }
}
