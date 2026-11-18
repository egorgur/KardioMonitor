package ru.kardiomonitor.features.band.connection

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
import java.io.IOException
import ru.kardiomonitor.features.band.api.SessionUnavailableException
import ru.kardiomonitor.features.band.domain.AuthKey
import ru.kardiomonitor.features.band.domain.Band
import ru.kardiomonitor.features.band.domain.BandAddress
import ru.kardiomonitor.features.band.domain.BandCredentials
import ru.kardiomonitor.features.band.domain.BatteryLevel
import ru.kardiomonitor.features.band.domain.ConnectionProblem
import ru.kardiomonitor.features.band.domain.ConnectionStage
import ru.kardiomonitor.features.band.domain.HeartRateMeasurementInterval
import ru.kardiomonitor.features.band.fakes.FakeBandLink
import ru.kardiomonitor.features.band.fakes.FakeBandRepository
import ru.kardiomonitor.features.band.fakes.FakeBatteryLog
import java.time.Instant

/** Правила общего сеанса связи — ADR-0003. Корутины выполняются сразу (Unconfined). */
class SingleBandConnectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val link = FakeBandLink()
    private val repository = FakeBandRepository()
    private val batteryLog = FakeBatteryLog()
    private val connection = SingleBandConnection(link, repository, batteryLog, scope)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `фоновый сеанс закрывает соединение после сценария`() = runBlocking {
        val wasOpen = connection.withSession(isBackground = true) { link.isOpen }

        assertTrue(wasOpen)
        assertFalse(link.isOpen)
        assertEquals(ConnectionStage.Disconnected, connection.state.value.stage)
    }

    @Test
    fun `сеанс использует соединение, открытое пользователем, и не закрывает его`() = runBlocking {
        connection.connect()

        connection.withSession(isBackground = true) { }

        assertEquals(1, link.openCount)
        assertTrue(link.isOpen)
        assertEquals(ConnectionStage.Connected, connection.state.value.stage)
    }

    @Test
    fun `при подключении сохраняется заряд и записывается выбранная частота пульса`() = runBlocking {
        val band = repository.band.value ?: error("браслет привязан в фейке")
        repository.save(band.withHeartRateMeasurementInterval(HeartRateMeasurementInterval.Every(10)))

        connection.connect()

        assertEquals(listOf(BatteryLevel(78)), batteryLog.records)
        assertEquals(listOf<HeartRateMeasurementInterval>(HeartRateMeasurementInterval.Every(10)), link.writtenIntervals)
        assertEquals(HeartRateMeasurementInterval.Every(10), connection.state.value.appliedInterval)
    }

    @Test
    fun `отсутствующий заряд не записывается как ноль и не мешает read-only сеансу`() {
        val unknownBattery = object : ru.kardiomonitor.features.band.domain.BandLink by link {
            override suspend fun readBattery(): BatteryLevel? = null
        }
        val session = SingleBandConnection(unknownBattery, repository, batteryLog, scope)

        session.connect()

        assertTrue(session.state.value.isConnected)
        assertEquals(null, session.state.value.battery)
        assertTrue(batteryLog.records.isEmpty())
    }

    @Test
    fun `Smart и отсутствующий disabled сохраняются без превращения в Off`() {
        val settings = ru.kardiomonitor.features.band.domain.ReportedHeartRateSettings(null, 0)
        val readOnly = object : ru.kardiomonitor.features.band.domain.BandLink by link {
            override suspend fun readHeartRateSettings() = settings
        }
        val session = SingleBandConnection(readOnly, repository, batteryLog, scope)

        session.connect()

        assertEquals(settings, session.state.value.reportedHeartRateSettings)
        assertEquals(null, session.state.value.appliedInterval)
        assertTrue(link.writtenIntervals.isEmpty())
    }

    @Test
    fun `новая частота пульса при открытом соединении записывается сразу`() = runBlocking {
        connection.connect()
        val band = repository.band.value ?: error("браслет привязан в фейке")

        repository.save(band.withHeartRateMeasurementInterval(HeartRateMeasurementInterval.Off))

        assertEquals(listOf<HeartRateMeasurementInterval>(HeartRateMeasurementInterval.Off), link.writtenIntervals)
    }

    @Test
    fun `Band 9 не получает сохранённый legacy интервал при подключении и изменении настройки`() {
        val old = requireNotNull(repository.band.value)
        val credentials = old.credentials.copy(protocol = ru.kardiomonitor.features.band.domain.BandProtocol.XiaomiBand9Spp)
        repository.save(Band.restore(credentials, old.pairedAt, HeartRateMeasurementInterval.Off))

        connection.connect()
        repository.save(Band.restore(credentials, old.pairedAt, HeartRateMeasurementInterval.Every(5)))

        assertTrue(link.writtenIntervals.isEmpty())
        assertEquals(null, connection.state.value.appliedInterval)
    }

    @Test
    fun `браслет не найден — подключение по кнопке показывает причину`() {
        link.refusal = ConnectionProblem.NotFound

        connection.connect()

        assertEquals(ConnectionProblem.NotFound, connection.state.value.problem)
        assertEquals(ConnectionStage.Disconnected, connection.state.value.stage)
    }

    @Test
    fun `ошибка IO при открытии маршрутизируемого сеанса не покидает connect`() {
        val throwingLink = object : ru.kardiomonitor.features.band.domain.BandLink by link {
            override suspend fun open(
                credentials: BandCredentials,
                onStage: (ConnectionStage) -> Unit,
            ): ru.kardiomonitor.features.band.domain.OpenResult = throw IOException("подключение отменено")
        }
        val session = SingleBandConnection(throwingLink, repository, batteryLog, scope)

        session.connect()

        assertEquals(ConnectionProblem.LinkFailed("подключение отменено"), session.state.value.problem)
        assertFalse(session.state.value.isConnected)
    }

    @Test
    fun `непривязанный браслет — сеанс недоступен`() = runBlocking {
        repository.forget()
        try {
            connection.withSession(isBackground = true) { }
            fail("сеанс без привязанного браслета должен быть недоступен")
        } catch (e: SessionUnavailableException) {
            assertEquals(ConnectionProblem.NotPaired, e.problem)
        }
        assertEquals(0, link.openCount)
    }

    @Test
    fun `потеря соединения, открытого пользователем, показывается как проблема`() {
        connection.connect()

        link.loseConnection()

        assertEquals(ConnectionProblem.Lost, connection.state.value.problem)
        assertEquals(ConnectionStage.Disconnected, connection.state.value.stage)
    }

    @Test
    fun `свёрнутое приложение без измерения пульса закрывает соединение сразу`() {
        connection.connect()

        connection.closeIfIdle()

        assertFalse(link.isOpen)
    }

    @Test
    fun `свёрнутое приложение закрывает соединение, когда измерение пульса закончится`() = runBlocking {
        connection.connect()

        connection.holdOpen {
            connection.closeIfIdle()
            assertTrue(link.isOpen)
        }

        assertFalse(link.isOpen)
    }

    @Test
    fun `действие с экрана не открывает соединение, если браслет не подключён`() = runBlocking {
        try {
            connection.withOpenSession { }
            fail("закрытое соединение не должно открываться действием с экрана")
        } catch (e: SessionUnavailableException) {
            assertEquals(ConnectionProblem.NotConnected, e.problem)
        }
        assertEquals(0, link.openCount)
    }

    @Test
    fun `сбой связи сразу после авторизации закрывает соединение и называет причину`() {
        link.batteryFailure = IOException("браслет не ответил")

        connection.connect()

        assertEquals(ConnectionProblem.LinkFailed("браслет не ответил"), connection.state.value.problem)
        assertFalse(link.isOpen)
    }

    @Test
    fun `замена учётных данных закрывает прежний сеанс и подключает новый браслет`() {
        connection.connect()
        val replacement = Band.paired(
            BandCredentials(
                BandAddress("AA:BB:CC:DD:EE:FF"),
                AuthKey("ffeeddccbbaa99887766554433221100"),
            ),
            Instant.parse("2026-10-29T11:00:00Z"),
        )

        repository.save(replacement)

        assertEquals(2, link.openCount)
        assertTrue(link.isOpen)
        assertEquals(ConnectionStage.Connected, connection.state.value.stage)
    }
}
