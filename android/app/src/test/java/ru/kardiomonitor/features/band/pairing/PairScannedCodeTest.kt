package ru.kardiomonitor.features.band.pairing

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kardiomonitor.features.band.fakes.FakeBandRepository
import ru.kardiomonitor.features.band.domain.Band
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.clinic.domain.ClinicEnrollment
import ru.kardiomonitor.features.clinic.domain.EnrollmentResult
import ru.kardiomonitor.features.clinic.fakes.FakeClinicServer
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PairScannedCodeTest {
    private val now = Instant.parse("2026-11-01T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val v2 = requireNotNull(PairingCode.parse(
        "kardio;E7:19:F3:6C:04:9E;00112233445566778899aabbccddeeff;" +
            "s=https://clinic.example;e=AbCdEfGhIjKlMnOpQrSt-_",
    ))

    @Test
    fun `QR v2 сохраняет браслет после успешной привязки к пациенту`() = runBlocking {
        val repository = FakeBandRepository(initial = null)
        val enrollment = ClinicEnrollment { address, _ ->
            EnrollmentResult.Enrolled(FakeClinicServer.REGISTRATION.copy(address = address))
        }

        val result = PairScannedCode(repository, enrollment, clock)(v2)

        assertEquals(PairingResult.Paired, result)
        assertEquals(now, repository.band.value?.pairedAt)
    }

    @Test
    fun `использованный код не сохраняет браслет`() = runBlocking {
        val repository = FakeBandRepository(initial = null)
        val enrollment = ClinicEnrollment { _, _ -> EnrollmentResult.InvalidCode }

        val result = PairScannedCode(repository, enrollment, clock)(v2)

        assertEquals(PairingResult.InvalidEnrollmentCode, result)
        assertNull(repository.band.value)
    }

    @Test
    fun `сбой сервера не сохраняет браслет`() = runBlocking {
        val repository = FakeBandRepository(initial = null)
        val enrollment = ClinicEnrollment { _, _ -> EnrollmentResult.Failed("нет связи") }

        val result = PairScannedCode(repository, enrollment, clock)(v2)

        assertEquals(PairingResult.Failed("нет связи"), result)
        assertNull(repository.band.value)
    }

    @Test
    fun `ошибка защищённого хранилища показана вместо падения`() {
        val repository = FailingBandRepository()
        val enrollment = ClinicEnrollment { _, _ -> error("для ручного ввода сервер не нужен") }

        val result = PairScannedCode(repository, enrollment, clock).save(FakeBandRepository.TEST_CREDENTIALS)

        assertEquals(PairingResult.Failed("хранилище недоступно"), result)
    }
}

private class FailingBandRepository : BandRepository {
    override val band: StateFlow<Band?> = MutableStateFlow(null)

    override fun save(band: Band) {
        throw IOException("хранилище недоступно")
    }

    override fun forget() = Unit
}
