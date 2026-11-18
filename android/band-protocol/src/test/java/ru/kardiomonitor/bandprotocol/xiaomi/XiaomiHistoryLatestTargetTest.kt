package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class XiaomiHistoryLatestTargetTest {
    private val epoch = 1_700_000_040L
    private fun id(time: Long, zone: Int = 28): ByteArray = ByteArray(7).also { bytes ->
        repeat(4) { bytes[it] = (time ushr (8 * it)).toByte() }
        bytes[4] = zone.toByte(); bytes[5] = 4
    }
    private fun rejects(block: () -> Unit) {
        try { block() } catch (_: IOException) { return }
        throw AssertionError("Ожидался отказ")
    }

    @Test fun `latest выбирает максимальное supported начало а не крайний offset или будущий ID`() {
        val older = id(epoch - 600)
        val newest = id(epoch)
        val future = id(epoch + 60)
        val wrongZone = id(epoch + 60, 0)
        val unaligned = id(epoch + 1)
        val unsigned = id(0x80000000)
        val target = XiaomiHistoryLatestTarget(epoch, 25_200)
        for (details in listOf(listOf(older, newest, future, wrongZone, unaligned, unsigned),
            listOf(unsigned, wrongZone, newest, future, older, unaligned))) {
            assertArrayEquals(newest, details[target.select(details)])
        }
    }

    @Test fun `пустой неподходящий или неоднозначный latest отказывает до fetch без fallback`() {
        val target = XiaomiHistoryLatestTarget(epoch, 25_200)
        for (details in listOf(emptyList(), listOf(id(epoch + 60)), listOf(id(epoch, 0)),
            listOf(id(epoch + 1)), listOf(id(0x80000000)))) {
            var fetches = 0
            val identifiers = details.fold(ByteArray(0)) { bytes, next -> bytes + next }
            XiaomiHistoryTransfer(repeat = true).use { transfer ->
                rejects {
                    XiaomiHistoryRead.read(0, true, transfer, { XiaomiHistoryFixtures.index(identifiers) },
                        { target.select(it) }) { fetches++ }
                }
            }
            assertEquals(0, fetches)
        }
        rejects { target.select(listOf(id(epoch), id(epoch))) }
        rejects { XiaomiHistoryLatestTarget(-1, 25_200) }
        rejects { XiaomiHistoryLatestTarget(epoch, 1) }
        rejects { XiaomiHistoryLatestTarget(epoch, 60_000) }
        rejects { target.select(listOf(ByteArray(6))) }
    }

    @Test fun `клиент передаёт latest selector до fetch и закрывается при неподходящем индексе`() = runBlocking {
        val peer = XiaomiScriptedPeer().apply { historyEnabled = true; historyIndexSource = XiaomiHistoryIndex.TODAY }
        val client = XiaomiReadOnlyClient({ peer }, 37, 1_000, 200)
        XiaomiHistoryReferenceSample().use { sample ->
            client.open("00:11:22:33:44:55", peer.secret)
            try {
                client.captureHistoryReferenceSample(0, sample, XiaomiHistoryIndex.TODAY, XiaomiHistoryLatestTarget(epoch, 25_200))
                throw AssertionError("Ожидался отказ")
            } catch (_: IOException) { }
            assertEquals(0, peer.historyFetches)
            assertEquals(listOf(1L to 26L, 1L to 27L, 2L to 2L, 8L to 1L), peer.commands)
            assertEquals(1, peer.closeCount)
        }
    }

    @Test fun `latest repeat запрашивает только один выбранный ID и не продлевает обход после него`() {
        val older = id(epoch - 600)
        val latest = id(epoch)
        val target = XiaomiHistoryLatestTarget(epoch + 600, 25_200)
        var fetches = 0
        XiaomiHistoryReferenceSample().use { sample ->
            XiaomiHistoryTransfer(repeat = true, sample = sample).use { transfer ->
                val result = XiaomiHistoryRead.read(0, true, transfer,
                    { XiaomiHistoryFixtures.index(older + latest) }, { target.select(it) }) { requested ->
                    fetches++
                    assertArrayEquals(latest, requested)
                    transfer.accept(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file(latest)), false)
                }
                assertEquals(2, fetches)
                assertEquals(true, result.repeatedBytesIdentical)
                assertEquals(true, result.retentionUnchanged)
                assertEquals(XiaomiHistoryReferenceSample.WindowRelation.INSIDE,
                    sample.windowRelation(XiaomiHistoryReference(epoch, 25_200, 70, null)))
            }
        }
    }
}
