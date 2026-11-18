package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiHistoryFormatTest {
    private fun rejects(block: () -> Unit) {
        try { block() } catch (_: IOException) { return }
        throw AssertionError("Ожидался отказ")
    }
    @Test fun `литеральный fetch сохраняет все семь байтов без нормализации времени`() {
        assertArrayEquals(XiaomiScriptedPeer.hex("080810035209120701020304fc0400"),
            XiaomiHistorySnapshot.request(XiaomiHistoryFixtures.id))
    }
    @Test fun `snapshot сохраняет полный multiset включая неизвестные типы и очищается после close`() {
        val first = XiaomiHistoryFixtures.id
        val second = first.copyOf().also { it[5] = 9 }
        val before = XiaomiHistoryFixtures.index(first + second + first).use { XiaomiHistorySnapshot(it) }
        val after = XiaomiHistoryFixtures.index(second + first + first).use { XiaomiHistorySnapshot(it) }
        val changed = XiaomiHistoryFixtures.index(second + first + second).use { XiaomiHistorySnapshot(it) }
        assertTrue(before.unchanged(after))
        assertFalse(before.unchanged(changed))
        val selected = before.details()
        assertEquals(1, selected.size)
        assertArrayEquals(first, selected[0])
        selected.forEach { it.fill(0) }
        before.close(); after.close(); changed.close()
        rejects { before.details() }
        rejects { before.unchanged(before) }
    }
    @Test fun `каждый байт ID обязателен даже при правильном CRC и известном формате`() {
        for (index in 0..6) {
            XiaomiHistoryTransfer(repeat = false).use { transfer ->
                transfer.request(XiaomiHistoryFixtures.id)
                val changed = XiaomiHistoryFixtures.id.also { it[index] = (it[index].toInt() xor 1).toByte() }
                transfer.accept(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file(changed)), waitingForIndex = false)
                assertFalse(transfer.found)
                assertEquals(0, transfer.exactTransfers)
            }
        }
    }
    @Test fun `unsigned HR255 и нулевые коды считаются отдельно от отсутствующей validity`() {
        val body = byteArrayOf(0xb0.toByte(), 0, 0xc0.toByte(), 0, 0, 0, 0, 0x40, -1, 99, 0, 0, 0)
        val file = XiaomiHistoryFixtures.file(body = body)
        assertEquals(XiaomiHistoryAggregate(2, 2, 1, 1, 2, 2, 0), XiaomiDailyDetails.parse(file, file.size))
        val absent = XiaomiHistoryFixtures.file(body = byteArrayOf(0xa0.toByte(), 0, 0x80.toByte(), 0, 0, 0, 0, 0, 0))
        assertEquals(XiaomiHistoryAggregate(1, 0, 0, 0, 0, 0, 0), XiaomiDailyDetails.parse(absent, absent.size))
    }
    @Test fun `обрезанные группы extra и лимит записей не возвращают агрегаты`() {
        for (body in listOf(byteArrayOf(1), byteArrayOf(0xb0.toByte(), 0, 0, 0, 0, 0, 1),
            byteArrayOf(0xa0.toByte(), 0, 0, 0, 0, 0, 0, 0x40), ByteArray(6 + 2881).also { it[2] = 0xc0.toByte() })) {
            val file = XiaomiHistoryFixtures.file(body = body)
            rejects { XiaomiDailyDetails.parse(file, file.size) }
        }
    }
    @Test fun `details v4 возвращает числовые поля и сохраняет нулевые коды`() {
        val body = byteArrayOf(
            0x90.toByte(), 0, 0xc0.toByte(), 0, 0, 0,
            5, 0, 0,
            5, 0, 0xff.toByte(),
        )
        val file = XiaomiHistoryFixtures.file(body = body)
        val records = XiaomiDailyDetails.parseRecords(file, file.size)

        assertEquals(2, records.size)
        assertEquals(XiaomiDailyDetailRecord(0, 5, 0), records[0])
        assertEquals(XiaomiDailyDetailRecord(1, 5, 255), records[1])
    }

    @Test fun `отсутствующие поля details v4 остаются null`() {
        val body = byteArrayOf(
            0x80.toByte(), 0, 0x80.toByte(), 0, 0, 0,
            0, 0, 0,
        )
        val records = XiaomiDailyDetails.parseRecords(XiaomiHistoryFixtures.file(body = body), 21)

        assertEquals(listOf(XiaomiDailyDetailRecord(0, null, null)), records)
    }

    @Test fun `extra byte перед двумя последними группами не смещает следующую запись`() {
        val header = byteArrayOf(0xbf.toByte(), -1, -1, -1, -1, 0xf0.toByte())
        val first = byteArrayOf(-1, -1, 11, 12, 13, 14, -1, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24)
        val second = byteArrayOf(0, 0, 11, 12, 13, 14, 70, 15, 16, 17, 18, 19, 21, 22, 23, 24)
        val file = XiaomiHistoryFixtures.file(body = header + first + second)
        val records = XiaomiDailyDetails.parseRecords(file, file.size)
        assertEquals(listOf(XiaomiDailyDetailRecord(0, 16383, 255), XiaomiDailyDetailRecord(1, 0, 70)), records)
        assertEquals("XiaomiDailyDetailRecord", records[0].toString())
        rejects { XiaomiDailyDetails.parseRecords(file, file.size - 1) }
    }

    @Test fun `неверный размер и чужой заголовок не допускаются к декодеру записей`() {
        val file = XiaomiHistoryFixtures.file()
        for (size in listOf(Int.MIN_VALUE, -1, 0, 17, file.size + 1, Int.MAX_VALUE)) {
            rejects { XiaomiDailyDetails.parseRecords(file, size) }
            rejects { XiaomiDailyDetails.parse(file, size) }
        }
        for (index in listOf(5, 6, 7)) {
            val changed = file.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            XiaomiHistoryFixtures.crc(changed)
            rejects { XiaomiDailyDetails.parseRecords(changed, changed.size) }
        }
    }

    @Test fun `неполный хвост и лишние записи не возвращают частичный список минут`() {
        val header = byteArrayOf(0, 0, 0xc0.toByte(), 0, 0, 0)
        val largest = XiaomiHistoryFixtures.file(body = header + ByteArray(2880) { 70 })
        assertEquals(2880, XiaomiDailyDetails.parseRecords(largest, largest.size).size)
        val overflow = XiaomiHistoryFixtures.file(body = header + ByteArray(2881) { 70 })
        rejects { XiaomiDailyDetails.parseRecords(overflow, overflow.size) }
    }

    @Test fun `границы chunk порядка и общий лимит файлов действуют до и после request`() {
        XiaomiHistoryTransfer(repeat = false).use { transfer ->
            rejects { transfer.accept(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file(), total = 2, number = 2), false) }
        }
        XiaomiHistoryTransfer(repeat = false).use { transfer ->
            repeat(16) { transfer.accept(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file()), false) }
            rejects { transfer.accept(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file()), false) }
        }
        XiaomiHistoryTransfer(repeat = false).use { transfer ->
            transfer.accept(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file(), total = 2, end = 15), false)
            rejects { transfer.requireBoundary() }
        }
    }
    @Test fun `все буферы ID и digest очищаются в том числе при незавершённом повторе`() {
        val transfer = XiaomiHistoryTransfer(repeat = true)
        transfer.request(XiaomiHistoryFixtures.id)
        transfer.accept(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file()), false)
        val privateBytes = listOf("buffer", "target", "baselineDigest").map {
            transfer.javaClass.getDeclaredField(it).apply { isAccessible = true }.get(transfer) as ByteArray
        }
        transfer.repeatRequest()
        transfer.accept(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file(), total = 2, end = 15), false)
        transfer.close(); transfer.close()
        for (bytes in privateBytes) assertTrue(bytes.all { it == 0.toByte() })
        rejects { transfer.request(XiaomiHistoryFixtures.id) }
        rejects { transfer.repeatRequest() }
        rejects { transfer.accept(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file()), false) }
        rejects { transfer.requireBoundary() }
    }
}
