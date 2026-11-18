package ru.kardiomonitor.features.rehab.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RehabPlanTest {
    private val start = LocalDate.parse("2026-10-01")
    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun `правка в день 10 сохраняет нормы дней 1–9`() {
        val initial = revision(start, threshold = 60, trainingMinutes = 30)
        val changed = revision(start.plusDays(9), threshold = 40, trainingMinutes = 40)
        val plan = RehabPlan(zone, listOf(initial)).updated(changed, zone)

        assertEquals(30, plan.programOn(start.plusDays(8)).targetsOn(start.plusDays(8))?.trainingMinutes)
        assertEquals(40, plan.programOn(start.plusDays(9)).targetsOn(start.plusDays(9))?.trainingMinutes)
        assertEquals(60, plan.revisionOn(start.plusDays(8)).walkingStepsThreshold)
        assertEquals(40, plan.revisionOn(start.plusDays(9)).walkingStepsThreshold)
    }

    @Test
    fun `вторая правка в тот же день заменяет первую`() {
        val first = revision(start, threshold = 60, trainingMinutes = 30)
        val dayFive = start.plusDays(4)
        val plan = RehabPlan(zone, listOf(first))
            .updated(revision(dayFive, threshold = 50, trainingMinutes = 40), zone)
            .updated(revision(dayFive, threshold = 45, trainingMinutes = 45), zone)

        assertEquals(listOf(start, dayFive), plan.revisions.map { it.effectiveFrom })
        assertEquals(45, plan.revisionOn(dayFive).walkingStepsThreshold)
    }

    @Test
    fun `день вне исторической программы оценивается текущей`() {
        val short = revision(start, threshold = 60, trainingMinutes = 30, duration = 10)
        val movedStart = start.minusDays(5)
        val current = ProgramRevision(
            start.plusDays(2),
            60,
            program(movedStart, duration = 30, trainingMinutes = 20),
        )
        val plan = RehabPlan(zone, listOf(short, current))

        assertEquals(20, plan.programOn(start.minusDays(2)).targetsOn(start.minusDays(2))?.trainingMinutes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `план без версий создать нельзя`() {
        RehabPlan(zone, emptyList())
    }

    private fun revision(
        effectiveFrom: LocalDate,
        threshold: Int,
        trainingMinutes: Int,
        duration: Int = 25,
    ): ProgramRevision = ProgramRevision(effectiveFrom, threshold, program(start, duration, trainingMinutes))

    private fun program(startDate: LocalDate, duration: Int, trainingMinutes: Int): RehabProgram = RehabProgram(
        startDate,
        duration,
        listOf(
            RehabStage(
                1,
                duration,
                DailyTargets(TrainingHeartRateZone(90, 110), trainingMinutes, 10_000),
            ),
        ),
    )
}
