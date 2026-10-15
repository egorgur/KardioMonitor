package ru.kardiomonitor.features.monitoring.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class DailySummaryTest {
    private val dayStart = Instant.parse("2026-10-09T00:00:00Z")

    private fun minute(
        index: Int,
        heartRate: Int? = null,
        steps: Int = 0,
        kind: ActivityKind = ActivityKind.Other(code = 80),
    ) = ActivityMinute(
        start = dayStart.plusSeconds(index * 60L),
        steps = StepCount(steps),
        heartRate = heartRate?.let(::HeartRate),
        kind = kind,
        intensity = Intensity(0),
    )

    @Test
    fun `шаги дня — сумма шагов всех минут`() {
        val summary = DailySummary.of(listOf(minute(0, steps = 5), minute(1, steps = 12), minute(2)))
        assertEquals(StepCount(17), summary.steps)
    }

    @Test
    fun `средний, минимальный и максимальный пульс считаются только по минутам с замером`() {
        val summary = DailySummary.of(listOf(minute(0, heartRate = 60), minute(1), minute(2, heartRate = 81)))
        assertEquals(HeartRate(70), summary.averageHeartRate)
        assertEquals(HeartRate(60), summary.minHeartRate)
        assertEquals(HeartRate(81), summary.maxHeartRate)
    }

    @Test
    fun `покрытие пульсом — доля минут с замером в процентах`() {
        val summary = DailySummary.of(listOf(minute(0, heartRate = 70), minute(1), minute(2)))
        assertEquals(33, summary.heartRateCoveragePercent)
        assertEquals(3, summary.recordedMinutes)
    }

    @Test
    fun `снятый и заряжающийся браслет считаются минутами без браслета`() {
        val minutes = listOf(
            minute(0, kind = ActivityKind.NotWorn),
            minute(1, kind = ActivityKind.Charging),
            minute(2, kind = ActivityKind.Sleep),
        )
        assertEquals(2, DailySummary.of(minutes).notWornMinutes)
    }

    @Test
    fun `день без замеров пульса не имеет среднего пульса`() {
        val summary = DailySummary.of(listOf(minute(0, steps = 3)))
        assertNull(summary.averageHeartRate)
        assertEquals(0, summary.heartRateCoveragePercent)
    }

    @Test
    fun `день без записей не содержит данных`() {
        val summary = DailySummary.of(emptyList())
        assertFalse(summary.hasData)
        assertEquals(StepCount(0), summary.steps)
    }
}
