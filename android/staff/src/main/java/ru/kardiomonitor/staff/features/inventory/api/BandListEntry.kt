package ru.kardiomonitor.staff.features.inventory.api

import ru.kardiomonitor.staff.features.inventory.domain.BandAddress

data class BandListEntry(
    val address: BandAddress,
    val label: String?,
    val model: String,
    val firmware: String?,
    val supported: Boolean,
    val protocol: ru.kardiomonitor.staff.shared.BandProtocol =
        ru.kardiomonitor.staff.shared.BandProtocol.MiBand6Ble,
)
