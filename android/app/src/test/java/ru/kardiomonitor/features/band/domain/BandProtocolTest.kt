package ru.kardiomonitor.features.band.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandProtocolTest {
    @Test
    fun `старая привязка без протокола остаётся Band 6`() {
        assertEquals(BandProtocol.MiBand6Ble, BandProtocol.fromStored(null))
        assertTrue(BandProtocol.MiBand6Ble.canWriteMeasurementInterval)
    }

    @Test
    fun `неизвестный протокол не становится Band 6`() {
        assertNull(BandProtocol.fromStored("future-protocol"))
        assertNull(BandProtocol.fromStored(""))
    }

    @Test
    fun `Band 9 не разрешает непроверенные записи и мониторинг`() {
        assertFalse(BandProtocol.XiaomiBand9Spp.canWriteMeasurementInterval)
        assertFalse(BandProtocol.XiaomiBand9Spp.canMonitorActivity)
    }
}
