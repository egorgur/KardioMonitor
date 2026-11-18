package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiHistoryVerificationTest {
    private val address = "00:11:22:33:44:55"
    private fun peer(scenario: String = "valid") = XiaomiScriptedPeer().apply { historyEnabled = true; historyScenario = scenario }
    private fun client(peer: XiaomiScriptedPeer) = XiaomiReadOnlyClient({ peer }, 37, 1_000, 200)
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block() } catch (_: IOException) { return }
        throw AssertionError("Ожидался отказ")
    }
    @Test fun `проверка одного exact файла возвращает только кодировки без права клинического импорта`() = runBlocking {
        val peer = peer()
        val client = client(peer)
        client.open(address, peer.secret)
        val result = client.verifyPastHistory(0)
        assertEquals(1, result.exactTransfers)
        assertEquals(2, result.candidateFiles)
        assertEquals(1, result.omittedFiles)
        assertEquals(XiaomiHistoryAggregate(2, 2, 1, 0, 2, 0, 2), result.aggregate)
        assertTrue(result.retentionUnchanged)
        assertNull(result.repeatedBytesIdentical)
        assertFalse(result.timeAndValuesVerified)
        assertFalse(result.fullCoverageVerified)
        assertFalse(result.clinicalImportAllowed)
        assertEquals("XiaomiHistoryVerification", result.toString())
        assertEquals(listOf(1L to 26L, 1L to 27L, 2L to 2L, 8L to 2L, 8L to 3L, 8L to 2L), peer.commands)
        assertEquals(73, client.readBattery())
        client.close()
        assertEquals(1, peer.closeCount)
    }
    @Test fun `независимый эталон вне файла не превращает успешный repeat в подтверждение времени`() = runBlocking {
        val peer = peer()
        val client = client(peer)
        client.open(address, peer.secret)
        val result = client.verifyPastHistoryReference(0, XiaomiHistoryReference(1_700_000_040L, 25_200, 72, null))
        assertEquals(2, result.exactTransfers)
        assertEquals(true, result.repeatedBytesIdentical)
        assertTrue(result.retentionUnchanged)
        assertEquals(false, result.referenceMatched)
        assertEquals(false, result.referenceComparison?.recordLocated)
        assertNull(result.referenceComparison?.heartRateMatches)
        assertNull(result.referenceComparison?.stepsMatch)
        assertFalse(result.timeAndValuesVerified)
        assertFalse(result.clinicalImportAllowed)
        assertFalse(peer.commands.contains(8L to 5L))
        client.close()
    }
    @Test fun `history кадры не исчерпывают отдельный обычный бюджет после успешного чтения`() = runBlocking {
        val peer = peer()
        val client = client(peer)
        client.open(address, peer.secret)
        repeat(249) { assertEquals(73, client.readBattery()) }
        assertTrue(client.verifyPastHistory(0, repeat = true).retentionUnchanged)
        assertEquals(73, client.readBattery())
        assertEquals(XiaomiHeartRateSettings(false, 10), client.readHeartRateSettings())
        client.close()
    }
    @Test fun `два exact чтения используют одни семь байт и сравниваются в памяти без ACK`() = runBlocking {
        val peer = peer()
        val client = client(peer)
        client.open(address, peer.secret)
        val result = client.verifyPastHistory(0, repeat = true)
        assertEquals(2, result.exactTransfers)
        assertEquals(true, result.repeatedBytesIdentical)
        assertEquals(true, result.repeatedAggregatesConsistent)
        assertTrue(result.retentionUnchanged)
        assertEquals(2, peer.historyFetches)
        assertArrayEquals(peer.historyRequestedIds[0], peer.historyRequestedIds[1])
        assertArrayEquals(XiaomiHistoryFixtures.id, peer.historyRequestedIds[0])
        assertFalse(peer.commands.contains(8L to 5L))
        assertFalse(result.clinicalImportAllowed)
        client.close()
    }
    @Test fun `одинаковые агрегаты не скрывают изменение байтов между передачами`() = runBlocking {
        val peer = peer("changed")
        val client = client(peer)
        client.open(address, peer.secret)
        val result = client.verifyPastHistory(0, repeat = true)
        assertEquals(false, result.repeatedBytesIdentical)
        assertEquals(true, result.repeatedAggregatesConsistent)
        assertTrue(result.retentionUnchanged)
        client.close()
    }
    @Test fun `полный multiset retention обнаруживает потерю и дубли независимо от выбранного файла`() = runBlocking {
        for (scenario in listOf("retention_changed", "retention_duplicate")) {
            val peer = peer(scenario)
            val client = client(peer)
            client.open(address, peer.secret)
            assertFalse(client.verifyPastHistory(0).retentionUnchanged)
            client.close()
        }
    }
    @Test fun `неподдерживаемые повреждённые и чужие файлы не превращаются в нулевой успех`() = runBlocking {
        for (scenario in listOf("crc", "padding", "unsupported", "plaintext", "missing", "bytes", "invalid_index", "retention_partial")) {
            val peer = peer(scenario)
            val client = client(peer)
            var losses = 0
            client.onConnectionLost = { losses++ }
            client.open(address, peer.secret)
            rejects { client.verifyPastHistory(0, repeat = true) }
            assertEquals(1, peer.closeCount)
            assertEquals(1, losses)
            assertTrue(peer.historyFetches <= 2)
            assertFalse(peer.commands.contains(8L to 5L))
            rejects { client.readBattery() }
        }
    }
    @Test fun `начатый до запроса exact файл карантинируется и не заменяет свежий target`() = runBlocking {
        val peer = peer("prestarted")
        val client = client(peer)
        client.open(address, peer.secret)
        assertEquals(1, client.verifyPastHistory(0).exactTransfers)
        client.close()
    }
    @Test fun `отсутствующий offset отклоняется до fetch а неверный диапазон до транспорта`() = runBlocking {
        val peer = peer()
        val client = client(peer)
        client.open(address, peer.secret)
        for (offset in listOf(-1, 256)) {
            try { client.verifyPastHistory(offset); throw AssertionError("Ожидался отказ") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, peer.historyIndices)
        rejects { client.verifyPastHistory(2) }
        assertEquals(0, peer.historyFetches)
        assertEquals(1, peer.closeCount)
    }
    @Test fun `таймаут и отмена history verification закрывают сокет и не возвращают partial как success`() = runBlocking {
        val timeoutPeer = peer("timeout")
        val timeoutClient = client(timeoutPeer)
        timeoutClient.open(address, timeoutPeer.secret)
        rejects { timeoutClient.verifyPastHistory(0) }
        assertEquals(1, timeoutPeer.closeCount)
        val peer = peer("timeout")
        val client = client(peer)
        client.open(address, peer.secret)
        val task = async { client.verifyPastHistory(0, repeat = true) }
        withTimeout(2_000) { while (peer.reading.count > 0) delay(1) }
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(1, peer.closeCount)
        rejects { client.readBattery() }
    }
}
