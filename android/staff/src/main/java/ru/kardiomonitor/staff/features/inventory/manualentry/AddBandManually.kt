package ru.kardiomonitor.staff.features.inventory.manualentry

import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.inventory.domain.BandInventory
import ru.kardiomonitor.staff.features.inventory.domain.BandModel
import ru.kardiomonitor.staff.shared.BandProtocol
import ru.kardiomonitor.staff.features.inventory.domain.ClinicBand
import ru.kardiomonitor.staff.features.inventory.domain.KeyOrigin
import java.time.Clock

sealed interface ManualEntryResult {
    data class Added(val address: BandAddress) : ManualEntryResult
    data object AddressMissing : ManualEntryResult
    data object KeyMissing : ManualEntryResult
    data object ProtocolMismatch : ManualEntryResult
}

class AddBandManually(
    private val inventory: BandInventory,
    private val clock: Clock,
) {
    suspend operator fun invoke(text: String): ManualEntryResult {
        val address = BandAddress.findIn(text) ?: return ManualEntryResult.AddressMissing
        return invoke(address, text)
    }

    suspend operator fun invoke(
        address: BandAddress,
        keyText: String,
        protocol: BandProtocol = BandProtocol.MiBand6Ble,
    ): ManualEntryResult {
        if (protocol == BandProtocol.Unknown) return ManualEntryResult.ProtocolMismatch
        val key = BandKeyInput.parse(keyText, address, protocol) ?: return ManualEntryResult.KeyMissing
        val previous = inventory.summaryByAddress(address)
        if (previous != null && previous.protocol != protocol) return ManualEntryResult.ProtocolMismatch
        inventory.save(
            ClinicBand(
                address = address,
                key = key,
                label = previous?.label,
                model = previous?.model ?: BandModel(
                    if (protocol == BandProtocol.XiaomiBand9Spp) "Xiaomi Smart Band 9" else "Mi Smart Band 6",
                ),
                firmware = previous?.firmware,
                keyOrigin = KeyOrigin.Manual,
                keyReceivedAt = clock.instant(),
                isInCloud = false,
                protocol = protocol,
            ),
        )
        return ManualEntryResult.Added(address)
    }
}
