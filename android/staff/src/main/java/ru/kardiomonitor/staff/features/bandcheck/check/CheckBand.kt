package ru.kardiomonitor.staff.features.bandcheck.check

import ru.kardiomonitor.staff.features.bandcheck.domain.BandCheck
import ru.kardiomonitor.staff.features.bandcheck.domain.BandCheckLog
import ru.kardiomonitor.staff.features.bandcheck.domain.BandInspector
import ru.kardiomonitor.staff.features.bandcheck.domain.BatteryLevel
import ru.kardiomonitor.staff.features.bandcheck.domain.CheckOutcome
import ru.kardiomonitor.staff.features.bandcheck.domain.CheckProblem
import ru.kardiomonitor.staff.features.bandcheck.domain.InspectionResult
import ru.kardiomonitor.staff.features.bandcheck.domain.InspectionStage
import ru.kardiomonitor.staff.features.bandcheck.domain.TestedFirmware
import ru.kardiomonitor.staff.features.inventory.api.IssuableBands
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.shared.BandProtocol
import java.time.Clock

class CheckBand(
    private val bands: IssuableBands,
    private val inspector: BandInspector,
    private val log: BandCheckLog,
    private val clock: Clock,
) {
    suspend operator fun invoke(address: BandAddress, onStage: (InspectionStage) -> Unit): BandCheck {
        val band = try {
            bands.byAddress(address)
        } catch (_: ru.kardiomonitor.staff.features.inventory.domain.StoredKeyUnavailable) {
            val failed = BandCheck(address.value, clock.instant(), CheckOutcome.Failed(CheckProblem.KeyUnavailable),
                null, null, BandProtocol.Unknown)
            log.save(failed)
            return failed
        }
        val protocol = band?.protocol ?: BandProtocol.Unknown
        val result = when {
            band == null -> InspectionResult.Refused(CheckProblem.NotFound)
            protocol == BandProtocol.Unknown -> InspectionResult.Refused(CheckProblem.UnsupportedProtocol)
            !protocol.supports(band.model.value) -> InspectionResult.Refused(CheckProblem.UnsupportedModel)
            else -> band.key.useBytesSuspending { key ->
                inspector.inspect(address.value, key, HEART_RATE_INTERVAL, onStage, protocol)
            }
        }
        val check = result.toCheck(address, protocol)
        log.save(check)
        return check
    }

    private fun InspectionResult.toCheck(address: BandAddress, protocol: BandProtocol): BandCheck = when (this) {
        is InspectionResult.Refused -> BandCheck(
            address.value,
            clock.instant(),
            CheckOutcome.Failed(problem),
            null,
            null,
            protocol,
        )
        is InspectionResult.Inspected -> {
            val firmware = TestedFirmware(firmware)
            val battery = batteryPercent?.let(::BatteryLevel)
            val warnings = buildList {
                if (protocol == BandProtocol.XiaomiBand9Spp) {
                    add("Band 9: только чтение. История, live-пульс и шаги за сегодня заблокированы; мониторинг не подтверждён")
                    if (heartRateIntervalMinutes == null) add("Интервал пульса не получен")
                } else if (!firmware.isKnown) {
                    add("Прошивка ${firmware.value} не проверена")
                }
                if (battery == null) add("Заряд не получен")
                else if (!battery.isEnoughForIssue) add("Зарядите браслет: ${battery.percent} %")
            }
            BandCheck(
                address.value,
                clock.instant(),
                if (warnings.isEmpty()) CheckOutcome.Ready else CheckOutcome.Warning(warnings),
                firmware,
                battery,
                protocol,
                heartRateIntervalMinutes,
                heartRateDisabled,
            )
        }
    }

    private companion object {
        const val HEART_RATE_INTERVAL = 1
    }
}
