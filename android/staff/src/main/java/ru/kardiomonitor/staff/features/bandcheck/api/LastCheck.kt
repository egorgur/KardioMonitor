package ru.kardiomonitor.staff.features.bandcheck.api

import ru.kardiomonitor.staff.features.bandcheck.domain.BandCheck

interface LastCheck {
    suspend fun of(address: String): BandCheck?
}
