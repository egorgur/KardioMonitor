package ru.kardiomonitor.features.band.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class BandTest {
    private val credentials = BandCredentials(BandAddress("E7:19:F3:6C:04:9E"), AuthKey("00112233445566778899aabbccddeeff"))
    private val pairedAt = Instant.parse("2026-10-08T09:15:00Z")

    @Test
    fun `новая привязка браслета не переносит частоту измерения пульса`() {
        assertNull(Band.paired(credentials, pairedAt).heartRateMeasurementInterval)
    }

    @Test
    fun `выбранная частота измерения пульса сохраняется в браслете`() {
        val band = Band.paired(credentials, pairedAt).withHeartRateMeasurementInterval(HeartRateMeasurementInterval.Every(5))
        assertEquals(HeartRateMeasurementInterval.Every(5), band.heartRateMeasurementInterval)
        assertEquals(credentials, band.credentials)
    }

    @Test
    fun `смена частоты измерения пульса не меняет момент привязки`() {
        val band = Band.paired(credentials, pairedAt).withHeartRateMeasurementInterval(HeartRateMeasurementInterval.Off)
        assertEquals(pairedAt, band.pairedAt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `настройку Band 6 нельзя назначить Band 9 через агрегат`() {
        val band9 = Band.paired(credentials.copy(protocol = BandProtocol.XiaomiBand9Spp), pairedAt)
        band9.withHeartRateMeasurementInterval(HeartRateMeasurementInterval.Off)
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
