package ru.kardiomonitor.staff.features.issuance.qrdisplay

import ru.kardiomonitor.staff.features.bandcheck.api.LastCheck
import ru.kardiomonitor.staff.features.inventory.api.IssuableBands
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.issuance.domain.IssuanceLog
import ru.kardiomonitor.staff.features.issuance.domain.PairingCode
import java.time.Clock

sealed interface PrepareIssuanceResult {
    data class Ready(
        val code: PairingCode,
        val address: String,
        val label: String?,
        val alreadyOpen: Boolean,
        val protocol: ru.kardiomonitor.staff.shared.BandProtocol,
    ) : PrepareIssuanceResult
    data object BandMissing : PrepareIssuanceResult
    data object UnsupportedModel : PrepareIssuanceResult
    data object CheckRequired : PrepareIssuanceResult
    data object KeyUnavailable : PrepareIssuanceResult
}

class PrepareIssuance(
    private val bands: IssuableBands,
    private val checks: LastCheck,
    private val issuances: IssuanceLog,
    private val clock: Clock,
) {
    suspend operator fun invoke(address: BandAddress): PrepareIssuanceResult {
        val band = try {
            bands.byAddress(address)
        } catch (_: ru.kardiomonitor.staff.features.inventory.domain.StoredKeyUnavailable) {
            return PrepareIssuanceResult.KeyUnavailable
        } ?: return PrepareIssuanceResult.BandMissing
        val now = clock.instant()
        if (!band.protocol.supports(band.model.value)) return PrepareIssuanceResult.UnsupportedModel
        val check = checks.of(address.value)
        if (check?.isFreshAt(now) != true || check.protocol != band.protocol ||
            !check.checkedAt.isAfter(band.keyReceivedAt)
        ) {
            return PrepareIssuanceResult.CheckRequired
        }
        val currentIssuance = issuances.current(address.value)
        if (currentIssuance != null && currentIssuance.protocol != band.protocol) {
            return PrepareIssuanceResult.UnsupportedModel
        }
        val alreadyOpen = currentIssuance != null
        val code = band.key.useHex { key -> PairingCode.of(address.value, key, band.protocol) }
        return PrepareIssuanceResult.Ready(code, address.value, band.label?.value, alreadyOpen, band.protocol)
    }
}
