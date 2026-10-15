package ru.kardiomonitor.features.band.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandTest {
    private val credentials = BandCredentials(BandAddress("E7:19:F3:6C:04:9E"), AuthKey("00112233445566778899aabbccddeeff"))

    @Test
    fun `новая привязка браслета не переносит частоту измерения пульса`() {
        assertNull(Band.paired(credentials).heartRateMeasurementInterval)
    }

    @Test
    fun `выбранная частота измерения пульса сохраняется в браслете`() {
        val band = Band.paired(credentials).withHeartRateMeasurementInterval(HeartRateMeasurementInterval.Every(5))
        assertEquals(HeartRateMeasurementInterval.Every(5), band.heartRateMeasurementInterval)
        assertEquals(credentials, band.credentials)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `частота измерения пульса реже раза в 120 минут не допускается`() {
        HeartRateMeasurementInterval.Every(121)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `частота 0 минут — это выключенное измерение, а не интервал`() {
        HeartRateMeasurementInterval.Every(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `заряд больше 100 процентов не допускается`() {
        BatteryLevel(101)
    }
}
