package ru.kardiomonitor.staff.features.issuance.domain

import java.time.Instant
import ru.kardiomonitor.staff.shared.BandProtocol

data class Issuance(
    val id: Long,
    val address: String,
    val label: String?,
    val issuedAt: Instant,
    val returnedAt: Instant?,
    val protocol: BandProtocol = BandProtocol.MiBand6Ble,
) {
    val isOpen: Boolean get() = returnedAt == null
}

interface IssuanceLog {
    suspend fun openOrCurrent(
        address: String,
        label: String?,
        at: Instant,
        protocol: BandProtocol = BandProtocol.MiBand6Ble,
    ): Issuance

    suspend fun current(address: String): Issuance?

    suspend fun returned(address: String, at: Instant): Boolean

    suspend fun history(): List<Issuance>
}
