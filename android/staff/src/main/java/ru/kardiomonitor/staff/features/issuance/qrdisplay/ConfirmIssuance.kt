package ru.kardiomonitor.staff.features.issuance.qrdisplay

import ru.kardiomonitor.staff.features.issuance.domain.Issuance
import ru.kardiomonitor.staff.features.issuance.domain.IssuanceLog
import java.time.Clock

/** Открывает выдачу только после того, как защищённый QR действительно появился на экране. */
class ConfirmIssuance(
    private val issuances: IssuanceLog,
    private val clock: Clock,
    private val prepare: PrepareIssuance,
) {
    suspend operator fun invoke(displayed: PrepareIssuanceResult.Ready): Issuance? {
        val current = prepare(ru.kardiomonitor.staff.features.inventory.domain.BandAddress.of(displayed.address))
            as? PrepareIssuanceResult.Ready ?: return null
        val sameCode = current.code.useEncoded { fresh -> displayed.code.useEncoded { it == fresh } }
        if (!sameCode || current.protocol != displayed.protocol) return null
        return try {
            issuances.openOrCurrent(current.address, current.label, clock.instant(), current.protocol)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}