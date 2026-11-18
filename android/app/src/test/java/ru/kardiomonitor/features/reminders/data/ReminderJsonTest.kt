package ru.kardiomonitor.features.reminders.data

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.features.reminders.domain.DailyTargetReminderSettings
import ru.kardiomonitor.features.reminders.domain.IntervalReminderSettings
import ru.kardiomonitor.features.reminders.domain.MoveReminderSettings
import ru.kardiomonitor.features.reminders.domain.ReminderDayFacts
import ru.kardiomonitor.features.reminders.domain.ReminderInterval
import ru.kardiomonitor.features.reminders.domain.ReminderKind
import ru.kardiomonitor.features.reminders.domain.ReminderSettings
import ru.kardiomonitor.features.reminders.domain.ReminderWindow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class ReminderJsonTest {
    @Test
    fun `настройки напоминаний переживают JSON round trip`() {
        val expected = ReminderSettings(
            move = MoveReminderSettings(
                IntervalReminderSettings(
                    true,
                    ReminderInterval.HalfHour,
                    ReminderWindow(LocalTime.of(8, 0), LocalTime.of(19, 0)),
                    setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                ),
                stepsPerHour = 300,
            ),
            dailyTarget = DailyTargetReminderSettings(true, LocalTime.of(17, 0)),
            wearBand = IntervalReminderSettings(enabled = true, interval = ReminderInterval.TwoHours),
        )

        val restored = ReminderSettingsJson.decode(ReminderSettingsJson.encode(expected))

        assertEquals(expected, restored)
    }

    @Test
    fun `факты показов отложения и тишины переживают JSON round trip`() {
        val shownAt = Instant.parse("2026-11-01T08:50:00Z")
        val expected = ReminderDayFacts(
            day = LocalDate.parse("2026-11-01"),
            shown = mapOf(ReminderKind.Move to listOf(shownAt)),
            snoozedUntil = mapOf(ReminderKind.Move to shownAt.plusSeconds(1_800)),
            isMuted = true,
        )

        val restored = ReminderFactsJson.decode(ReminderFactsJson.encode(expected))

        assertEquals(expected, restored)
    }
}
