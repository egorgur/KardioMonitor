package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiHistoryReferenceSampleTest {
    private val epoch = 1_700_000_040L
    private val identifier = XiaomiHistoryFixtures.id.also { id ->
        repeat(4) { id[it] = (epoch ushr (8 * it)).toByte() }
        id[4] = 28
    }
    private val reference = XiaomiHistoryReference(epoch, 25_200, 70, 1)
    private fun rejects(block: () -> Unit) {
        try { block() } catch (_: IOException) { return }
        throw AssertionError("Ожидался отказ")
    }

    @Test fun `образец сравнивается только после двух exact чтений а закрытие стирает удержанный массив`() {
        val sample = XiaomiHistoryReferenceSample()
        XiaomiHistoryTransfer(repeat = true, sample = sample).use { transfer ->
            transfer.request(identifier)
            val file = XiaomiHistoryFixtures.file(identifier)
            transfer.accept(XiaomiHistoryFixtures.chunk(file), false)
            rejects { sample.compare(reference) }
            transfer.repeatRequest()
            transfer.accept(XiaomiHistoryFixtures.chunk(file), false)
            file.fill(0)
            assertTrue(sample.verified)
            assertTrue(sample.compare(reference).matches)
            assertFalse(sample.compare(reference.copy(heartRate = 71)).matches)
            assertFalse(sample.compare(reference.copy(epochSeconds = epoch - 60)).recordLocated)
        }
        val field = XiaomiHistoryReferenceSample::class.java.getDeclaredField("bytes").apply { isAccessible = true }
        val held = field.get(sample) as ByteArray
        assertTrue(held.any { it != 0.toByte() })
        sample.close()
        assertTrue(held.all { it == 0.toByte() })
        assertTrue(sample.cleared)
        rejects { sample.compare(reference) }
    }

    @Test fun `quarantine и ранее начатый файл не создают reverse образец`() {
        XiaomiHistoryReferenceSample().use { sample ->
            XiaomiHistoryTransfer(repeat = true, sample = sample).use { transfer ->
                val file = XiaomiHistoryFixtures.file(identifier)
                transfer.accept(XiaomiHistoryFixtures.chunk(file, total = 2, number = 1, end = 15), true)
                transfer.request(identifier)
                transfer.accept(XiaomiHistoryFixtures.chunk(file, total = 2, number = 2, start = 15), false)
                val foreign = XiaomiHistoryFixtures.file(identifier.copyOf().also { it[0] = 99 })
                transfer.accept(XiaomiHistoryFixtures.chunk(foreign), false)
                assertEquals(0, transfer.exactTransfers)
                assertFalse(sample.verified)
                rejects { sample.compare(reference) }
            }
        }
    }

    @Test fun `CRC ошибка и изменившийся repeat не позволяют сравнить неполный образец`() {
        for (corruptCrc in listOf(true, false)) {
            XiaomiHistoryReferenceSample().use { sample ->
                XiaomiHistoryTransfer(repeat = true, sample = sample).use { transfer ->
                    transfer.request(identifier)
                    val file = XiaomiHistoryFixtures.file(identifier)
                    if (!corruptCrc) {
                        transfer.accept(XiaomiHistoryFixtures.chunk(file), false)
                        transfer.repeatRequest()
                        file[16] = 71
                        XiaomiHistoryFixtures.crc(file)
                    } else file[file.lastIndex] = (file.last().toInt() xor 1).toByte()
                    rejects { transfer.accept(XiaomiHistoryFixtures.chunk(file), false) }
                    assertFalse(sample.verified)
                    rejects { sample.compare(reference) }
                }
            }
        }
    }

    @Test fun `reverse today закрывает клиент до независимого сравнения и не открывает clinical gates`() = runBlocking {
        val peer = XiaomiScriptedPeer().apply { historyEnabled = true; historyIndexSource = XiaomiHistoryIndex.TODAY }
        val client = XiaomiReadOnlyClient({ peer }, 37, 1_000, 200)
        XiaomiHistoryReferenceSample().use { sample ->
            client.open("00:11:22:33:44:55", peer.secret)
            val result = client.captureHistoryReferenceSample(0, sample, XiaomiHistoryIndex.TODAY)
            client.close()
            val commands = peer.commands.toList()
            assertTrue(sample.verified)
            assertEquals(2, result.exactTransfers)
            assertTrue(result.retentionUnchanged)
            assertEquals(true, result.repeatedBytesIdentical)
            assertFalse(result.clinicalImportAllowed)
            assertFalse(result.timeAndValuesVerified)
            assertFalse(result.fullCoverageVerified)
            assertEquals(listOf(1L to 26L, 1L to 27L, 2L to 2L, 8L to 1L, 8L to 3L, 8L to 3L, 8L to 1L), commands)
            sample.compare(reference)
            assertEquals(commands, peer.commands)
            assertEquals(1, peer.closeCount)
        }
    }
}
