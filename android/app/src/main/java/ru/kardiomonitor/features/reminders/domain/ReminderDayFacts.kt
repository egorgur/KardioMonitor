package ru.kardiomonitor.features.reminders.domain

import java.time.Instant
import java.time.LocalDate

/** Факты показа за местный день, отложенные виды и «не напоминать сегодня». */
data class ReminderDayFacts(
    val day: LocalDate,
    val shown: Map<ReminderKind, List<Instant>> = emptyMap(),
    val snoozedUntil: Map<ReminderKind, Instant> = emptyMap(),
    val isMuted: Boolean = false,
) {
    val shownCount: Int get() = shown.values.sumOf(List<Instant>::size)
}
