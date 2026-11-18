package ru.kardiomonitor.staff.features.bandcheck.domain

interface BandCheckLog {
    suspend fun save(check: BandCheck)

    suspend fun latestOf(address: String): BandCheck?
}
