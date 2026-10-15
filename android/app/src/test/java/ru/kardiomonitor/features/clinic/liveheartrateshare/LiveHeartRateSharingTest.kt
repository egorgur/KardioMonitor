package ru.kardiomonitor.features.clinic.liveheartrateshare

import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.features.clinic.domain.LiveHeartRateReport
import ru.kardiomonitor.features.clinic.fakes.FakeClinicServer
import ru.kardiomonitor.features.clinic.fakes.FakeServerRegistrationRepository
import ru.kardiomonitor.features.monitoring.data.InMemoryLiveHeartRateFeed
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateReading
import java.time.Instant

class LiveHeartRateSharingTest {
    private val server = FakeClinicServer()
    private val feed = InMemoryLiveHeartRateFeed()
    private val at = Instant.parse("2026-10-15T10:00:00Z")

    /** Даёт подписчику подписаться и обработать опубликованное: SharedFlow без replay теряет ранние значения. */
    private suspend fun settle() = repeat(SETTLE_YIELDS) { yield() }

    @Test
    fun `пульс сейчас зарегистрированного телефона уходит на сервер`() = runBlocking {
        val registrations = FakeServerRegistrationRepository(FakeClinicServer.REGISTRATION)
        LiveHeartRateSharing(server, registrations, feed, this).start()
        settle()

        feed.publish(LiveHeartRateReading(at, HeartRate(88)))
        withTimeout(1_000) { while (server.liveHeartRate.isEmpty()) yield() }

        assertEquals(listOf(LiveHeartRateReport(at, 88)), server.liveHeartRate)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `без регистрации пульс никуда не отправляется`() = runBlocking {
        LiveHeartRateSharing(server, FakeServerRegistrationRepository(), feed, this).start()
        settle()

        feed.publish(LiveHeartRateReading(at, HeartRate(88)))
        settle()

        assertEquals(emptyList<LiveHeartRateReport>(), server.liveHeartRate)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `сбой сети не останавливает передачу следующих значений`() = runBlocking {
        LiveHeartRateSharing(server, FakeServerRegistrationRepository(FakeClinicServer.REGISTRATION), feed, this).start()
        settle()

        server.failure = "нет сети"
        feed.publish(LiveHeartRateReading(at, HeartRate(88)))
        settle()
        server.failure = null
        feed.publish(LiveHeartRateReading(at.plusSeconds(3), HeartRate(90)))
        withTimeout(1_000) { while (server.liveHeartRate.isEmpty()) yield() }

        assertEquals(listOf(90), server.liveHeartRate.map { it.bpm })
        coroutineContext.cancelChildren()
    }

    private companion object {
        const val SETTLE_YIELDS = 20
    }
}
