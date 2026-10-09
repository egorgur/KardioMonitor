package ru.kardiomonitor.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class MiBand6Test {
    private val tz = ZoneOffset.ofHours(7)

    @Test
    fun `время начала выгрузки кодируется как в Python`() {
        // fetch_history.encode_since(2026-10-09 14:05 +03:00) == ea070a090e05000c
        val t = ZonedDateTime.of(2026, 10, 9, 14, 5, 0, 0, ZoneOffset.ofHours(3))
        assertEquals("ea070a090e05000c", MiBand6.encodeTime(t).toHex())
    }

    @Test
    fun `время начала из ответа браслета учитывает часовой пояс`() {
        // 10 01 01 <len> 2026-10-09 12:03:00 +07:00 (28 четвертей часа)
        val response = "10010158050000ea070a090c03001c".hexToBytes()
        val start = MiBand6.decodeTime(response, 7)
        assertEquals(ZonedDateTime.of(2026, 10, 9, 12, 3, 0, 0, tz).toInstant(), start.toInstant())
    }

    @Test
    fun `отрицательное смещение часового пояса читается со знаком`() {
        val data = byteArrayOf(0xea.toByte(), 0x07, 1, 2, 3, 4, 5, (-20).toByte())
        assertEquals(-5 * 3600, MiBand6.decodeTime(data, 0).offset.totalSeconds)
    }

    @Test
    fun `история разбирается поминутно, пульс 0 означает, что замера не было`() {
        // первые записи history.csv: 12:03 kind=112 steps=5 hr=нет; 14:51 hr=73
        val raw = "70060500" + "00800000" + "50090049" + "00800000"
        val start = ZonedDateTime.of(2026, 10, 9, 12, 3, 0, 0, tz)
        val samples = MiBand6.parseSamples(start, raw.hexToBytes())
        assertEquals(2, samples.size)
        assertEquals(112, samples[0].kind)
        assertEquals(5, samples[0].steps)
        assertNull(samples[0].heartRate)
        assertEquals(73, samples[1].heartRate)
        assertEquals(start.toEpochSecond() + 60, samples[1].epochSecond)
    }

    @Test
    fun `команда частоты измерения пульса содержит число минут`() {
        assertEquals("1401", MiBand6.heartRateIntervalCommand(1).toHex())
        assertEquals("140a", MiBand6.heartRateIntervalCommand(10).toHex())
        assertEquals("1400", MiBand6.heartRateIntervalCommand(0).toHex())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `частота измерения пульса больше 120 минут отклоняется`() {
        MiBand6.heartRateIntervalCommand(121)
    }
}
