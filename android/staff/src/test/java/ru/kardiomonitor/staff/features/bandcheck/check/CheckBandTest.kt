package ru.kardiomonitor.staff.features.bandcheck.check

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kardiomonitor.staff.features.bandcheck.domain.BandCheck
import ru.kardiomonitor.staff.features.bandcheck.domain.BandCheckLog
import ru.kardiomonitor.staff.features.bandcheck.domain.BandInspector
import ru.kardiomonitor.staff.features.bandcheck.domain.CheckOutcome
import ru.kardiomonitor.staff.features.bandcheck.domain.InspectionResult
import ru.kardiomonitor.staff.features.bandcheck.domain.InspectionStage
import ru.kardiomonitor.staff.features.inventory.api.IssuableBand
import ru.kardiomonitor.staff.features.inventory.api.IssuableBands
import ru.kardiomonitor.staff.features.inventory.domain.AuthKey
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.inventory.domain.BandModel
import ru.kardiomonitor.staff.shared.BandProtocol
import ru.kardiomonitor.staff.features.bandcheck.domain.CheckProblem
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CheckBandTest {
    private val address = BandAddress.of("E7:19:F3:6C:04:9E")
    private val log = MemoryCheckLog()
    private val clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `проверенная прошивка и достаточный заряд готовы к выдаче`() = runTest {
        val check = CheckBand(FixedBands(address), FixedInspector("V1.0.6.10", 78), log, clock)
            .invoke(address) {}

        assertEquals(CheckOutcome.Ready, check.outcome)
        assertEquals(1, log.saved.size)
    }

    @Test
    fun `Band9 остаётся ограниченным чтением без подмены неизвестных значений нулями`() = runTest {
        val check = CheckBand(FixedBands(address, BandProtocol.XiaomiBand9Spp), object : BandInspector {
            override suspend fun inspect(
                address: String, key: ByteArray, heartRateIntervalMinutes: Int,
                onStage: (InspectionStage) -> Unit, protocol: BandProtocol,
            ): InspectionResult {
                assertEquals(BandProtocol.XiaomiBand9Spp, protocol)
                return InspectionResult.Inspected("2.3.98", null, null, null)
            }
        }, log, clock).invoke(address) {}
        assertEquals(BandProtocol.XiaomiBand9Spp, check.protocol)
        assertTrue(check.outcome is CheckOutcome.Warning)
        assertEquals(null, check.battery)
        assertEquals(null, check.heartRateIntervalMinutes)
        assertEquals(null, check.heartRateDisabled)
    }

    @Test
    fun `Smart ноль и disabled сохраняются независимо`() = runTest {
        val check = CheckBand(FixedBands(address, BandProtocol.XiaomiBand9Spp), object : BandInspector {
            override suspend fun inspect(
                address: String, key: ByteArray, heartRateIntervalMinutes: Int,
                onStage: (InspectionStage) -> Unit, protocol: BandProtocol,
            ) = InspectionResult.Inspected("2.3.98", 78, 0, false)
        }, log, clock).invoke(address) {}
        assertEquals(0, check.heartRateIntervalMinutes)
        assertEquals(false, check.heartRateDisabled)
        assertTrue(check.outcome is CheckOutcome.Warning)
    }

    @Test
    fun `неизвестный протокол не вызывает inspector`() = runTest {
        val check = CheckBand(FixedBands(address, BandProtocol.Unknown), object : BandInspector {
            override suspend fun inspect(
                address: String, key: ByteArray, heartRateIntervalMinutes: Int,
                onStage: (InspectionStage) -> Unit, protocol: BandProtocol,
            ): InspectionResult = error("Не должен вызываться")
        }, log, clock).invoke(address) {}
        assertEquals(CheckOutcome.Failed(CheckProblem.UnsupportedProtocol), check.outcome)
    }

    @Test
    fun `недоступный Keystore не вызывает браслет и сохраняет отказ вместо старого успеха`() = runTest {
        val bands = object : IssuableBands {
            override suspend fun byAddress(address: BandAddress): IssuableBand? =
                throw ru.kardiomonitor.staff.features.inventory.domain.StoredKeyUnavailable()
        }
        val inspector = object : BandInspector {
            override suspend fun inspect(
                address: String, key: ByteArray, heartRateIntervalMinutes: Int,
                onStage: (InspectionStage) -> Unit, protocol: BandProtocol,
            ): InspectionResult = error("Не должен вызываться")
        }
        val check = CheckBand(bands, inspector, log, clock)(address) {}
        assertEquals(CheckOutcome.Failed(CheckProblem.KeyUnavailable), check.outcome)
        assertEquals(listOf(check), log.saved)
    }

    @Test
    fun `низкий заряд и неизвестная прошивка дают предупреждения`() = runTest {
        val check = CheckBand(FixedBands(address), FixedInspector("V2.0.0", 18), log, clock)
            .invoke(address) {}

        assertTrue(check.outcome is CheckOutcome.Warning)
        assertEquals(2, (check.outcome as CheckOutcome.Warning).reasons.size)
    }
}

private class FixedBands(
    private val address: BandAddress,
    private val protocol: BandProtocol = BandProtocol.MiBand6Ble,
) : IssuableBands {
    override suspend fun byAddress(address: BandAddress): IssuableBand? =
        if (address == this.address) {
            IssuableBand(
                address,
                null,
                BandModel(if (protocol == BandProtocol.XiaomiBand9Spp) "Xiaomi Smart Band 9" else "Mi Smart Band 6"),
                Instant.parse("2026-10-29T09:00:00Z"),
                AuthKey.of("00112233445566778899aabbccddeeff"),
                protocol,
            )
        } else null
}

private class FixedInspector(private val firmware: String, private val battery: Int) : BandInspector {
    override suspend fun inspect(
        address: String,
        key: ByteArray,
        heartRateIntervalMinutes: Int,
        onStage: (InspectionStage) -> Unit,
        protocol: BandProtocol,
    ): InspectionResult {
        onStage(InspectionStage.Configuring)
        return InspectionResult.Inspected(firmware, battery)
    }
}

private class MemoryCheckLog : BandCheckLog {
    val saved = mutableListOf<BandCheck>()

    override suspend fun save(check: BandCheck) {
        saved += check
    }

    override suspend fun latestOf(address: String): BandCheck? = saved.lastOrNull { it.address == address }
}
