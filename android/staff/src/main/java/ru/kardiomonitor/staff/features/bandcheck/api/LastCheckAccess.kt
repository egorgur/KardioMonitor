package ru.kardiomonitor.staff.features.bandcheck.api

import ru.kardiomonitor.staff.features.bandcheck.domain.BandCheck
import ru.kardiomonitor.staff.features.bandcheck.domain.BandCheckLog

class LastCheckAccess(private val log: BandCheckLog) : LastCheck {
    override suspend fun of(address: String): BandCheck? = log.latestOf(address)
}
