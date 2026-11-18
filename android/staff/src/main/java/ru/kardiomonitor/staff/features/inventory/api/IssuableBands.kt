package ru.kardiomonitor.staff.features.inventory.api

import ru.kardiomonitor.staff.features.inventory.domain.AuthKey
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.inventory.domain.BandLabel
import ru.kardiomonitor.staff.features.inventory.domain.BandModel
import java.time.Instant

/** Минимальный опубликованный порт к секрету для проверки и выдачи. */
interface IssuableBands {
    suspend fun byAddress(address: BandAddress): IssuableBand?
}

data class IssuableBand(
    val address: BandAddress,
    val label: BandLabel?,
    val model: BandModel,
    val keyReceivedAt: Instant,
    val key: AuthKey,
    val protocol: ru.kardiomonitor.staff.shared.BandProtocol =
        ru.kardiomonitor.staff.shared.BandProtocol.MiBand6Ble,
)
