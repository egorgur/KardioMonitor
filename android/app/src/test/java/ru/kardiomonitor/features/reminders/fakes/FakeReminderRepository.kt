package ru.kardiomonitor.features.reminders.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.kardiomonitor.features.reminders.domain.ReminderDayFacts
import ru.kardiomonitor.features.reminders.domain.ReminderKind
import ru.kardiomonitor.features.reminders.domain.ReminderRepository
import ru.kardiomonitor.features.reminders.domain.ReminderSettings
import java.time.Instant
import java.time.LocalDate

class FakeReminderRepository(initial: ReminderSettings = ReminderSettings()) : ReminderRepository {
    private val current = MutableStateFlow(initial)
    private var currentFacts: ReminderDayFacts? = null

    override val settings: StateFlow<ReminderSettings> = current

    override fun save(settings: ReminderSettings) {
        current.value = settings
    }

    override fun facts(day: LocalDate): ReminderDayFacts = currentFacts?.takeIf { it.day == day } ?: ReminderDayFacts(day)

    override fun recordShown(day: LocalDate, kind: ReminderKind, at: Instant) {
        val old = facts(day)
        currentFacts = old.copy(
            shown = old.shown + (kind to (old.shown[kind].orEmpty() + at)),
            snoozedUntil = old.snoozedUntil - kind,
        )
    }

    override fun snooze(day: LocalDate, kind: ReminderKind, until: Instant) {
        val old = facts(day)
        currentFacts = old.copy(snoozedUntil = old.snoozedUntil + (kind to until))
    }

    override fun mute(day: LocalDate) {
        currentFacts = facts(day).copy(isMuted = true)
    }
}
