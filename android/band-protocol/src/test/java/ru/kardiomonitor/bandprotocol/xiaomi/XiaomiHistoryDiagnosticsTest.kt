package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class XiaomiHistoryDiagnosticsTest {
    private val reference = XiaomiHistoryReference(1_700_000_040L, 25_200, 72, null)
    private val id = XiaomiScriptedPeer.hex("28f153651c0400")
    private fun failure(): Nothing = throw IOException("private source must not escape")
    private fun rejects(block: () -> Unit) {
        try { block() } catch (_: IOException) { return }
        throw AssertionError("Ожидался отказ")
    }

    @Test fun `отказ остаётся на точной стадии и не провоцирует дополнительный fetch`() {
        for (expected in listOf(XiaomiHistoryDiagnostics.Stage.INDEX, XiaomiHistoryDiagnostics.Stage.SELECTION,
            XiaomiHistoryDiagnostics.Stage.FIRST_TRANSFER, XiaomiHistoryDiagnostics.Stage.REPEAT_TRANSFER,
            XiaomiHistoryDiagnostics.Stage.RETENTION, XiaomiHistoryDiagnostics.Stage.BOUNDARY)) {
            val diagnostics = XiaomiHistoryDiagnostics()
            var queries = 0
            var fetches = 0
            XiaomiHistoryTransfer(repeat = true).use { transfer ->
                rejects {
                    XiaomiHistoryRead.read(0, true, transfer, query = {
                        queries++
                        if (queries == 1 && expected == XiaomiHistoryDiagnostics.Stage.INDEX ||
                            queries == 2 && expected == XiaomiHistoryDiagnostics.Stage.RETENTION) failure()
                        if (queries == 2 && expected == XiaomiHistoryDiagnostics.Stage.BOUNDARY)
                            transfer.accept(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file(id), total = 2, end = 15), true)
                        XiaomiHistoryFixtures.index(id)
                    }, select = {
                        if (expected == XiaomiHistoryDiagnostics.Stage.SELECTION) failure()
                        0
                    }, diagnostics = diagnostics) {
                        fetches++
                        if (fetches == 1 && expected == XiaomiHistoryDiagnostics.Stage.FIRST_TRANSFER ||
                            fetches == 2 && expected == XiaomiHistoryDiagnostics.Stage.REPEAT_TRANSFER) failure()
                        transfer.accept(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file(id)), false)
                    }
                }
            }
            assertEquals(expected, diagnostics.stage)
            val completed = when (expected) {
                XiaomiHistoryDiagnostics.Stage.INDEX, XiaomiHistoryDiagnostics.Stage.SELECTION -> 0
                XiaomiHistoryDiagnostics.Stage.FIRST_TRANSFER -> 1
                else -> 2
            }
            assertEquals(completed, fetches)
        }
    }

    @Test fun `полный протокольный путь не превращает несовпавший эталон в успешную проверку`() {
        val diagnostics = XiaomiHistoryDiagnostics()
        XiaomiHistoryTransfer(repeat = false, reference = reference).use { transfer ->
            val result = XiaomiHistoryRead.read(0, false, transfer,
                { XiaomiHistoryFixtures.index(id) }, diagnostics = diagnostics) {
                transfer.accept(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file(id)), false)
            }
            assertEquals(XiaomiHistoryDiagnostics.Stage.COMPLETE, diagnostics.stage)
            assertEquals(false, result.referenceMatched)
            assertFalse(result.clinicalImportAllowed)
        }
    }

    @Test fun `стадия сохраняется после нормализации исключения клиента и закрытия сокета`() = runBlocking {
        val peer = XiaomiScriptedPeer().apply { historyEnabled = true }
        val client = XiaomiReadOnlyClient({ peer }, 37, 1_000, 200)
        val diagnostics = XiaomiHistoryDiagnostics()
        client.open("00:11:22:33:44:55", peer.secret)
        try {
            client.verifyPastHistoryReference(0, reference, selectReferenceTime = true, diagnostics = diagnostics)
            throw AssertionError("Ожидался отказ")
        } catch (_: IOException) { }
        assertEquals(XiaomiHistoryDiagnostics.Stage.SELECTION, diagnostics.stage)
        assertEquals(XiaomiHistoryDiagnostics.Selection.UNALIGNED_EPOCH, diagnostics.selection)
        assertEquals(0, peer.historyFetches)
        assertEquals(1, peer.closeCount)
    }
}
