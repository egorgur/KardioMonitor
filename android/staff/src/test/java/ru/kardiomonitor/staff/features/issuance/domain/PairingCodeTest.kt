package ru.kardiomonitor.staff.features.issuance.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import ru.kardiomonitor.staff.shared.BandProtocol
import org.junit.Test

class PairingCodeTest {
    @Test
    fun `оба протокола записывают общий синтетический v3 fixture`() {
        listOf(BandProtocol.MiBand6Ble, BandProtocol.XiaomiBand9Spp).forEach { protocol ->
            PairingCode.of("E7:19:F3:6C:04:9E", "0123456789abcdef0123456789abcdef", protocol).useEncoded { encoded ->
                assertEquals("kardio3;${protocol.id};E719F36C049E;ASNFZ4mrze8BI0VniavN7w", encoded)
                assertFalse(Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}").containsMatchIn(encoded))
                assertFalse(Regex("(?i)[0-9a-f]{32}").containsMatchIn(encoded))
                assertFalse(encoded.contains('='))
            }
        }
    }

    @Test
    fun `старый доменный вызов по умолчанию пишет v3 Band6`() {
        PairingCode.of("E7:19:F3:6C:04:9E", "0123456789abcdef0123456789abcdef").useEncoded {
            assertEquals("kardio3;mi-band6-ble;E719F36C049E;ASNFZ4mrze8BI0VniavN7w", it)
        }
    }

    @Test
    fun `неизвестный протокол и неканонические значения отклоняются`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingCode.of("E7:19:F3:6C:04:9E", "0123456789abcdef0123456789abcdef", BandProtocol.Unknown)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingCode.of("e7:19:f3:6c:04:9e", "0123456789abcdef0123456789abcdef")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingCode.of("E7:19:F3:6C:04:9E", "0123456789ABCDEF0123456789ABCDEF")
        }
    }

    @Test
    fun `секрет не попадает в строковое представление`() {
        val code = PairingCode.of(
            "E7:19:F3:6C:04:9E",
            "00112233445566778899aabbccddeeff",
        )
        assertEquals("PairingCode(<hidden>)", code.toString())
    }
}
