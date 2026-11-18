package ru.kardiomonitor.features.reminders.remindercheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kardiomonitor.features.reminders.domain.ReminderKind
import ru.kardiomonitor.features.reminders.fakes.FakeReminderRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ReminderActionsStoreTest {
    private val now = Instant.parse("2026-11-02T11:00:00Z")
    private val day = LocalDate.parse("2026-11-02")

    @Test
    fun `отложить переносит этот вид на 30 минут`() {
        val repository = FakeReminderRepository()
        val actions = ReminderActionsStore(repository, Clock.fixed(now, ZoneOffset.UTC))

        actions.snooze(ReminderKind.Move)

        assertEquals(now.plusSeconds(1_800), repository.facts(day).snoozedUntil[ReminderKind.Move])
    }

    @Test
    fun `не напоминать сегодня выключает все виды до следующего дня`() {
        val repository = FakeReminderRepository()
        val actions = ReminderActionsStore(repository, Clock.fixed(now, ZoneOffset.UTC))

        actions.muteToday()

        assertTrue(repository.facts(day).isMuted)
    }
}
