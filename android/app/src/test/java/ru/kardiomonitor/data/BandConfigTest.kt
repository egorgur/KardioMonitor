package ru.kardiomonitor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandConfigTest {
    private val expected = BandConfig("E7:19:F3:6C:04:9E", "00112233445566778899aabbccddeeff")

    @Test
    fun `QR-код клиники содержит адрес и ключ браслета`() {
        assertEquals(expected, BandConfig.parse("kardio;e7:19:f3:6c:04:9e;00112233445566778899aabbccddeeff"))
    }

    @Test
    fun `ключ с префиксом 0x находится среди произвольного текста`() {
        assertEquals(expected, BandConfig.parse("MAC: E7:19:F3:6C:04:9E\nKey: 0x00112233445566778899aabbccddeeff"))
    }

    @Test
    fun `без адреса или без ключа учётные данные не распознаются`() {
        assertNull(BandConfig.parse("E7:19:F3:6C:04:9E"))
        assertNull(BandConfig.parse("00112233445566778899aabbccddeeff"))
    }
}
