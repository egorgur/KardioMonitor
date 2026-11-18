package ru.kardiomonitor.features.reminders.domain

import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate

/** Настройки пациента и факты показов, которые не передаются на сервер. */
interface ReminderRepository {
    val settings: StateFlow<ReminderSettings>

    fun save(settings: ReminderSettings)

    fun facts(day: LocalDate): ReminderDayFacts

    fun recordShown(day: LocalDate, kind: ReminderKind, at: Instant)

    fun snooze(day: LocalDate, kind: ReminderKind, until: Instant)

    fun mute(day: LocalDate)
}
