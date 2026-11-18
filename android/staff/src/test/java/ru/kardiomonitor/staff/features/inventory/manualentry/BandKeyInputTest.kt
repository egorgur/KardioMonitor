package ru.kardiomonitor.staff.features.inventory.manualentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kardiomonitor.staff.features.inventory.domain.AuthKey
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.shared.BandProtocol

class BandKeyInputTest {
    private val address = BandAddress.of("E7:19:F3:6C:04:9E")
    private val hex = "0123456789abcdef0123456789abcdef"
    private val encoded = "ASNFZ4mrze8BI0VniavN7w"

    @Test
    fun `старый QR импортируется только в выбранный Band6 с тем же адресом`() {
        val qr = "kardio;${address.value};$hex"
        assertEquals(AuthKey.of(hex), BandKeyInput.parse(qr, address, BandProtocol.MiBand6Ble))
        assertNull(BandKeyInput.parse(qr, address, BandProtocol.XiaomiBand9Spp))
        assertNull(BandKeyInput.parse(qr, BandAddress.of("AA:BB:CC:DD:EE:01"), BandProtocol.MiBand6Ble))
    }

    @Test
    fun `v3 обоих протоколов требует точную идентичность`() {
        listOf(BandProtocol.MiBand6Ble, BandProtocol.XiaomiBand9Spp).forEach { protocol ->
            val qr = "kardio3;${protocol.id};E719F36C049E;$encoded"
            assertEquals(AuthKey.of(hex), BandKeyInput.parse(qr, address, protocol))
            assertNull(BandKeyInput.parse(qr, BandAddress.of("AA:BB:CC:DD:EE:01"), protocol))
            assertNull(BandKeyInput.parse(qr, address, BandProtocol.Unknown))
            val other = if (protocol == BandProtocol.MiBand6Ble) BandProtocol.XiaomiBand9Spp else BandProtocol.MiBand6Ble
            assertNull(BandKeyInput.parse(qr, address, other))
        }
    }

    @Test
    fun `неизвестный или повреждённый QR не извлекает hex через fallback`() {
        listOf(
            "kardio3;future;E719F36C049E;$hex",
            "kardio4;${address.value};$hex",
            "kardio3;mi-band6-ble;E719F36C049E;${encoded.dropLast(1)}x",
            "kardio3;mi-band6-ble;E719F36C049E;$encoded=",
            "kardio;${address.value};$hex;e=invite",
            "prefix kardio;${address.value};$hex",
        ).forEach { assertNull(BandKeyInput.parse(it, address, BandProtocol.MiBand6Ble)) }
    }

    @Test
    fun `свободный ключ не принимает чужой адрес и фрагмент длинного hex`() {
        assertEquals(AuthKey.of(hex), BandKeyInput.parse("Key: 0X${hex.uppercase()}", address, BandProtocol.XiaomiBand9Spp))
        assertNull(BandKeyInput.parse("AA:BB:CC:DD:EE:01 $hex", address, BandProtocol.MiBand6Ble))
        assertNull(BandKeyInput.parse("f$hex", address, BandProtocol.MiBand6Ble))
        assertNull(BandKeyInput.parse("$hex $hex", address, BandProtocol.MiBand6Ble))
        assertNull(BandKeyInput.parse("${address.value} ${address.value} $hex", address, BandProtocol.MiBand6Ble))
    }
}
