package ru.kardiomonitor.staff.features.inventory.api

import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.inventory.domain.BandInventory

class InventoryAccess(private val inventory: BandInventory) : IssuableBands {
    override suspend fun byAddress(address: BandAddress): IssuableBand? =
        inventory.byAddress(address)?.let {
            IssuableBand(it.address, it.label, it.model, it.keyReceivedAt, it.key, it.protocol)
        }
}
