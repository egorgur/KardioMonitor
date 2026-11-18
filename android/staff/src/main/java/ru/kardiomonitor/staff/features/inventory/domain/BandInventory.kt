package ru.kardiomonitor.staff.features.inventory.domain

import kotlinx.coroutines.flow.Flow

interface BandInventory {
    val bands: Flow<List<ClinicBandSummary>>

    suspend fun byAddress(address: BandAddress): ClinicBand?

    suspend fun summaryByAddress(address: BandAddress): ClinicBandSummary?

    suspend fun save(band: ClinicBand)

    suspend fun remove(address: BandAddress)

    suspend fun hasLabel(label: BandLabel, except: BandAddress? = null): Boolean

    suspend fun updateLabel(address: BandAddress, label: BandLabel): Boolean
}
