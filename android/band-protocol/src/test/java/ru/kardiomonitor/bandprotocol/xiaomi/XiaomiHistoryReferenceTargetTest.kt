package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class XiaomiHistoryReferenceTargetTest {
    private val epoch = 1_700_000_040L
    private val reference = XiaomiHistoryReference(epoch + 60, 25_200, 72, null)
    private fun id(time: Long, zone: Int = 28): ByteArray = ByteArray(7).also { bytes ->
        repeat(4) { bytes[it] = (time ushr (8 * it)).toByte() }
        bytes[4] = zone.toByte(); bytes[5] = 4
    }
    private fun rejects(block: () -> Unit) {
        try { block() } catch (_: IOException) { return }
        throw AssertionError("Ожидался отказ")
    }

    @Test fun `кандидат выбирается по ближайшему началу а не позиции свежего индекса`() {
        val earlier = id(epoch - 3600)
        val wanted = id(epoch)
        val future = id(epoch + 120)
        val wrongZone = id(epoch + 60, 0)
        for (details in listOf(listOf(earlier, wanted, future, wrongZone), listOf(wrongZone, future, wanted, earlier))) {
            assertArrayEquals(wanted, details[XiaomiHistoryReferenceTarget.select(details, reference)])
        }
    }

    @Test fun `отсутствующий слишком далёкий и неоднозначный кандидат отклоняется до fetch`() {
        for (details in listOf(emptyList(), listOf(id(epoch + 120)), listOf(id(epoch, 0)),
            listOf(id(epoch - 2880 * 60)), listOf(id(epoch), id(epoch)), listOf(id(epoch + 1)), listOf(id(0x80000000)))) {
            rejects { XiaomiHistoryReferenceTarget.select(details, reference) }
        }
        var fetches = 0
        XiaomiHistoryTransfer(repeat = true, reference = reference).use { transfer ->
            rejects {
                XiaomiHistoryRead.read(0, true, transfer, { XiaomiHistoryFixtures.index(id(epoch, 0)) },
                    { XiaomiHistoryReferenceTarget.select(it, reference) }) { fetches++ }
            }
        }
        assertEquals(0, fetches)
    }

    @Test fun `безопасная причина отсутствия кандидата различает время зону и границы окна`() {
        val cases = listOf(
            emptyList<ByteArray>() to XiaomiHistoryDiagnostics.Selection.EMPTY,
            listOf(id(0x80000000)) to XiaomiHistoryDiagnostics.Selection.UNSUPPORTED_EPOCH,
            listOf(id(epoch + 1)) to XiaomiHistoryDiagnostics.Selection.UNALIGNED_EPOCH,
            listOf(id(epoch, 0)) to XiaomiHistoryDiagnostics.Selection.TIMEZONE_UNAVAILABLE,
            listOf(id(epoch + 120)) to XiaomiHistoryDiagnostics.Selection.NO_PRECEDING_FILE,
            listOf(id(epoch - 2880 * 60)) to XiaomiHistoryDiagnostics.Selection.OUTSIDE_WINDOW,
            listOf(id(epoch), id(epoch)) to XiaomiHistoryDiagnostics.Selection.AMBIGUOUS,
        )
        val diagnostics = XiaomiHistoryDiagnostics()
        for ((details, expected) in cases) {
            rejects { XiaomiHistoryReferenceTarget.select(details, reference, diagnostics) }
            assertEquals(expected, diagnostics.selection)
            assertEquals("XiaomiHistoryDiagnostics", diagnostics.toString())
        }
        assertEquals(0, XiaomiHistoryReferenceTarget.select(listOf(id(epoch)), reference, diagnostics))
        assertEquals(XiaomiHistoryDiagnostics.Selection.SELECTED, diagnostics.selection)
    }

    @Test fun `непокрытый ближайший кандидат не запускает перебор предыдущих файлов`() {
        val earlier = id(epoch - 3600)
        val selected = id(epoch)
        var fetches = 0
        val file = XiaomiHistoryFixtures.file(selected, byteArrayOf(0, 0, 0xc0.toByte(), 0, 0, 0, 72))
        XiaomiHistoryTransfer(repeat = false, reference = reference).use { transfer ->
            val result = XiaomiHistoryRead.read(0, false, transfer,
                { XiaomiHistoryFixtures.index(earlier + selected) },
                { XiaomiHistoryReferenceTarget.select(it, reference) }) { requested ->
                fetches++
                assertArrayEquals(selected, requested)
                transfer.accept(XiaomiHistoryFixtures.chunk(file), false)
            }
            assertEquals(1, fetches)
            assertEquals(false, result.referenceMatched)
            assertFalse(result.clinicalImportAllowed)
        }
    }
}
