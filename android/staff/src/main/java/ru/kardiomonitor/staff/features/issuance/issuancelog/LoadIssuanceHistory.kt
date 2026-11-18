package ru.kardiomonitor.staff.features.issuance.issuancelog

import ru.kardiomonitor.staff.features.issuance.domain.Issuance
import ru.kardiomonitor.staff.features.issuance.domain.IssuanceLog

class LoadIssuanceHistory(private val issuances: IssuanceLog) {
    suspend operator fun invoke(): List<Issuance> = issuances.history()
}
