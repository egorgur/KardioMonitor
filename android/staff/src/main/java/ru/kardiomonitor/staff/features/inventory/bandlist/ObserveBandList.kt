package ru.kardiomonitor.staff.features.inventory.bandlist

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.kardiomonitor.staff.features.inventory.api.BandListEntry
import ru.kardiomonitor.staff.features.inventory.domain.BandInventory

class ObserveBandList(private val inventory: BandInventory) {
    operator fun invoke(query: String): Flow<List<BandListEntry>> = inventory.bands.map { bands ->
        val needle = query.trim().lowercase()
        bands.filter { band ->
            needle.isEmpty() || band.label?.value?.lowercase()?.contains(needle) == true ||
                band.address.value.lowercase().contains(needle)
        }.map { band ->
            BandListEntry(
                address = band.address,
                label = band.label?.value,
                model = band.model.value,
                firmware = band.firmware?.value,
                supported = band.protocol.supports(band.model.value),
                protocol = band.protocol,
            )
        }
    }
}
