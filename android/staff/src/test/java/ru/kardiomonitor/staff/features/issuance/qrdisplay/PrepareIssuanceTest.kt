package ru.kardiomonitor.staff.features.issuance.qrdisplay

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kardiomonitor.staff.features.bandcheck.api.LastCheck
import ru.kardiomonitor.staff.features.bandcheck.domain.BandCheck
import ru.kardiomonitor.staff.features.bandcheck.domain.BatteryLevel
import ru.kardiomonitor.staff.features.bandcheck.domain.CheckOutcome
import ru.kardiomonitor.staff.features.bandcheck.domain.TestedFirmware
import ru.kardiomonitor.staff.features.inventory.api.IssuableBand
import ru.kardiomonitor.staff.features.inventory.api.IssuableBands
import ru.kardiomonitor.staff.features.inventory.domain.AuthKey
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.inventory.domain.BandModel
import ru.kardiomonitor.staff.features.issuance.domain.Issuance
import ru.kardiomonitor.staff.features.issuance.domain.IssuanceLog
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PrepareIssuanceTest {
    private val now = Instant.parse("2026-10-29T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val address = BandAddress.of("E7:19:F3:6C:04:9E")
    private val log = MemoryIssuanceLog()

    @Test
    fun `подготовка кода не открывает выдачу`() = runTest {
        val useCase = PrepareIssuance(
            FixedIssuableBands(bandReceivedAt("2026-10-29T08:00:00Z")),
            FixedLastCheck(checkAt("2026-10-29T09:00:00Z")),
            log,
            clock,
        )

        assertTrue(useCase(address) is PrepareIssuanceResult.Ready)
        assertEquals(0, log.openCalls)
    }

    @Test
    fun `проверка до замены ключа требует повторной проверки`() = runTest {
        val useCase = PrepareIssuance(
            FixedIssuableBands(bandReceivedAt("2026-10-29T09:00:00Z")),
            FixedLastCheck(checkAt("2026-10-29T08:59:59Z")),
            log,
            clock,
        )

        assertSame(PrepareIssuanceResult.CheckRequired, useCase(address))
    }

    @Test
    fun `проверка в ту же секунду что замена ключа требует повторной проверки`() = runTest {
        val useCase = PrepareIssuance(
            FixedIssuableBands(bandReceivedAt("2026-10-29T09:00:00Z")),
            FixedLastCheck(checkAt("2026-10-29T09:00:00Z")),
            log,
            clock,
        )

        assertSame(PrepareIssuanceResult.CheckRequired, useCase(address))
    }

    @Test
    fun `неподдерживаемая модель не выдаётся`() = runTest {
        val band = bandReceivedAt("2026-10-29T08:00:00Z").copy(model = BandModel("Generic Band 6"))
        val useCase = PrepareIssuance(
            FixedIssuableBands(band),
            FixedLastCheck(checkAt("2026-10-29T09:00:00Z")),
            log,
            clock,
        )

        assertSame(PrepareIssuanceResult.UnsupportedModel, useCase(address))
    }

    @Test
    fun `Band9 проверка и выдача сохраняют протокол в v3 и журнале`() = runTest {
        val protocol = ru.kardiomonitor.staff.shared.BandProtocol.XiaomiBand9Spp
        val band = bandReceivedAt("2026-10-29T08:00:00Z").copy(
            model = BandModel("Xiaomi Smart Band 9"), protocol = protocol,
        )
        val check = checkAt("2026-10-29T09:00:00Z").copy(
            protocol = protocol, firmware = TestedFirmware("2.3.98"),
            outcome = CheckOutcome.Warning(listOf("Только чтение")),
        )
        val useCase = PrepareIssuance(FixedIssuableBands(band), FixedLastCheck(check), log, clock)
        val ready = useCase(address) as PrepareIssuanceResult.Ready
        assertEquals(protocol, ready.protocol)
        ready.code.useEncoded { assertTrue(it.startsWith("kardio3;xiaomi-band9-spp;")) }
        val issued = requireNotNull(ConfirmIssuance(log, clock, useCase)(ready))
        assertEquals(protocol, issued.protocol)
    }

    @Test
    fun `проверка другого протокола не разрешает QR`() = runTest {
        val band = bandReceivedAt("2026-10-29T08:00:00Z").copy(
            model = BandModel("Xiaomi Smart Band 9"),
            protocol = ru.kardiomonitor.staff.shared.BandProtocol.XiaomiBand9Spp,
        )
        val useCase = PrepareIssuance(
            FixedIssuableBands(band), FixedLastCheck(checkAt("2026-10-29T09:00:00Z")), log, clock,
        )
        assertSame(PrepareIssuanceResult.CheckRequired, useCase(address))
    }

    @Test
    fun `подтверждение показа открывает выдачу один раз`() = runTest {
        val prepare = PrepareIssuance(FixedIssuableBands(bandReceivedAt("2026-10-29T08:00:00Z")),
            FixedLastCheck(checkAt("2026-10-29T09:00:00Z")), log, clock)
        val ready = prepare(address) as PrepareIssuanceResult.Ready
        val confirm = ConfirmIssuance(log, clock, prepare)

        val results = coroutineScope {
            List(8) { async { confirm(ready) } }.awaitAll()
        }

        assertEquals(1, results.distinct().size)
        assertEquals(1, log.openCalls)
    }

    @Test
    fun `неизвестный протокол не выдаёт QR даже при свежей проверке`() = runTest {
        val band = bandReceivedAt("2026-10-29T08:00:00Z").copy(
            protocol = ru.kardiomonitor.staff.shared.BandProtocol.Unknown)
        val prepare = PrepareIssuance(FixedIssuableBands(band), FixedLastCheck(checkAt("2026-10-29T09:00:00Z")), log, clock)
        assertSame(PrepareIssuanceResult.UnsupportedModel, prepare(address))
        assertEquals(0, log.openCalls)
    }

    @Test
    fun `ошибка хранилища не открывает QR и выдачу`() = runTest {
        val unavailable = object : IssuableBands {
            override suspend fun byAddress(address: BandAddress): IssuableBand? =
                throw ru.kardiomonitor.staff.features.inventory.domain.StoredKeyUnavailable()
        }
        val prepare = PrepareIssuance(unavailable, FixedLastCheck(checkAt("2026-10-29T09:00:00Z")), log, clock)
        assertSame(PrepareIssuanceResult.KeyUnavailable, prepare(address))
        assertEquals(0, log.openCalls)
    }

    @Test
    fun `изменение ключа или последней проверки после подготовки не открывает выдачу`() = runTest {
        var band = bandReceivedAt("2026-10-29T08:00:00Z")
        var check: BandCheck? = checkAt("2026-10-29T09:00:00Z")
        val bands = object : IssuableBands {
            override suspend fun byAddress(address: BandAddress): IssuableBand = band
        }
        val checks = object : LastCheck {
            override suspend fun of(address: String): BandCheck? = check
        }
        val prepare = PrepareIssuance(bands, checks, log, clock)
        val ready = prepare(address) as PrepareIssuanceResult.Ready
        val confirm = ConfirmIssuance(log, clock, prepare)
        band = band.copy(key = AuthKey.of("0123456789abcdef0123456789abcdef"))
        assertEquals(null, confirm(ready))
        band = bandReceivedAt("2026-10-29T08:00:00Z")
        check = null
        assertEquals(null, confirm(ready))
        assertEquals(0, log.openCalls)
    }

    @Test
    fun `открытая выдача с другим протоколом не разрешает повторный QR`() = runTest {
        log.openOrCurrent(address.value, null, now.minusSeconds(60), ru.kardiomonitor.staff.shared.BandProtocol.XiaomiBand9Spp)
        val prepare = PrepareIssuance(FixedIssuableBands(bandReceivedAt("2026-10-29T08:00:00Z")),
            FixedLastCheck(checkAt("2026-10-29T09:00:00Z")), log, clock)
        assertSame(PrepareIssuanceResult.UnsupportedModel, prepare(address))
    }

    private fun bandReceivedAt(value: String) = IssuableBand(
        address = address,
        label = null,
        model = BandModel("Mi Smart Band 6"),
        keyReceivedAt = Instant.parse(value),
        key = AuthKey.of("00112233445566778899aabbccddeeff"),
    )

    private fun checkAt(value: String) = BandCheck(
        address = address.value,
        checkedAt = Instant.parse(value),
        outcome = CheckOutcome.Ready,
        firmware = TestedFirmware("V1.0.6.10"),
        battery = BatteryLevel(80),
    )
}

private class FixedIssuableBands(private val band: IssuableBand) : IssuableBands {
    override suspend fun byAddress(address: BandAddress): IssuableBand? =
        band.takeIf { it.address == address }
}

private class FixedLastCheck(private val check: BandCheck?) : LastCheck {
    override suspend fun of(address: String): BandCheck? = check?.takeIf { it.address == address }
}

private class MemoryIssuanceLog : IssuanceLog {
    private val values = mutableListOf<Issuance>()
    private val mutex = Mutex()
    var openCalls = 0
        private set

    override suspend fun openOrCurrent(
        address: String,
        label: String?,
        at: Instant,
        protocol: ru.kardiomonitor.staff.shared.BandProtocol,
    ): Issuance = mutex.withLock {
        current(address)?.let { return@withLock it }
        openCalls += 1
        Issuance(openCalls.toLong(), address, label, at, null, protocol).also(values::add)
    }

    override suspend fun current(address: String): Issuance? =
        values.lastOrNull { it.address == address && it.isOpen }

    override suspend fun returned(address: String, at: Instant): Boolean = false

    override suspend fun history(): List<Issuance> = values.toList()
}
