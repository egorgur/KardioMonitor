package ru.kardiomonitor.features.clinic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ServerAddressTest {
    @Test
    fun `адрес ngrok из буфера обмена принимается без пробелов и слэша в конце`() {
        val address = ServerAddress.parse("  https://a1b2-c3.ngrok-free.app/ \n")

        assertEquals("https://a1b2-c3.ngrok-free.app", address.url)
    }

    @Test
    fun `адрес без схемы считается https`() {
        assertEquals("https://demo.ngrok-free.app", ServerAddress.parse("demo.ngrok-free.app").url)
    }

    @Test
    fun `адрес локального сервера с портом принимается`() {
        assertEquals("http://192.168.1.10:8000", ServerAddress.parse("http://192.168.1.10:8000").url)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `адрес с пробелом внутри не принимается`() {
        ServerAddress.parse("https://demo ngrok.app")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `адрес не по http не принимается`() {
        ServerAddress.parse("ftp://demo.ngrok-free.app")
    }

    @Test
    fun `токен загрузки не попадает в журнал через toString`() {
        val registration = ServerRegistration(
            ServerAddress.parse("https://demo.ngrok-free.app"),
            "patient-1",
            PatientName("Иван П."),
            UploadToken("secret-token-value"),
        )

        assertFalse(registration.toString().contains("secret-token-value"))
    }
}
