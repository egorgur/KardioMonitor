package ru.kardiomonitor.features.rehab.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalkingMinuteClassTest {
    private val zone = TrainingHeartRateZone(90, 110)

    @Test
    fun `пульс внутри зоны и на обеих границах даёт тренировочную минуту`() {
        assertEquals(WalkingMinuteClass.Training, classify(80, 100))
        assertEquals(WalkingMinuteClass.Training, classify(80, 90))
        assertEquals(WalkingMinuteClass.Training, classify(80, 110))
    }

    @Test
    fun `ровно порог шагов не считается ходьбой`() {
        assertEquals(WalkingMinuteClass.Training, classify(61, 100))
        assertNull(classify(60, 100))
    }

    @Test
    fun `ходьба выше и ниже зоны различается`() {
        assertEquals(WalkingMinuteClass.AboveZone, classify(80, 115))
        assertEquals(WalkingMinuteClass.BelowZone, classify(80, 85))
    }

    @Test
    fun `ходьба без замера пульса считается отдельно`() {
        assertEquals(WalkingMinuteClass.NoHeartRate, classify(80, null))
    }

    @Test
    fun `снятый и заряжающийся браслет не считаются ходьбой`() {
        assertNull(classify(80, null, isWorn = false))
        assertNull(classify(80, 100, isWorn = false))
    }

    @Test
    fun `порог ходьбы индивидуален`() {
        assertEquals(WalkingMinuteClass.Training, classify(50, 100, threshold = 40))
    }

    private fun classify(
        steps: Int,
        heartRate: Int?,
        threshold: Int = 60,
        isWorn: Boolean = true,
    ): WalkingMinuteClass? = walkingMinuteClass(minute(steps, heartRate, isWorn), zone, threshold)

    private fun minute(steps: Int, heartRate: Int?, isWorn: Boolean) =
        RehabActivityMinute(steps, heartRate, isWorn)
}
