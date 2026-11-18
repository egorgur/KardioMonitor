package ru.kardiomonitor.features.clinic.enrollment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kardiomonitor.features.clinic.domain.EnrollmentCode
import ru.kardiomonitor.features.clinic.domain.EnrollmentResult
import ru.kardiomonitor.features.clinic.domain.ServerAddress
import ru.kardiomonitor.features.clinic.fakes.FakeClinicServer
import ru.kardiomonitor.features.clinic.fakes.FakeServerRegistrationRepository

class StoredClinicEnrollmentTest {
    private val address = ServerAddress.parse("https://clinic.example")
    private val code = EnrollmentCode("AbCdEfGhIjKlMnOpQrSt-_")

    @Test
    fun `одноразовый код заменяется сохранённым токеном пациента`() = runBlocking {
        val server = FakeClinicServer()
        val repository = FakeServerRegistrationRepository()

        val result = StoredClinicEnrollment(server, repository).enroll(address, code)

        assertTrue(result is EnrollmentResult.Enrolled)
        assertEquals(code, server.enrolledCode)
        assertEquals(FakeClinicServer.REGISTRATION.copy(address = address), repository.registration.value)
    }

    @Test
    fun `истёкший или использованный код не меняет регистрацию`() = runBlocking {
        val server = FakeClinicServer().apply { enrollment = null }
        val repository = FakeServerRegistrationRepository()

        val result = StoredClinicEnrollment(server, repository).enroll(address, code)

        assertEquals(EnrollmentResult.InvalidCode, result)
        assertNull(repository.registration.value)
    }

    @Test
    fun `сбой сервера не меняет регистрацию`() = runBlocking {
        val server = FakeClinicServer().apply { failure = "нет связи" }
        val repository = FakeServerRegistrationRepository()

        val result = StoredClinicEnrollment(server, repository).enroll(address, code)

        assertEquals(EnrollmentResult.Failed("нет связи"), result)
        assertNull(repository.registration.value)
    }

    @Test
    fun `ошибка защищённого хранилища возвращается как ошибка привязки`() = runBlocking {
        val server = FakeClinicServer()
        val repository = FakeServerRegistrationRepository().apply { failure = "хранилище недоступно" }

        val result = StoredClinicEnrollment(server, repository).enroll(address, code)

        assertEquals(EnrollmentResult.Failed("хранилище недоступно"), result)
        assertNull(repository.registration.value)
    }
}
