package ru.kardiomonitor.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.kardiomonitor.features.reminders.domain.ReminderRepository
import ru.kardiomonitor.features.reminders.remindercheck.ReminderWorker

/** Держит фоновую проверку в расписании, пока включён хотя бы один вид напоминаний. */
class ReminderSchedule(
    private val context: Context,
    private val repository: ReminderRepository,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            repository.settings
                .map { it.hasEnabledReminders }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) ReminderWorker.schedule(context) else ReminderWorker.cancel(context)
                }
        }
    }
}
