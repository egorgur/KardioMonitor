package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XiaomiHistorySelectionEvidenceTest {
    private val epoch = 1_700_000_040L
    private fun reference(time: Long = epoch) = XiaomiHistoryReference(time, 25_200, 72, null)
    private fun id(time: Long): ByteArray = ByteArray(7).also { bytes ->
        repeat(4) { bytes[it] = (time ushr (it * 8)).toByte() }
        bytes[4] = 28; bytes[5] = 4
    }
    private fun refuse(details: List<ByteArray>, reference: XiaomiHistoryReference, diagnostics: XiaomiHistoryDiagnostics) {
        try {
            XiaomiHistoryReferenceTarget.select(details, reference, diagnostics)
            throw AssertionError("Ожидался отказ")
        } catch (_: IOException) { }
    }

    @Test fun `начало после эталона но до часов телефона различается с будущим и не разрешает fetch`() {
        val identifier = id(epoch + 600)
        val original = identifier.copyOf()
        val diagnostics = XiaomiHistoryDiagnostics(epoch + 1800)
        var fetches = 0
        XiaomiHistoryTransfer(repeat = true, reference = reference()).use { transfer ->
            try {
                XiaomiHistoryRead.read(0, true, transfer, { XiaomiHistoryFixtures.index(identifier) },
                    { XiaomiHistoryReferenceTarget.select(it, reference(), diagnostics) }, diagnostics) { fetches++ }
                throw AssertionError("Ожидался отказ")
            } catch (_: IOException) { }
        }
        assertEquals(0, fetches)
        assertArrayEquals(original, identifier)
        assertEquals(XiaomiHistoryDiagnostics.Selection.NO_PRECEDING_FILE, diagnostics.selection)
        val evidence = requireNotNull(diagnostics.selectionEvidence)
        assertEquals(XiaomiHistorySelectionEvidence.ReferenceClockRelation.WITHIN_1H, evidence.referenceClockRelation)
        assertEquals(XiaomiHistorySelectionEvidence.CandidateClockRelation.ALL_NOT_FUTURE, evidence.candidateClockRelation)
        assertEquals(XiaomiHistorySelectionEvidence.AfterReferenceGap.UNDER_1H, evidence.afterReferenceGap)
        assertEquals(true, evidence.afterReferenceSameLocalDay)
        assertEquals("XiaomiHistorySelectionEvidence", evidence.toString())
    }

    @Test fun `будущее часов и смешанные начала не маскируются как доступная старая история`() {
        val diagnostics = XiaomiHistoryDiagnostics(epoch)
        refuse(listOf(id(epoch + 120)), reference(epoch + 60), diagnostics)
        assertEquals(XiaomiHistorySelectionEvidence.ReferenceClockRelation.FUTURE,
            diagnostics.selectionEvidence?.referenceClockRelation)
        assertEquals(XiaomiHistorySelectionEvidence.CandidateClockRelation.ALL_FUTURE,
            diagnostics.selectionEvidence?.candidateClockRelation)
        assertEquals(0, XiaomiHistoryReferenceTarget.select(listOf(id(epoch), id(epoch + 120)), reference(), diagnostics))
        assertEquals(XiaomiHistorySelectionEvidence.CandidateClockRelation.SOME_FUTURE,
            diagnostics.selectionEvidence?.candidateClockRelation)
        assertNull(diagnostics.selectionEvidence?.afterReferenceGap)
        assertNull(diagnostics.selectionEvidence?.afterReferenceSameLocalDay)
    }

    @Test fun `граница дня определяется локально без сдвига epoch и неизвестные часы не выдумываются`() {
        val utcMidnight = epoch / 86_400 * 86_400
        val beforeLocalMidnight = utcMidnight + 17 * 3600 - 60
        val diagnostics = XiaomiHistoryDiagnostics()
        refuse(listOf(id(beforeLocalMidnight + 120)), reference(beforeLocalMidnight), diagnostics)
        assertEquals(false, diagnostics.selectionEvidence?.afterReferenceSameLocalDay)
        assertEquals(XiaomiHistorySelectionEvidence.AfterReferenceGap.UNDER_5M,
            diagnostics.selectionEvidence?.afterReferenceGap)
        assertNull(diagnostics.selectionEvidence?.referenceClockRelation)
        assertNull(diagnostics.selectionEvidence?.candidateClockRelation)
        refuse(emptyList(), reference(), diagnostics)
        assertNull(diagnostics.selectionEvidence?.afterReferenceGap)
        assertNull(diagnostics.selectionEvidence?.afterReferenceSameLocalDay)
        assertNull(diagnostics.selectionEvidence?.candidateClockRelation)
    }
}
