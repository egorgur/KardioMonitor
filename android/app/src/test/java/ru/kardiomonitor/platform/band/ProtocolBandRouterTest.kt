package ru.kardiomonitor.platform.band

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.kardiomonitor.features.band.domain.Band
import ru.kardiomonitor.features.band.domain.BandLink
import ru.kardiomonitor.features.band.domain.BandProtocol
import ru.kardiomonitor.features.band.domain.ConnectionProblem
import ru.kardiomonitor.features.band.domain.HeartRateMeasurementInterval
import ru.kardiomonitor.features.band.domain.OpenResult
import ru.kardiomonitor.features.band.fakes.FakeBandLink
import ru.kardiomonitor.features.band.fakes.FakeBandRepository
import ru.kardiomonitor.features.monitoring.domain.ActivitySource
import ru.kardiomonitor.features.monitoring.fakes.FakeActivitySource
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime

class ProtocolBandRouterTest {
    private val repository = FakeBandRepository()
    private val band6 = FakeDriver()
    private val band9 = FakeDriver()
    private val router = ProtocolBandRouter(repository, band6, band9)

    @Test
    fun `старая привязка отправляется только драйверу Band 6`() = runBlocking {
        router.open(requireNotNull(repository.band.value).credentials) { }
        assertEquals(1, band6.link.openCount)
        assertEquals(0, band9.link.openCount)
        assertNull(router.monitoringUnavailableReason)
    }

    @Test
    fun `SPP не откатывается к BLE при отказе авторизации`() = runBlocking {
        selectBand9()
        band9.link.refusal = ConnectionProblem.AuthRejected("неверный ключ")
        val result = router.open(requireNotNull(repository.band.value).credentials) { }
        assertTrue(result is OpenResult.Refused)
        assertEquals(1, band9.link.openCount)
        assertEquals(0, band6.link.openCount)
        expectIo { router.readBattery() }
    }

    @Test
    fun `смена протокола закрывает прежний сеанс и игнорирует его поздний callback`() = runBlocking {
        router.open(requireNotNull(repository.band.value).credentials) { }
        selectBand9()
        router.open(requireNotNull(repository.band.value).credentials) { }
        band6.link.loseConnection()
        assertEquals(78, router.readBattery()?.percent)
        assertTrue(!band6.link.isOpen && band9.link.isOpen)
    }

    @Test
    fun `недоказанные операции не доходят до Xiaomi драйвера`() = runBlocking {
        selectBand9()
        router.open(requireNotNull(repository.band.value).credentials) { }
        assertNotNull(router.monitoringUnavailableReason)
        expectIo { router.minutesSince(ZonedDateTime.now()) { } }
        expectIo { router.liveHeartRate() }
        expectIo { router.stepsToday() }
        expectIo { router.writeHeartRateMeasurementInterval(HeartRateMeasurementInterval.Off) }
        assertTrue(band9.source.requestedStarts.isEmpty())
        assertTrue(band9.link.writtenIntervals.isEmpty())
    }

    @Test
    fun `отмена подключения закрывает выбранный драйвер без fallback`() = runBlocking {
        val cancelled = object : BandDriver by band9 {
            override suspend fun open(
                credentials: ru.kardiomonitor.features.band.domain.BandCredentials,
                onStage: (ru.kardiomonitor.features.band.domain.ConnectionStage) -> Unit,
            ): OpenResult {
                band9.open(credentials, onStage)
                throw CancellationException("отмена")
            }
        }
        selectBand9()
        val routed = ProtocolBandRouter(repository, band6, cancelled)
        try {
            routed.open(requireNotNull(repository.band.value).credentials) { }
            fail("отмена должна выйти наружу")
        } catch (_: CancellationException) {
            assertTrue(!band9.link.isOpen)
            assertEquals(0, band6.link.openCount)
        }
    }

    @Test
    fun `изменение сохранённого протокола не открывает запрещённые операции активного драйвера`() = runBlocking {
        val legacy = requireNotNull(repository.band.value)
        selectBand9()
        router.open(requireNotNull(repository.band.value).credentials) { }
        repository.save(legacy)
        expectIo { router.minutesSince(ZonedDateTime.now()) { } }
        expectIo { router.writeHeartRateMeasurementInterval(HeartRateMeasurementInterval.Off) }
        assertTrue(band9.source.requestedStarts.isEmpty())
        assertTrue(band9.link.writtenIntervals.isEmpty())
        assertTrue(!band9.link.isOpen)
    }

    private fun selectBand9() {
        val credentials = requireNotNull(repository.band.value).credentials.copy(protocol = BandProtocol.XiaomiBand9Spp)
        repository.save(Band.paired(credentials, Instant.parse("2026-11-12T10:00:00Z")))
    }

    private suspend fun expectIo(block: suspend () -> Unit) {
        try {
            block()
            fail("непроверенная операция должна быть недоступна")
        } catch (_: IOException) {
            // Отказ без отправки неподтверждённых команд.
        }
    }

    private class FakeDriver(
        val link: FakeBandLink = FakeBandLink(),
        val source: FakeActivitySource = FakeActivitySource(),
    ) : BandDriver, BandLink by link, ActivitySource by source
}
