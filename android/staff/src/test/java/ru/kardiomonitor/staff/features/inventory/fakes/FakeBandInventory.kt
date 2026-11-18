package ru.kardiomonitor.staff.features.inventory.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.inventory.domain.BandInventory
import ru.kardiomonitor.staff.features.inventory.domain.BandLabel
import ru.kardiomonitor.staff.features.inventory.domain.ClinicBand
import ru.kardiomonitor.staff.features.inventory.domain.ClinicBandSummary

class FakeBandInventory : BandInventory {
    private val stored = linkedMapOf<BandAddress, ClinicBand>()
    private val current = MutableStateFlow<List<ClinicBandSummary>>(emptyList())
    override val bands: Flow<List<ClinicBandSummary>> = current

    override suspend fun byAddress(address: BandAddress): ClinicBand? = stored[address]

    override suspend fun summaryByAddress(address: BandAddress): ClinicBandSummary? = stored[address]?.summary()

    override suspend fun save(band: ClinicBand) {
        stored[band.address] = band
        publish()
    }

    override suspend fun remove(address: BandAddress) {
        stored.remove(address)
        publish()
    }

    override suspend fun hasLabel(label: BandLabel, except: BandAddress?): Boolean =
        stored.values.any { it.address != except && it.label?.comparisonKey == label.comparisonKey }

    override suspend fun updateLabel(address: BandAddress, label: BandLabel): Boolean {
        val band = stored[address] ?: return false
        stored[address] = band.copy(label = label)
        publish()
        return true
    }

    private fun publish() {
        current.value = stored.values.map { it.summary() }
    }

    private fun ClinicBand.summary() = ClinicBandSummary(
        address,
        label,
        model,
        firmware,
        keyOrigin,
        keyReceivedAt,
        isInCloud,
        protocol,
    )
}
