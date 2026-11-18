package ru.kardiomonitor.platform.huami

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.platform.ble.hexToBytes
import ru.kardiomonitor.platform.ble.toHex
import java.io.IOException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

class MiBand6Test {
    @Test fun `время начала выгрузки кодируется как в Python`() {
        val time = ZonedDateTime.of(2026, 10, 9, 14, 5, 0, 0, ZoneOffset.ofHours(3))
        assertEquals("ea070a090e05000c", MiBand6.encodeTime(time).toHex())
    }

    @Test fun `время начала из ответа учитывает часовой пояс`() {
        val response = "10010158050000ea070a090c03001c".hexToBytes()
        val start = MiBand6.decodeTime(response, 7)
        assertEquals(
            ZonedDateTime.of(2026, 10, 9, 12, 3, 0, 0, ZoneOffset.ofHours(7)).toInstant(),
            start.toInstant(),
        )
    }

    @Test fun `сырые записи истории разбираются поминутно и без потери байтов`() {
        val start = ZonedDateTime.of(2026, 10, 9, 12, 3, 0, 0, ZoneOffset.UTC).toInstant()
        val records = parseActivityRecords(start, "70060500010203045009004905060708".hexToBytes())
        assertEquals(2, records.size)
        assertEquals(ActivityRecord(start, 112, 6, 5, 0, 1, 2, 3, 4), records[0])
        assertEquals(ActivityRecord(start.plusSeconds(60), 80, 9, 0, 73, 5, 6, 7, 8), records[1])
    }

    @Test fun `неполная запись истории отбрасывается`() {
        val records = parseActivityRecords(java.time.Instant.EPOCH, "0102030405060708ff".hexToBytes())
        assertEquals(1, records.size)
    }

    @Test fun `частота измерения пульса содержит число минут`() {
        assertEquals("1401", MiBand6.heartRateIntervalCommand(1).toHex())
        assertEquals("140a", MiBand6.heartRateIntervalCommand(10).toHex())
        assertEquals("1400", MiBand6.heartRateIntervalCommand(0).toHex())
    }

    @Test fun `версия прошивки читается как строка без завершающего нуля`() {
        assertEquals("1.0.6.16", MiBand6.decodeFirmware("1.0.6.16\u0000\r\n".toByteArray()))
    }

    @Test fun `прошивка читается из software revision`() {
        assertEquals(
            UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb"),
            MiBand6.FIRMWARE_REVISION,
        )
    }

    @Test(expected = IOException::class)
    fun `пустая версия прошивки отклоняется`() {
        MiBand6.decodeFirmware(byteArrayOf(0, 0))
    }
}
