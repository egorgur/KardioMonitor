package ru.kardiomonitor.staff.features.inventory.manualentry

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.shared.BandProtocol
import ru.kardiomonitor.staff.features.inventory.fakes.FakeBandInventory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AddBandManuallyTest {
    private val inventory = FakeBandInventory()
    private val clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC)
    private val add = AddBandManually(inventory, clock)

    @Test
    fun `мусор регистр и префикс 0x не мешают добавить браслет`() = runTest {
        val result = add("MAC: e7:19:f3:6c:04:9e Key: 0X00112233445566778899AABBCCDDEEFF")

        assertEquals(ManualEntryResult.Added(BandAddress.of("E7:19:F3:6C:04:9E")), result)
        assertNotNull(inventory.byAddress(BandAddress.of("E7:19:F3:6C:04:9E")))
    }

    @Test
    fun `строка без ключа не меняет реестр`() = runTest {
        val result = add("E7:19:F3:6C:04:9E")

        assertEquals(ManualEntryResult.KeyMissing, result)
        assertEquals(null, inventory.byAddress(BandAddress.of("E7:19:F3:6C:04:9E")))
    }

    @Test
    fun `Band9 сохраняет выбранный протокол и не превращается в Band6 при повторном вводе`() = runTest {
        val address = BandAddress.of("E7:19:F3:6C:04:9E")
        assertEquals(ManualEntryResult.Added(address), add(address, "0123456789abcdef0123456789abcdef", BandProtocol.XiaomiBand9Spp))
        assertEquals(BandProtocol.XiaomiBand9Spp, inventory.byAddress(address)?.protocol)
        assertEquals(BandProtocol.XiaomiBand9Spp, inventory.summaryByAddress(address)?.protocol)
        assertEquals("Xiaomi Smart Band 9", inventory.byAddress(address)?.model?.value)
        assertEquals(ManualEntryResult.ProtocolMismatch, add(address, "0123456789abcdef0123456789abcdef"))
        assertEquals(BandProtocol.XiaomiBand9Spp, inventory.byAddress(address)?.protocol)
    }

    @Test
    fun `неизвестный протокол не добавляет браслет`() = runTest {
        val address = BandAddress.of("E7:19:F3:6C:04:9E")
        assertEquals(ManualEntryResult.ProtocolMismatch, add(address, "0123456789abcdef0123456789abcdef", BandProtocol.Unknown))
        assertEquals(null, inventory.byAddress(address))
        assertEquals(BandProtocol.Unknown, BandProtocol.fromId("future-band"))
        assertEquals(BandProtocol.Unknown, BandProtocol.fromId("MI-BAND6-BLE"))
    }

    @Test
    fun `автоматически найденный адрес сохраняется с введённым ключом`() = runTest {
        val address = BandAddress.of("E7:19:F3:6C:04:9E")

        val result = add(address, "00112233445566778899aabbccddeeff")

        assertEquals(ManualEntryResult.Added(address), result)
        assertNotNull(inventory.byAddress(address))
    }
}
