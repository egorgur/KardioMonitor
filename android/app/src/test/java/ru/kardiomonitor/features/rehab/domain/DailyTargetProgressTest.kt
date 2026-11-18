package ru.kardiomonitor.features.rehab.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyTargetProgressTest {
    private val targets = DailyTargets(TrainingHeartRateZone(90, 110), 30, 10_000)

    @Test
    fun `15 минут и 6200 шагов не выполняют норму`() {
        val progress = progress(day(training = 15, steps = 6_200))

        assertEquals(15, progress.trainingMinutes)
        assertEquals(6_200, progress.steps)
        assertFalse(progress.isMet)
    }

    @Test
    fun `тренировочные минуты не обязаны идти подряд`() {
        val training = List(36) { minute(80, 100) }
        val progress = progress(training + minute(10_500 - 36 * 80, null))

        assertEquals(36, progress.trainingMinutes)
        assertTrue(progress.isMet)
    }

    @Test
    fun `обе нормы обязательны`() {
        assertFalse(progress(day(training = 40, steps = 9_000)).isMet)
        assertFalse(progress(day(training = 20, steps = 12_000)).isMet)
    }

    @Test
    fun `пустой день не имеет данных и не выполняет норму`() {
        val progress = progress(emptyList())

        assertFalse(progress.hasData)
        assertFalse(progress.isMet)
    }

    @Test
    fun `в сумму входят шаги всех минут`() {
        assertEquals(60, progress(listOf(minute(30, null), minute(30, null))).steps)
    }

    @Test
    fun `минуты ходьбы без пульса считаются отдельно`() {
        val minutes = List(10) { minute(80, 100) } + List(3) { minute(80, null) }
        val progress = progress(minutes)

        assertEquals(13, progress.walkingMinutes)
        assertEquals(3, progress.walkingMinutesWithoutHeartRate)
    }

    @Test
    fun `текущий день остаётся предварительным`() {
        assertFalse(progress(day(training = 35, steps = 11_000), isFinal = false).isFinal)
    }

    private fun progress(minutes: List<RehabActivityMinute>, isFinal: Boolean = true): DailyTargetProgress =
        DailyTargetProgress.of(minutes, targets, walkingStepsThreshold = 60, isFinal = isFinal)

    private fun day(training: Int, steps: Int): List<RehabActivityMinute> {
        val minutes = List(training) { minute(80, 100) }
        val rest = steps - training * 80
        return if (rest > 0) minutes + minute(rest, null) else minutes
    }

    private fun minute(steps: Int, heartRate: Int?) = RehabActivityMinute(steps, heartRate, isWorn = true)
}
