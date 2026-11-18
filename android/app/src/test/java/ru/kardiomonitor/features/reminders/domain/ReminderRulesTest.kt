package ru.kardiomonitor.features.reminders.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderRulesTest {
    private val now = ZonedDateTime.of(2026, 11, 2, 11, 0, 0, 0, ZoneId.of("Europe/Moscow"))
    private val day = LocalDate.parse("2026-11-02")

    @Test
    fun `120 шагов за полный час дают напоминание пройтись`() {
        val decision = decide(ReminderKind.Move, completeHour(steps = 2))

        val details = decision?.details as ReminderDecision.Details.Move
        assertEquals(120, details.steps)
        assertEquals(250, details.thresholdSteps)
    }

    @Test
    fun `900 шагов за час не дают напоминание`() {
        assertNull(decide(ReminderKind.Move, completeHour(steps = 15)))
    }

    @Test
    fun `без данных за каждую минуту интервала пройтись не напоминает`() {
        assertNull(decide(ReminderKind.Move, completeHour(steps = 2).dropLast(1)))
    }

    @Test
    fun `порог прогулки пропорционален получасовому интервалу`() {
        val settings = ReminderSettings(
            move = MoveReminderSettings(
                schedule = IntervalReminderSettings(true, ReminderInterval.HalfHour),
                stepsPerHour = 250,
            ),
        )
        val minutes = completeHour(steps = 2).takeLast(30)

        val decision = decide(ReminderKind.Move, minutes, settings = settings)

        val details = decision?.details as ReminderDecision.Details.Move
        assertEquals(60, details.steps)
        assertEquals(125, details.thresholdSteps)
    }

    @Test
    fun `во время сна и вне дневного окна напоминаний нет`() {
        val sleeping = completeHour(steps = 0).map { it.copy(state = ReminderActivityMinute.State.Sleep) }
        assertNull(decide(ReminderKind.Move, sleeping))
        assertNull(decide(ReminderKind.Move, completeHour(0), at = now.withHour(21)))
    }

    @Test
    fun `выполненная дневная норма выключает прогулку и норму дня`() {
        val progress = ReminderDailyProgress(30, 30, 10_000, 10_000, isMet = true)

        assertNull(decide(ReminderKind.Move, completeHour(0), progress = progress))
        assertNull(decide(ReminderKind.DailyTarget, completeHour(0), at = now.withHour(18), progress = progress))
    }

    @Test
    fun `норма дня сообщает сколько минут и шагов осталось`() {
        val progress = ReminderDailyProgress(18, 30, 7_000, 10_000, isMet = false)

        val decision = decide(ReminderKind.DailyTarget, completeHour(0), at = now.withHour(18), progress = progress)

        assertEquals(ReminderDecision.Details.DailyTarget(12, 3_000), decision?.details)
    }

    @Test
    fun `снятый час браслет напоминает надеть а зарядка не напоминает`() {
        val settings = ReminderSettings(wearBand = IntervalReminderSettings(enabled = true))
        val notWorn = completeHour(0).map { it.copy(state = ReminderActivityMinute.State.NotWorn) }
        val charging = notWorn.toMutableList().apply { this[30] = this[30].copy(state = ReminderActivityMinute.State.Charging) }

        assertTrue(decide(ReminderKind.WearBand, notWorn, settings = settings) != null)
        assertNull(decide(ReminderKind.WearBand, charging, settings = settings))
    }

    @Test
    fun `девятое напоминание за день не показывается`() {
        val shown = List(8) { now.minusMinutes(it.toLong()).toInstant() }
        val facts = ReminderDayFacts(day, shown = mapOf(ReminderKind.Move to shown))

        assertNull(decide(ReminderKind.Move, completeHour(0), facts = facts))
    }

    @Test
    fun `отложенное напоминание ждёт 30 минут и затем разрешено повторно`() {
        val until = now.plusMinutes(30).toInstant()
        val shown = mapOf(ReminderKind.Move to listOf(now.minusMinutes(10).toInstant()))
        val facts = ReminderDayFacts(day, shown, mapOf(ReminderKind.Move to until))

        assertNull(decide(ReminderKind.Move, completeHour(0), facts = facts))
        val laterMinutes = completeHour(0, end = now.plusMinutes(30).toInstant())
        assertTrue(decide(ReminderKind.Move, laterMinutes, at = now.plusMinutes(30), facts = facts) != null)
    }

    private fun decide(
        kind: ReminderKind,
        minutes: List<ReminderActivityMinute>,
        at: ZonedDateTime = now,
        settings: ReminderSettings = ReminderSettings(),
        facts: ReminderDayFacts = ReminderDayFacts(day),
        progress: ReminderDailyProgress? = null,
    ): ReminderDecision? = ReminderRules.decide(kind, at, settings, facts, minutes, progress)

    private fun completeHour(steps: Int, end: Instant = now.toInstant()): List<ReminderActivityMinute> =
        (60 downTo 1).map { ago ->
            ReminderActivityMinute(end.minusSeconds(ago * 60L), steps, ReminderActivityMinute.State.Worn)
        }
}
