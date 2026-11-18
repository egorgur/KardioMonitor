package ru.kardiomonitor.features.band.statusrefresh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.connection.SingleBandConnection
import ru.kardiomonitor.features.band.domain.Band
import ru.kardiomonitor.features.band.domain.BandProtocol
import ru.kardiomonitor.features.band.domain.backgroundWorkIdentity
import ru.kardiomonitor.features.band.fakes.FakeBandConnection
import ru.kardiomonitor.features.band.fakes.FakeBandLink
import ru.kardiomonitor.features.band.fakes.FakeBandRepository
import ru.kardiomonitor.features.band.fakes.FakeBatteryLog
import java.time.Instant

class BandStatusRefreshTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repository = FakeBandRepository()
    private val link = FakeBandLink()
    private val battery = FakeBatteryLog()
    private val connection = SingleBandConnection(link, repository, battery, scope)
    private var allowed = true
    private val refresh = BandStatusRefresh(connection, repository) { allowed }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `фоновая проверка Band 9 читает заряд и закрывается без SET`() = runBlocking {
        val identity = selectBand9()
        assertTrue(refresh.refresh(identity))
        assertEquals(1, link.openCount)
        assertEquals(78, battery.records.single().percent)
        assertFalse(link.isOpen)
        assertTrue(link.writtenIntervals.isEmpty())
    }

    @Test
    fun `без разрешения или CDM сеанс не открывается`() = runBlocking {
        val identity = selectBand9()
        allowed = false
        assertFalse(refresh.refresh(identity))
        assertEquals(0, link.openCount)
    }

    @Test
    fun `устаревшая работа не подключает заменённую привязку или Band 6`() = runBlocking {
        val identity = selectBand9()
        val band = requireNotNull(repository.band.value)
        repository.save(Band.paired(band.credentials, Instant.parse("2026-11-13T01:00:00Z")))
        assertFalse(refresh.refresh(identity))
        repository.save(Band.paired(band.credentials.copy(protocol = BandProtocol.MiBand6Ble), band.pairedAt ?: Instant.EPOCH))
        assertFalse(refresh.refresh(identity))
        repository.forget()
        assertFalse(refresh.refresh(identity))
        assertEquals(0, link.openCount)
    }

    @Test
    fun `отмена WorkManager не превращается в успешную проверку`() = runBlocking {
        val identity = selectBand9()
        val cancelled = object : BandConnection by FakeBandConnection() {
            override suspend fun <T> withSessionIf(
                isBackground: Boolean,
                isAllowed: () -> Boolean,
                block: suspend () -> T,
            ): T? = throw CancellationException("отмена")
        }
        try {
            BandStatusRefresh(cancelled, repository) { true }.refresh(identity)
            fail("отмена должна выйти к WorkManager")
        } catch (_: CancellationException) {
            assertEquals(0, link.openCount)
        }
    }

    private fun selectBand9(): String {
        val old = requireNotNull(repository.band.value)
        val band = Band.paired(old.credentials.copy(protocol = BandProtocol.XiaomiBand9Spp), Instant.EPOCH)
        repository.save(band)
        return band.backgroundWorkIdentity
    }
}
