package ru.kardiomonitor.features.band.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.kardiomonitor.features.band.domain.AuthKey
import ru.kardiomonitor.features.band.domain.Band
import ru.kardiomonitor.features.band.domain.BandAddress
import ru.kardiomonitor.features.band.domain.BandCredentials
import ru.kardiomonitor.features.band.domain.BandRepository

/** Хранилище браслета в памяти. По умолчанию браслет привязан тестовыми учётными данными. */
class FakeBandRepository(initial: Band? = Band.paired(TEST_CREDENTIALS)) : BandRepository {
    private val current = MutableStateFlow(initial)

    override val band: StateFlow<Band?> = current

    override fun save(band: Band) {
        current.value = band
    }

    override fun forget() {
        current.value = null
    }

    companion object {
        /** Тестовый ключ из docs (protocol-change), не настоящий. */
        val TEST_CREDENTIALS = BandCredentials(BandAddress("E7:19:F3:6C:04:9E"), AuthKey("00112233445566778899aabbccddeeff"))
    }
}
