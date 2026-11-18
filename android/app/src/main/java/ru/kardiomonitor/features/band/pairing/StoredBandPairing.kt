package ru.kardiomonitor.features.band.pairing

import ru.kardiomonitor.features.band.api.BandPairing
import ru.kardiomonitor.features.band.domain.BandRepository
import java.time.Instant

/** Точка входа слайса для `app`: момент привязки сохранённого браслета. */
class StoredBandPairing(private val repository: BandRepository) : BandPairing {
    override fun pairedAt(): Instant? = repository.band.value?.pairedAt
}
