package ru.kardiomonitor.staff.features.bandcheck.domain

import java.time.Duration
import java.time.Instant

@JvmInline
value class BatteryLevel(val percent: Int) {
    init {
        require(percent in 0..100) { "Заряд должен быть от 0 до 100 %" }
    }

    val isEnoughForIssue: Boolean get() = percent >= 30
}

@JvmInline
value class TestedFirmware(val value: String) {
    val isKnown: Boolean get() = value.removePrefix("V") == VERIFIED_VERSION.removePrefix("V")

    companion object {
        const val VERIFIED_VERSION = "V1.0.6.10"
    }
}

sealed interface CheckOutcome {
    data object Ready : CheckOutcome
    data class Warning(val reasons: List<String>) : CheckOutcome
    data class Failed(val problem: CheckProblem) : CheckOutcome
}

enum class CheckProblem {
    NotFound,
    AuthRejected,
    LinkFailed,
    NoBluetooth,
    BluetoothOff,
    NoPermission,
    UnsupportedProtocol,
    UnsupportedModel,
    KeyUnavailable,
}

data class BandCheck(
    val address: String,
    val checkedAt: Instant,
    val outcome: CheckOutcome,
    val firmware: TestedFirmware?,
    val battery: BatteryLevel?,
    val protocol: ru.kardiomonitor.staff.shared.BandProtocol =
        ru.kardiomonitor.staff.shared.BandProtocol.MiBand6Ble,
    val heartRateIntervalMinutes: Int? = null,
    val heartRateDisabled: Boolean? = null,
) {
    fun isFreshAt(now: Instant): Boolean =
        !now.isBefore(checkedAt) &&
            Duration.between(checkedAt, now) < Duration.ofHours(24) &&
            outcome !is CheckOutcome.Failed
}
