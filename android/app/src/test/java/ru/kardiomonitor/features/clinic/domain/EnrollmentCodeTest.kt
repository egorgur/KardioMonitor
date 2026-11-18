package ru.kardiomonitor.features.clinic.domain

import org.junit.Assert.assertFalse
import org.junit.Test

class EnrollmentCodeTest {
    @Test
    fun `код привязки принимает 22 знака base64url`() {
        EnrollmentCode("AbCdEfGhIjKlMnOpQrSt-_")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `короткий код привязки не принимается`() {
        EnrollmentCode("too-short")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `код привязки с посторонними знаками не принимается`() {
        EnrollmentCode("AbCdEfGhIjKlMnOpQrSt+/")
    }

    @Test
    fun `код привязки не попадает в текстовое представление`() {
        val value = "AbCdEfGhIjKlMnOpQrSt-_"

        assertFalse(EnrollmentCode(value).toString().contains(value))
    }
}
