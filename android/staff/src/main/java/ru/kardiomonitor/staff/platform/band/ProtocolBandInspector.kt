package ru.kardiomonitor.staff.platform.band

import ru.kardiomonitor.staff.features.bandcheck.domain.BandInspector
import ru.kardiomonitor.staff.features.bandcheck.domain.CheckProblem
import ru.kardiomonitor.staff.features.bandcheck.domain.InspectionResult
import ru.kardiomonitor.staff.features.bandcheck.domain.InspectionStage
import ru.kardiomonitor.staff.shared.BandProtocol

class ProtocolBandInspector(
    private val band6: BandInspector,
    private val band9: BandInspector,
) : BandInspector {
    override suspend fun inspect(
        address: String,
        key: ByteArray,
        heartRateIntervalMinutes: Int,
        onStage: (InspectionStage) -> Unit,
        protocol: BandProtocol,
    ): InspectionResult = when (protocol) {
        BandProtocol.MiBand6Ble -> band6.inspect(address, key, heartRateIntervalMinutes, onStage, protocol)
        BandProtocol.XiaomiBand9Spp -> band9.inspect(address, key, heartRateIntervalMinutes, onStage, protocol)
        BandProtocol.Unknown -> InspectionResult.Refused(CheckProblem.UnsupportedProtocol)
    }
}
