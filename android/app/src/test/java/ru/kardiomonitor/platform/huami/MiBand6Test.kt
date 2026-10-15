package ru.kardiomonitor.platform.huami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kardiomonitor.features.monitoring.domain.ActivityKind
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.StepCount
import ru.kardiomonitor.platform.ble.hexToBytes
import ru.kardiomonitor.platform.ble.toHex
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
        val minutes = ActivityRecords.parse(start, raw.hexToBytes())
        assertEquals(2, minutes.size)
        assertEquals(ActivityKind.Other(112), minutes[0].kind)
        assertEquals(StepCount(5), minutes[0].steps)
        assertNull(minutes[0].heartRate)
        assertEquals(HeartRate(73), minutes[1].heartRate)
        assertEquals(start.toInstant().plusSeconds(60), minutes[1].start)
    }

    @Test
    fun `коды браслета снят, заряжается и сон переводятся в типы активности`() {
        // kind 115, 118, 120 — docs/ble-protocol.md, «История активности»
        val raw = "73000000" + "00800000" + "76000000" + "00800000" + "78000000" + "00800000"
        val start = ZonedDateTime.of(2026, 10, 9, 0, 0, 0, 0, tz)
        val kinds = ActivityRecords.parse(start, raw.hexToBytes()).map { it.kind }
        assertEquals(listOf(ActivityKind.NotWorn, ActivityKind.Charging, ActivityKind.Sleep), kinds)
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
