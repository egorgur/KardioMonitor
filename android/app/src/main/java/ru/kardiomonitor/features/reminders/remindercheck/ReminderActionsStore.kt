package ru.kardiomonitor.features.reminders.remindercheck

import ru.kardiomonitor.features.reminders.api.ReminderActions
import ru.kardiomonitor.features.reminders.domain.ReminderKind
import ru.kardiomonitor.features.reminders.domain.ReminderRepository
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/** Сохраняет действия пациента из уведомления по местному дню телефона. */
class ReminderActionsStore(
    private val repository: ReminderRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ReminderActions {
    override fun snooze(kind: ReminderKind) {
        val now = clock.instant()
        repository.snooze(LocalDate.now(clock), kind, now.plus(Duration.ofMinutes(SNOOZE_MINUTES)))
    }

    override fun muteToday() {
        repository.mute(LocalDate.now(clock))
    }

    private companion object {
        const val SNOOZE_MINUTES = 30L
    }
}
