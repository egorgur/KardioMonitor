package ru.kardiomonitor.staff.features.bandcheck.domain

sealed interface InspectionResult {
    data class Inspected(
        val firmware: String,
        val batteryPercent: Int?,
        val heartRateIntervalMinutes: Int? = null,
        val heartRateDisabled: Boolean? = null,
    ) : InspectionResult
    data class Refused(val problem: CheckProblem) : InspectionResult
}

enum class InspectionStage { Searching, Connecting, Authenticating, Reading, Configuring }

interface BandInspector {
    suspend fun inspect(
        address: String,
        key: ByteArray,
        heartRateIntervalMinutes: Int,
        onStage: (InspectionStage) -> Unit,
        protocol: ru.kardiomonitor.staff.shared.BandProtocol =
            ru.kardiomonitor.staff.shared.BandProtocol.MiBand6Ble,
    ): InspectionResult
}
