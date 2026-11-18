package ru.kardiomonitor.features.band.statusrefresh

import kotlinx.coroutines.withTimeoutOrNull
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.domain.BandProtocol
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.band.domain.backgroundWorkIdentity
import java.io.IOException

/** Короткий read-only сеанс Band 9: авторизация, заряд и GET настроек при открытии. */
class BandStatusRefresh(
    private val connection: BandConnection,
    private val repository: BandRepository,
    private val isBackgroundAllowed: () -> Boolean,
) {
    fun canRefresh(identity: String): Boolean {
        val band = repository.band.value ?: return false
        return band.credentials.protocol == BandProtocol.XiaomiBand9Spp &&
            band.backgroundWorkIdentity == identity && isBackgroundAllowed()
    }

    suspend fun refresh(identity: String): Boolean {
        if (!canRefresh(identity)) return false
        val expected = repository.band.value
        return try {
            withTimeoutOrNull(60_000) {
                connection.withSessionIf(
                    isBackground = true,
                    isAllowed = { canRefresh(identity) && repository.band.value?.credentials == expected?.credentials },
                ) { canRefresh(identity) }
            } ?: false
        } catch (_: IOException) {
            false
        }
    }
}
