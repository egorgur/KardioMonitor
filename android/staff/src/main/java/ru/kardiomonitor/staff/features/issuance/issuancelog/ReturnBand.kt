package ru.kardiomonitor.staff.features.issuance.issuancelog

import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.issuance.domain.IssuanceLog
import java.time.Clock

class ReturnBand(
    private val issuances: IssuanceLog,
    private val clock: Clock,
) {
    suspend operator fun invoke(address: BandAddress): Boolean = issuances.returned(address.value, clock.instant())
}
