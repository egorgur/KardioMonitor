package ru.kardiomonitor.features.monitoring.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateTest {

    @Test
    fun `пульс 0 означает, что замера не было`() {
        assertNull(HeartRate.fromMeasurement(0))
    }

    @Test
    fun `пульс 255 означает, что замера не было`() {
        assertNull(HeartRate.fromMeasurement(255))
    }

    @Test
    fun `пульс в допустимом диапазоне сохраняется как есть`() {
        assertEquals(HeartRate(11), HeartRate.fromMeasurement(11))
        assertEquals(HeartRate(249), HeartRate.fromMeasurement(249))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `пульс вне диапазона 11–249 создать нельзя`() {
        HeartRate(250)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `отрицательное число шагов создать нельзя`() {
        StepCount(-1)
    }
}
