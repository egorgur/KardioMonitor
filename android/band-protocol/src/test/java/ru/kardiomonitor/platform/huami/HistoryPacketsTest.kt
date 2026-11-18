package ru.kardiomonitor.platform.huami

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.platform.ble.hexToBytes
import ru.kardiomonitor.platform.ble.toHex
import java.io.IOException

class HistoryPacketsTest {
    @Test fun `порция собирается из пакетов без счётчиков`() {
        val packets = HistoryPackets()
        packets.add("0070060500".hexToBytes())
        packets.add("0100800000".hexToBytes())
        assertEquals("7006050000800000", packets.records().toHex())
    }

    @Test fun `счётчик пакетов после 255 продолжается с 0`() {
        val packets = HistoryPackets()
        (0..256).forEach { packets.add(byteArrayOf(it.toByte(), 0x01)) }
        assertEquals(257, packets.records().size)
    }

    @Test(expected = IOException::class)
    fun `пропуск счётчика пакета портит порцию`() {
        val packets = HistoryPackets()
        packets.add("0070060500".hexToBytes())
        packets.add("0200800000".hexToBytes())
        packets.records()
    }

    @Test fun `новая порция начинается заново после испорченной`() {
        val packets = HistoryPackets()
        packets.add("0170060500".hexToBytes())
        packets.reset()
        packets.add("0070060500".hexToBytes())
        assertEquals("70060500", packets.records().toHex())
    }
}
