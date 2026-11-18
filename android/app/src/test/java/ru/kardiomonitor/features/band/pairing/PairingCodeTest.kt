package ru.kardiomonitor.features.band.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kardiomonitor.features.band.fakes.FakeBandRepository

class PairingCodeTest {
    private val prefix = "kardio;E7:19:F3:6C:04:9E;00112233445566778899aabbccddeeff"
    private val enrollmentCode = "AbCdEfGhIjKlMnOpQrSt-_"

    @Test
    fun `QR v1 содержит только учётные данные браслета`() {
        val parsed = PairingCode.parse(prefix)

        assertEquals(FakeBandRepository.TEST_CREDENTIALS, parsed?.credentials)
        assertNull(parsed?.invitation)
    }

    @Test
    fun `QR v2 содержит адрес сервера и одноразовый код`() {
        val parsed = PairingCode.parse("$prefix;s=https://clinic.example/api;e=$enrollmentCode")

        assertEquals("https://clinic.example/api", parsed?.invitation?.address?.url)
        assertEquals(enrollmentCode, parsed?.invitation?.code?.value)
    }

    @Test
    fun `QR v3 сохраняет протокол вместе с существующим HTTPS приглашением`() {
        val v3 = "kardio3;xiaomi-band9-spp;E719F36C049E;ASNFZ4mrze8BI0VniavN7w"
        val parsed = PairingCode.parse("$v3;s=https://clinic.example/api;e=$enrollmentCode")
        assertEquals(ru.kardiomonitor.features.band.domain.BandProtocol.XiaomiBand9Spp, parsed?.credentials?.protocol)
        assertEquals("https://clinic.example/api", parsed?.invitation?.address?.url)
        assertEquals(enrollmentCode, parsed?.invitation?.code?.value)
        assertNull(PairingCode.parse("$v3;s=https://clinic.example"))
        assertNull(PairingCode.parse("$v3;s=https://clinic.example;e=$enrollmentCode;e=$enrollmentCode"))
        assertNull(PairingCode.parse("$v3;s=http://clinic.example;e=$enrollmentCode"))
    }

    @Test
    fun `QR v2 без адреса или кода не принимается`() {
        assertNull(PairingCode.parse("$prefix;s=https://clinic.example"))
        assertNull(PairingCode.parse("$prefix;e=$enrollmentCode"))
    }

    @Test
    fun `QR v2 принимает только HTTPS сервер`() {
        assertNull(PairingCode.parse("$prefix;s=http://clinic.example;e=$enrollmentCode"))
    }

    @Test
    fun `неверный код привязки не принимается`() {
        assertNull(PairingCode.parse("$prefix;s=https://clinic.example;e=short"))
    }

    @Test
    fun `неизвестные поля после обязательных не мешают QR v2`() {
        assertNotNull(PairingCode.parse("$prefix;s=https://clinic.example;e=$enrollmentCode;future=value"))
    }
}
