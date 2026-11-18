package ru.kardiomonitor.features.rehab.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RehabOverviewTest {
    private val start = LocalDate.parse("2026-10-01")
    private val plan = RehabPlan(
        ZoneId.of("Europe/Moscow"),
        listOf(ProgramRevision(start, 60, program())),
    )

    @Test
    fun `текущий день входит в прогресс но не в завершённые дни`() {
        val today = LocalDate.parse("2026-10-12")
        val minutes = (0 until 9).associate { offset -> start.plusDays(offset.toLong()) to metDay() }

        val overview = RehabOverview.of(plan, today, minutes)

        assertEquals(12, overview.programDay)
        assertEquals(11, overview.completedDays)
        assertEquals(9, overview.metDays)
        assertFalse(overview.isCompleted)
    }

    @Test
    fun `после последнего дня программа завершена`() {
        val overview = RehabOverview.of(plan, LocalDate.parse("2026-11-20"), emptyMap())

        assertTrue(overview.isCompleted)
        assertEquals(42, overview.completedDays)
    }

    private fun program(): RehabProgram = RehabProgram(
        start,
        42,
        listOf(
            RehabStage(
                1,
                42,
                DailyTargets(TrainingHeartRateZone(90, 110), 30, 10_000),
            ),
        ),
    )

    private fun metDay(): List<RehabActivityMinute> {
        val training = List(30) { minute(80, 100) }
        return training + minute(7_600, null)
    }

    private fun minute(steps: Int, heartRate: Int?) = RehabActivityMinute(steps, heartRate, isWorn = true)
}
