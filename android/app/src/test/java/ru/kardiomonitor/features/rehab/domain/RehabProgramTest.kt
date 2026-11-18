package ru.kardiomonitor.features.rehab.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RehabProgramTest {
    private val start = LocalDate.parse("2026-10-01")

    @Test
    fun `день 16 получает нормы второго этапа`() {
        assertEquals(40, program().targetsOn(start.plusDays(15))?.trainingMinutes)
    }

    @Test
    fun `номер дня считается от даты начала`() {
        assertEquals(1, program().dayNumber(start))
        assertEquals(25, program().dayNumber(LocalDate.parse("2026-10-25")))
    }

    @Test
    fun `дни вне программы не имеют номера и норм`() {
        assertNull(program().dayNumber(start.minusDays(1)))
        assertNull(program().targetsOn(LocalDate.parse("2026-10-26")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `пропуск между этапами не принимается`() {
        RehabProgram(start, 25, listOf(stage(1, 15), stage(18, 25)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `пересечение этапов не принимается`() {
        RehabProgram(start, 25, listOf(stage(1, 16), stage(15, 25)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `этапы должны доходить до последнего дня`() {
        RehabProgram(start, 25, listOf(stage(1, 20)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `этап не выходит за программу`() {
        RehabProgram(start, 25, listOf(stage(1, 19), stage(20, 30)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `длительность программы не больше 365 дней`() {
        RehabProgram(start, 366, listOf(stage(1, 366)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `нижняя граница зоны меньше верхней`() {
        TrainingHeartRateZone(120, 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `зона пульса не выходит за 40 и 220`() {
        TrainingHeartRateZone(39, 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `дневная норма минут не больше 1440`() {
        DailyTargets(TrainingHeartRateZone(90, 110), 1441, 10_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `дневная норма шагов не больше 100000`() {
        DailyTargets(TrainingHeartRateZone(90, 110), 30, 100_001)
    }

    private fun program(): RehabProgram = RehabProgram(
        start,
        25,
        listOf(stage(1, 15, trainingMinutes = 30), stage(16, 25, trainingMinutes = 40)),
    )

    private fun stage(first: Int, last: Int, trainingMinutes: Int = 30): RehabStage = RehabStage(
        first,
        last,
        DailyTargets(TrainingHeartRateZone(90, 110), trainingMinutes, 10_000),
    )
}
