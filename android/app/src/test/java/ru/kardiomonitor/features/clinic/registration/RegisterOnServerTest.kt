package ru.kardiomonitor.features.clinic.registration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kardiomonitor.features.clinic.fakes.FakeClinicServer
import ru.kardiomonitor.features.clinic.fakes.FakeServerRegistrationRepository
import java.time.ZoneId

class RegisterOnServerTest {
    private val server = FakeClinicServer()
    private val repository = FakeServerRegistrationRepository()
    private val novosibirsk = ZoneId.of("Asia/Novosibirsk")
    private val register = RegisterOnServer(server, repository, timeZone = { novosibirsk })

    @Test
    fun `после регистрации телефон помнит сервер и токен`() = runBlocking {
        val result = register("demo.ngrok-free.app/", "  Иван П. ")

        assertTrue(result is RegistrationResult.Registered)
        assertEquals("https://demo.ngrok-free.app", repository.registration.value?.address?.url)
        assertEquals("Иван П.", repository.registration.value?.patientName?.value)
    }

    @Test
    fun `сервер получает часовой пояс телефона — по нему считаются дни пациента`() = runBlocking {
        register("https://demo.ngrok-free.app", "Иван")

        assertEquals(novosibirsk, server.registeredTimeZone)
    }

    @Test
    fun `сервер недоступен — регистрация не сохраняется, причина показывается`() = runBlocking {
        server.failure = "Unable to resolve host"

        val result = register("https://demo.ngrok-free.app", "Иван")

        assertEquals(RegistrationResult.Failed("Unable to resolve host"), result)
        assertNull(repository.registration.value)
    }

    @Test
    fun `пустое имя не отправляется на сервер`() = runBlocking {
        val result = register("https://demo.ngrok-free.app", "   ")

        assertTrue(result is RegistrationResult.Failed)
        assertNull(server.registeredTimeZone)
    }
}
