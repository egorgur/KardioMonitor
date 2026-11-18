package ru.kardiomonitor.features.band.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadV3Test {
    private val payload = "kardio3;xiaomi-band9-spp;E719F36C049E;ASNFZ4mrze8BI0VniavN7w"
    private val key = AuthKey("0123456789abcdef0123456789abcdef")

    @Test
    fun `новый QR явно выбирает SPP и восстанавливает ключ`() {
        assertEquals(
            BandCredentials(BandAddress("E7:19:F3:6C:04:9E"), key, BandProtocol.XiaomiBand9Spp),
            BandCredentials.parse(payload),
        )
    }

    @Test
    fun `новый QR Band 6 тоже читается`() {
        assertEquals(BandProtocol.MiBand6Ble, BandCredentials.parse(payload.replace("xiaomi-band9-spp", "mi-band6-ble"))?.protocol)
    }

    @Test
    fun `новый QR не содержит распознаваемых старым клиентом адреса и ключа`() {
        assertFalse(Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}").containsMatchIn(payload))
        assertFalse(Regex("(?i)[0-9a-f]{32}").containsMatchIn(payload))
    }

    @Test
    fun `повреждённый новый QR не откатывается к свободному парсеру Band 6`() {
        val suffix = ";E7:19:F3:6C:04:9E;0123456789abcdef0123456789abcdef"
        assertNull(BandCredentials.parse(payload.replace("xiaomi-band9-spp", "unknown") + suffix))
        assertNull(BandCredentials.parse(payload.replace("kardio3", "kardio4") + suffix))
        assertNull(BandCredentials.parse(payload + suffix))
        assertNull(BandCredentials.parse(payload + ";p=mi-band6-ble"))
    }

    @Test
    fun `неканонический Base64 и неверный MAC отвергаются`() {
        assertNull(BandCredentials.parse(payload + "="))
        assertNull(BandCredentials.parse(payload.replace("N7w", "N7x")))
        assertNull(BandCredentials.parse(payload.replace("E719F36C049E", "E719F36C049")))
        assertNull(BandCredentials.parse(payload.replace("E719F36C049E", "e719f36c049e")))
    }

    @Test
    fun `серверное поле v3 не может подсунуть старому клиенту другой адрес и ключ`() {
        assertNull(BandCredentials.parse("$payload;s=https://clinic.example/E7:19:F3:6C:04:9E"))
        assertNull(BandCredentials.parse("$payload;s=https://clinic.example/0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun `маркер протокола в старом формате не позволяет угадать Band 6`() {
        assertNull(BandCredentials.parse("kardio;E7:19:F3:6C:04:9E;0123456789abcdef0123456789abcdef;p=xiaomi-band9-spp"))
    }
}
