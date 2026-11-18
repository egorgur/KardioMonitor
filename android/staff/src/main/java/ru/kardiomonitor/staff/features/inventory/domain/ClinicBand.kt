package ru.kardiomonitor.staff.features.inventory.domain

import java.time.Instant
import ru.kardiomonitor.staff.shared.BandProtocol

@JvmInline
value class BandModel(val value: String) {
    val isSupported: Boolean get() = BandProtocol.MiBand6Ble.supports(value)
}

@JvmInline
value class Firmware(val value: String)

enum class KeyOrigin { MiFitnessCloud, ZeppLifeCloud, Manual }

data class ClinicBand(
    val address: BandAddress,
    val key: AuthKey,
    val label: BandLabel?,
    val model: BandModel,
    val firmware: Firmware?,
    val keyOrigin: KeyOrigin,
    val keyReceivedAt: Instant,
    val isInCloud: Boolean,
    val protocol: BandProtocol = BandProtocol.MiBand6Ble,
) {
    override fun toString(): String =
        "ClinicBand(address=$address, label=$label, model=$model, firmware=$firmware, " +
            "keyOrigin=$keyOrigin, keyReceivedAt=$keyReceivedAt, isInCloud=$isInCloud, key=<hidden>)"
}

/** Несекретное представление для списков и редактирования метки. */
data class ClinicBandSummary(
    val address: BandAddress,
    val label: BandLabel?,
    val model: BandModel,
    val firmware: Firmware?,
    val keyOrigin: KeyOrigin,
    val keyReceivedAt: Instant,
    val isInCloud: Boolean,
    val protocol: BandProtocol = BandProtocol.MiBand6Ble,
)
