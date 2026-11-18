package ru.kardiomonitor.staff.platform.band

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleBandDiscoveryTest {
    @Test
    fun `распознаются имена Mi Band 6 из рекламы`() {
        assertTrue(isMiBand6AdvertisementName("Mi Smart Band 6"))
        assertTrue(isMiBand6AdvertisementName("Mi Band 6"))
        assertTrue(isMiBand6AdvertisementName("Xiaomi Smart Band 6"))
        assertTrue(isMiBand6AdvertisementName("Mi Smart Band 6 NFC"))
    }

    @Test
    fun `другие BLE устройства не выбираются`() {
        assertFalse(isMiBand6AdvertisementName("Mi Smart Band 7"))
        assertFalse(isMiBand6AdvertisementName("Fake Mi Smart Band 6 Clone"))
        assertFalse(isMiBand6AdvertisementName("Mi Body Composition Scale 2"))
    }
}
