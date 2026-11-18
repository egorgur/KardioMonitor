package ru.kardiomonitor.features.band.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BandCredentialsTest {
    private val expected = BandCredentials(BandAddress("E7:19:F3:6C:04:9E"), AuthKey("00112233445566778899aabbccddeeff"))

    @Test
    fun `QR-код клиники содержит адрес и ключ браслета`() {
        assertEquals(expected, BandCredentials.parse("kardio;e7:19:f3:6c:04:9e;00112233445566778899aabbccddeeff"))
    }

    @Test
    fun `поля QR-кода после ключа не мешают распознать адрес и ключ`() {
        val withFutureFields = "kardio;E7:19:F3:6C:04:9E;00112233445566778899aabbccddeeff;s=https://kardio.example;e=K7Q-2M9"
        assertEquals(expected, BandCredentials.parse(withFutureFields))
    }

    @Test
    fun `ключ с префиксом 0x находится среди произвольного текста`() {
        assertEquals(expected, BandCredentials.parse("MAC: E7:19:F3:6C:04:9E\nKey: 0x00112233445566778899aabbccddeeff"))
    }

    @Test
    fun `без адреса или без ключа учётные данные не распознаются`() {
        assertNull(BandCredentials.parse("E7:19:F3:6C:04:9E"))
        assertNull(BandCredentials.parse("00112233445566778899aabbccddeeff"))
    }

    @Test
    fun `ключ авторизации не попадает в текстовое представление учётных данных`() {
        assertFalse(expected.toString().contains("00112233445566778899aabbccddeeff"))
    }

    @Test
    fun `ключ авторизации — 16 байт`() {
        assertEquals(16, expected.key.bytes().size)
        assertEquals(0x11.toByte(), expected.key.bytes()[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `адрес браслета в нижнем регистре не принимается без нормализации`() {
        BandAddress("e7:19:f3:6c:04:9e")
    }
}
