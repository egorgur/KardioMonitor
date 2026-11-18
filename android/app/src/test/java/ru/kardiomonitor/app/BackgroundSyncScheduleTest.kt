package ru.kardiomonitor.app

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kardiomonitor.features.band.domain.Band
import ru.kardiomonitor.features.band.domain.BandProtocol
import ru.kardiomonitor.features.band.domain.backgroundWorkIdentity
import ru.kardiomonitor.features.band.fakes.FakeBandRepository
import ru.kardiomonitor.features.monitoring.fakes.FakeSyncSettings
import java.time.Instant

class BackgroundSyncScheduleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repository = FakeBandRepository()
    private val settings = FakeSyncSettings()
    private val calls = mutableListOf<String>()

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `смена протокола выключение фона и отвязка снимают несовместимую работу`() {
        startSchedule()
        assertEquals(listOf("status-cancel", "history-schedule"), calls)
        calls.clear()
        val old = requireNotNull(repository.band.value)
        val band9 = Band.paired(old.credentials.copy(protocol = BandProtocol.XiaomiBand9Spp), Instant.EPOCH)
        repository.save(band9)
        assertEquals(listOf("history-cancel", "status:${band9.backgroundWorkIdentity}"), calls)
        calls.clear()
        settings.setBackgroundSyncEnabled(false)
        assertEquals(listOf("history-cancel", "status-cancel"), calls)
        calls.clear()
        settings.setBackgroundSyncEnabled(true)
        assertEquals(listOf("history-cancel", "status:${band9.backgroundWorkIdentity}"), calls)
        calls.clear()
        repository.forget()
        assertEquals(listOf("history-cancel", "status-cancel"), calls)
    }

    @Test
    fun `новый процесс восстанавливает read-only расписание из сохранённого Band 9`() {
        val old = requireNotNull(repository.band.value)
        repository.save(Band.paired(old.credentials.copy(protocol = BandProtocol.XiaomiBand9Spp), Instant.EPOCH))
        startSchedule()
        assertEquals("history-cancel", calls.first())
        assertTrue(calls.last().startsWith("status:xiaomi-band9-spp:"))
        assertTrue(calls.none { it == "history-schedule" })
        assertEquals(null, settings.lastSync.value)
    }

    private fun startSchedule() {
        BackgroundSyncSchedule(
            ContextWrapper(null), repository, settings, scope,
            historySchedule = { calls += "history-schedule" },
            historyCancel = { calls += "history-cancel" },
            statusSchedule = { calls += "status:$it" },
            statusCancel = { calls += "status-cancel" },
        ).start()
    }
}
