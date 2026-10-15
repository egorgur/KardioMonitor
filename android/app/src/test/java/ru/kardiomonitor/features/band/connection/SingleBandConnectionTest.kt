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
import ru.kardiomonitor.features.band.domain.BatteryLevel
import ru.kardiomonitor.features.band.domain.ConnectionProblem
import ru.kardiomonitor.features.band.domain.ConnectionStage
import ru.kardiomonitor.features.band.domain.HeartRateMeasurementInterval
import ru.kardiomonitor.features.band.fakes.FakeBandLink
import ru.kardiomonitor.features.band.fakes.FakeBandRepository
import ru.kardiomonitor.features.band.fakes.FakeBatteryLog

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
    fun `новая частота пульса при открытом соединении записывается сразу`() = runBlocking {
        connection.connect()
        val band = repository.band.value ?: error("браслет привязан в фейке")

        repository.save(band.withHeartRateMeasurementInterval(HeartRateMeasurementInterval.Off))

        assertEquals(listOf<HeartRateMeasurementInterval>(HeartRateMeasurementInterval.Off), link.writtenIntervals)
    }

    @Test
    fun `браслет не найден — подключение по кнопке показывает причину`() {
        link.refusal = ConnectionProblem.NotFound

        connection.connect()

        assertEquals(ConnectionProblem.NotFound, connection.state.value.problem)
        assertEquals(ConnectionStage.Disconnected, connection.state.value.stage)
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
}
