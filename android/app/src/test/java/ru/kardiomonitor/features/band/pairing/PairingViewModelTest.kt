package ru.kardiomonitor.features.band.pairing

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.features.band.fakes.FakeBandRepository
import ru.kardiomonitor.features.clinic.domain.ClinicEnrollment
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PairingViewModelTest {
    private val now = Instant.parse("2026-10-29T11:20:00Z")
    private val repository = FakeBandRepository(initial = null)
    private val unusedEnrollment = ClinicEnrollment { _, _ -> error("QR v1 не обращается к серверу") }
    private val viewModel = PairingViewModel(
        repository,
        unusedEnrollment,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `сканирование QR клиники запоминает момент привязки браслета`() {
        viewModel.onScanned("kardio;E7:19:F3:6C:04:9E;00112233445566778899aabbccddeeff")

        assertEquals(FakeBandRepository.TEST_CREDENTIALS, repository.band.value?.credentials)
        assertEquals(now, StoredBandPairing(repository).pairedAt())
    }

    @Test
    fun `ручной ввод тоже запоминает момент привязки`() {
        viewModel.onMacChange("E7:19:F3:6C:04:9E")
        viewModel.onKeyChange("00112233445566778899aabbccddeeff")
        viewModel.saveManual()

        assertEquals(now, repository.band.value?.pairedAt)
        assertEquals("", viewModel.state.value.mac)
        assertEquals("", viewModel.state.value.key)
        assertEquals(null, viewModel.state.value.manualCredentials)
    }
}
