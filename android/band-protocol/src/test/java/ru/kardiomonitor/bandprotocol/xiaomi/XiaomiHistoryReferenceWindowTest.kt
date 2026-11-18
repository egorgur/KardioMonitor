package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class XiaomiHistoryReferenceWindowTest {
    private val epoch = 1_700_000_040L
    private val reference = XiaomiHistoryReference(epoch, 25_200, 70, null)
    private fun withSample(start: Long, empty: Boolean = false, block: (XiaomiHistoryReferenceSample) -> Unit) {
        val id = XiaomiHistoryFixtures.id.also { bytes ->
            repeat(4) { bytes[it] = (start ushr (8 * it)).toByte() }
            bytes[4] = 28
        }
        val file = if (empty) XiaomiHistoryFixtures.file(id, ByteArray(6)) else XiaomiHistoryFixtures.file(id)
        XiaomiHistoryReferenceSample().use { sample ->
            XiaomiHistoryTransfer(repeat = true, sample = sample).use { transfer ->
                transfer.request(id)
                transfer.accept(XiaomiHistoryFixtures.chunk(file), false)
                transfer.repeatRequest()
                transfer.accept(XiaomiHistoryFixtures.chunk(file), false)
            }
            block(sample)
        }
    }

    @Test fun `интервал полуоткрытый и не сдвигается повторно на timezone`() {
        withSample(epoch) { sample ->
            val cases = listOf(-60L to XiaomiHistoryReferenceSample.WindowRelation.BEFORE,
                0L to XiaomiHistoryReferenceSample.WindowRelation.INSIDE,
                60L to XiaomiHistoryReferenceSample.WindowRelation.INSIDE,
                120L to XiaomiHistoryReferenceSample.WindowRelation.AFTER)
            for ((delta, expected) in cases) {
                assertEquals(expected, sample.windowRelation(reference.copy(epochSeconds = epoch + delta)))
            }
            assertEquals(XiaomiHistoryReferenceSample.WindowRelation.INSIDE,
                sample.windowRelation(reference.copy(utcOffsetSeconds = -3600)))
        }
    }

    @Test fun `пустое неподдержанное и пересекающее2038 окно не притворяется before или after`() {
        withSample(epoch, empty = true) { sample ->
            assertEquals(XiaomiHistoryReferenceSample.WindowRelation.EMPTY, sample.windowRelation(reference))
        }
        for (start in listOf(epoch + 1, 0x80000000L, Int.MAX_VALUE.toLong() / 60 * 60)) {
            withSample(start) { sample ->
                assertEquals(XiaomiHistoryReferenceSample.WindowRelation.UNSUPPORTED_TIME, sample.windowRelation(reference))
            }
        }
    }

    @Test fun `незавершённый и очищенный образец не раскрывают положение эталона`() {
        val sample = XiaomiHistoryReferenceSample()
        for (closed in listOf(false, true)) {
            if (closed) sample.close()
            try {
                sample.windowRelation(reference)
                throw AssertionError("Ожидался отказ")
            } catch (_: IOException) { }
        }
    }
}
