package ru.kardiomonitor.features.band.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kardiomonitor.features.band.domain.BandLink
import ru.kardiomonitor.features.band.domain.BatteryLevel
import ru.kardiomonitor.features.band.fakes.FakeBandLink
import ru.kardiomonitor.features.band.fakes.FakeBandRepository
import ru.kardiomonitor.features.band.fakes.FakeBatteryLog

class BandSessionLifecycleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val link = FakeBandLink()
    private val repository = FakeBandRepository()
    private val batteryLog = FakeBatteryLog()

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `сворачивание во время фоновой проверки не закрывает занятый общий сеанс`() = runBlocking {
        val connection = session()
        connection.withSession(isBackground = true) {
            connection.closeIfIdle()
            assertTrue(link.isOpen)
        }
        assertFalse(link.isOpen)
    }

    @Test
    fun `сворачивание ждёт завершения действия на открытом экране`() = runBlocking {
        val connection = session()
        connection.connect()
        connection.withOpenSession {
            connection.closeIfIdle()
            assertTrue(link.isOpen)
        }
        assertFalse(link.isOpen)
    }

    @Test
    fun `отмена подключения во время GET закрывает транспорт и не сохраняет заряд`() {
        val waiting = object : BandLink by link {
            override suspend fun readBattery(): BatteryLevel = awaitCancellation()
        }
        val connection = session(waiting)
        connection.connect()
        assertTrue(link.isOpen)
        connection.closeIfIdle()
        assertFalse(link.isOpen)
        assertFalse(connection.state.value.isConnected)
        assertTrue(batteryLog.records.isEmpty())
    }

    @Test
    fun `отмена фонового GET закрывает сеанс и не оставляет подключённое состояние`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val waiting = object : BandLink by link {
            override suspend fun readBattery(): BatteryLevel {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val connection = session(waiting)
        val job = launch(Dispatchers.Unconfined) { connection.withSession(isBackground = true) {} }
        entered.await()
        job.cancelAndJoin()
        assertFalse(link.isOpen)
        assertFalse(connection.state.value.isConnected)
        assertTrue(batteryLog.records.isEmpty())
    }

    @Test
    fun `отвязка во время GET не оставляет заряд чужой привязки`() = runBlocking {
        val forgetting = object : BandLink by link {
            override suspend fun readBattery(): BatteryLevel {
                repository.forget()
                return BatteryLevel(78)
            }
        }
        val connection = session(forgetting)
        connection.connect()
        assertFalse(connection.state.value.isConnected)
        assertTrue(batteryLog.records.isEmpty())
    }

    @Test
    fun `ожидающая работа проверяет привязку после освобождения общего сеанса до подключения`() = runBlocking {
        val connection = session()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Unconfined) {
            connection.withSession(isBackground = true) { entered.complete(Unit); release.await() }
        }
        entered.await()
        var allowed = true
        val second = launch(Dispatchers.Unconfined) {
            assertEquals(null, connection.withSessionIf(true, { allowed }) { error("устаревшая работа") })
        }
        allowed = false
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, link.openCount)
    }

    private fun session(bandLink: BandLink = link) = SingleBandConnection(bandLink, repository, batteryLog, scope)
}
