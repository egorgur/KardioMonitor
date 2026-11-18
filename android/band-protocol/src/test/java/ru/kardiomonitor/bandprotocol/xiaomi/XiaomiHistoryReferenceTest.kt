package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiHistoryReferenceTest {
    private val epoch = 1_700_000_040L
    private fun file(heartRate: Int = 72, steps: Int = 5): ByteArray = XiaomiHistoryFixtures.file(
        identifier = XiaomiScriptedPeer.hex("28f153651c0400"),
        body = byteArrayOf(0x90.toByte(), 0, 0xc0.toByte(), 0, 0, 0, 0, 0, 70, steps.toByte(), 0, heartRate.toByte()),
    )
    private fun reference(time: Long = epoch + 60, offset: Int = 25_200, heartRate: Int? = 72, steps: Int? = 5) =
        XiaomiHistoryReference(time, offset, heartRate, steps)
    private fun compare(bytes: ByteArray, reference: XiaomiHistoryReference = reference()) =
        XiaomiHistoryReferenceCheck.compare(bytes, bytes.size, reference)

    @Test fun `эталон на второй минуте совпадает без повторного применения часового пояса`() {
        val result = compare(file())
        assertTrue(result.recordLocated)
        assertTrue(result.timezoneMatches)
        assertEquals(true, result.heartRateMatches)
        assertEquals(true, result.stepsMatch)
        assertTrue(result.matches)
        assertFalse(compare(file(), reference(time = epoch + 60 + 25_200)).matches)
        assertEquals("XiaomiHistoryReference", reference().toString())
        assertEquals("XiaomiHistoryReferenceComparison", result.toString())
    }

    @Test fun `проверка не ищет похожие значения и не смещает время до совпадения`() {
        assertFalse(compare(file(), reference(time = epoch)).matches)
        assertFalse(compare(file(), reference(time = epoch - 60)).recordLocated)
        assertFalse(compare(file(), reference(time = epoch + 120)).recordLocated)
        assertFalse(compare(file(), reference(offset = -3_600)).timezoneMatches)
        assertFalse(compare(file(), reference(heartRate = 73)).matches)
        assertFalse(compare(file(), reference(steps = 6)).matches)
    }

    @Test fun `нулевой и sentinel HR не совпадают с измеренным пульсом`() {
        for (raw in listOf(0, 255)) assertFalse(compare(file(heartRate = raw)).matches)
        val bytes = file().also { it[10] = 0x80.toByte(); XiaomiHistoryFixtures.crc(it) }
        assertEquals(false, compare(bytes).heartRateMatches)
        val absentSteps = file().also { it[8] = 0x80.toByte(); XiaomiHistoryFixtures.crc(it) }
        assertEquals(false, compare(absentSteps, reference(steps = 0)).stepsMatch)
    }

    @Test fun `непредоставленный показатель не считается проверенным`() {
        val heartOnly = compare(file(), reference(steps = null))
        assertTrue(heartOnly.matches)
        assertNull(heartOnly.stepsMatch)
        val stepsOnly = compare(file(steps = 0), reference(heartRate = null, steps = 0))
        assertTrue(stepsOnly.matches)
        assertNull(stepsOnly.heartRateMatches)
    }

    @Test fun `signed четверти часа сравниваются напрямую включая отрицательные и дробные зоны`() {
        for (offset in listOf(-43_200, -3_600, 19_800, 25_200, 50_400)) {
            val bytes = file().also { it[4] = (offset / 900).toByte(); XiaomiHistoryFixtures.crc(it) }
            assertTrue(compare(bytes, reference(offset = offset)).matches)
        }
    }

    @Test fun `неподдержанное время и пустой файл не дают случайного совпадения`() {
        for (id in listOf("29f153651c0400", "000000801c0400", "ffffffff1c0400")) {
            val bytes = file().also { XiaomiScriptedPeer.hex(id).copyInto(it); XiaomiHistoryFixtures.crc(it) }
            assertFalse(compare(bytes).recordLocated)
        }
        val empty = XiaomiHistoryFixtures.file(
            XiaomiScriptedPeer.hex("28f153651c0400"), ByteArray(6),
        )
        assertFalse(compare(empty, reference(time = epoch)).matches)
    }

    @Test fun `эталон не принимается с неоднозначным временем неизвестным показателем или лишней точностью`() {
        val invalid = listOf<() -> XiaomiHistoryReference>(
            { reference(time = -60) }, { reference(time = Long.MAX_VALUE) }, { reference(time = epoch + 1) },
            { reference(offset = 25_201) }, { reference(offset = 51_300) }, { reference(offset = -44_100) },
            { reference(heartRate = 0) }, { reference(heartRate = 255) },
            { reference(steps = -1) }, { reference(steps = 16_384) }, { reference(heartRate = null, steps = null) },
        )
        for (construct in invalid) {
            try { construct(); throw AssertionError("Ожидался отказ") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `карантин и начатый до запроса файл не проверяются по эталону`() {
        val bytes = file()
        val id = bytes.copyOfRange(0, 7)
        XiaomiHistoryTransfer(repeat = false, reference = reference()).use { transfer ->
            transfer.accept(XiaomiHistoryFixtures.chunk(bytes, total = 2, end = 15), false)
            transfer.request(id)
            transfer.accept(XiaomiHistoryFixtures.chunk(bytes, total = 2, number = 2, start = 15), false)
            assertNull(transfer.referenceMatches)
            assertEquals(0, transfer.exactTransfers)
            transfer.accept(XiaomiHistoryFixtures.chunk(bytes), false)
            assertEquals(true, transfer.referenceMatches)
        }
        XiaomiHistoryTransfer(repeat = false, reference = reference()).use { transfer ->
            transfer.request(id.also { it[0] = (it[0] + 1).toByte() })
            transfer.accept(XiaomiHistoryFixtures.chunk(bytes), false)
            assertNull(transfer.referenceMatches)
        }
    }

    @Test fun `эталон проверяется в обоих exact чтениях и не разрешает импорт`() {
        val bytes = file()
        for (second in listOf(file(), file(heartRate = 73))) {
            XiaomiHistoryTransfer(repeat = true, reference = reference()).use { transfer ->
                transfer.request(bytes.copyOfRange(0, 7))
                transfer.accept(XiaomiHistoryFixtures.chunk(bytes), false)
                assertEquals(true, transfer.referenceMatches)
                transfer.repeatRequest()
                transfer.accept(XiaomiHistoryFixtures.chunk(second), false)
                assertEquals(second.contentEquals(bytes), transfer.referenceMatches)
                assertEquals(second.contentEquals(bytes), transfer.bytesIdentical)
            }
        }
        val transfer = XiaomiHistoryTransfer(repeat = false, reference = reference())
        val query = { XiaomiHistoryFixtures.index(bytes.copyOfRange(0, 7)) }
        transfer.use {
            val result = XiaomiHistoryRead.read(0, false, it, query) { _ ->
                transfer.accept(XiaomiHistoryFixtures.chunk(bytes), false)
            }
            assertEquals(true, result.referenceMatched)
            assertTrue(result.retentionUnchanged)
            assertFalse(result.timeAndValuesVerified)
            assertFalse(result.fullCoverageVerified)
            assertFalse(result.clinicalImportAllowed)
        }
    }

    @Test fun `неверный CRC не позволяет даже совпавшему эталону попасть в результат`() {
        val bytes = file()
        XiaomiHistoryTransfer(repeat = false, reference = reference()).use { transfer ->
            transfer.request(bytes.copyOfRange(0, 7))
            bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
            try {
                transfer.accept(XiaomiHistoryFixtures.chunk(bytes), false)
                throw AssertionError("Ожидался отказ")
            } catch (_: IOException) { }
            assertNull(transfer.referenceMatches)
        }
    }

    @Test fun `совпадение начала не скрывает повреждённый хвост`() {
        val bytes = file()
        try {
            XiaomiHistoryReferenceCheck.compare(bytes, bytes.size - 1, reference(time = epoch, heartRate = 70, steps = 0))
            throw AssertionError("Ожидался отказ")
        } catch (_: IOException) { }
    }
}
