package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiTodayReferenceTest {
    private val reference = XiaomiHistoryReference(1_700_000_040L, 25_200, 72, null)
    private fun peer(scenario: String = "valid") = XiaomiScriptedPeer().apply {
        historyEnabled = true
        historyIndexSource = XiaomiHistoryIndex.TODAY
        historyScenario = scenario
    }
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block() } catch (_: IOException) { return }
        throw AssertionError("Ожидался отказ")
    }

    @Test fun `явный today repeat использует только today индекс и не допускает клинический импорт`() = runBlocking {
        val peer = peer()
        val client = XiaomiReadOnlyClient({ peer }, 37, 1_000, 200)
        client.open("00:11:22:33:44:55", peer.secret)
        val result = client.verifyPastHistoryReference(0, reference, source = XiaomiHistoryIndex.TODAY)
        assertEquals(2, result.exactTransfers)
        assertEquals(true, result.repeatedBytesIdentical)
        assertTrue(result.retentionUnchanged)
        assertEquals(false, result.referenceMatched)
        assertFalse(result.clinicalImportAllowed)
        assertEquals(listOf(1L to 26L, 1L to 27L, 2L to 2L, 8L to 1L, 8L to 3L, 8L to 3L, 8L to 1L), peer.commands)
        assertEquals(73, client.readBattery())
        client.close()
    }

    @Test fun `today ошибки не вызывают fallback в past или ослабление ID CRC retention`() = runBlocking {
        for (scenario in listOf("crc", "padding", "missing", "plaintext", "retention_partial")) {
            val peer = peer(scenario)
            val client = XiaomiReadOnlyClient({ peer }, 37, 1_000, 200)
            client.open("00:11:22:33:44:55", peer.secret)
            rejects { client.verifyPastHistoryReference(0, reference, source = XiaomiHistoryIndex.TODAY) }
            assertFalse(peer.commands.contains(8L to 2L))
            assertFalse(peer.commands.contains(8L to 5L))
            assertTrue(peer.historyFetches <= 2)
            assertEquals(1, peer.closeCount)
        }
    }

    @Test fun `ответ не того индекса и неверное поле protobuf не принимаются как пустой успех`() {
        for (source in XiaomiHistoryIndex.entries) {
            val other = XiaomiHistoryIndex.entries.first { it != source }
            XiaomiHistoryFixtures.index(XiaomiHistoryFixtures.id, source).use { command ->
                try {
                    XiaomiHistorySnapshot(command, other).close()
                    throw AssertionError("Ожидался отказ")
                } catch (_: IOException) { }
            }
            val body = XiaomiProto.Writer().use { it.bytes(1, XiaomiHistoryFixtures.id).build() }
            val bytes = XiaomiProto.Writer().use { it.number(1, 8).number(2, source.subtype.toLong()).bytes(10, body).build() }
            XiaomiProto.parse(bytes).use { command ->
                try {
                    XiaomiHistorySnapshot(command, source).close()
                    throw AssertionError("Ожидался отказ")
                } catch (_: IOException) { }
            }
        }
    }

    @Test fun `today GET содержит options а ID обоих ответов читаются из второго поля health`() {
        assertArrayEquals(XiaomiScriptedPeer.hex("0808100152042a020800"), XiaomiHistoryIndex.TODAY.request())
        assertArrayEquals(XiaomiScriptedPeer.hex("08081002"), XiaomiHistoryIndex.PAST.request())
        val literal = XiaomiScriptedPeer.hex("080810015209120701020304fc0400")
        XiaomiProto.parse(literal).use { command ->
            XiaomiHistorySnapshot(command, XiaomiHistoryIndex.TODAY).use { snapshot ->
                assertArrayEquals(XiaomiHistoryFixtures.id, snapshot.details().single())
            }
        }
    }

    @Test fun `одинаковые ID разных источников не подтверждают retention`() {
        val past = XiaomiHistoryFixtures.index(XiaomiHistoryFixtures.id).use { XiaomiHistorySnapshot(it) }
        val today = XiaomiHistoryFixtures.index(XiaomiHistoryFixtures.id, XiaomiHistoryIndex.TODAY)
            .use { XiaomiHistorySnapshot(it, XiaomiHistoryIndex.TODAY) }
        past.use { today.use { assertFalse(past.unchanged(today)) } }
    }
}
