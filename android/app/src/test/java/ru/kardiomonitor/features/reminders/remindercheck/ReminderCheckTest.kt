package ru.kardiomonitor.features.reminders.remindercheck

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.features.monitoring.api.ActivityHistoryRefresh
import ru.kardiomonitor.features.monitoring.domain.ActivityKind
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.Intensity
import ru.kardiomonitor.features.monitoring.domain.StepCount
import ru.kardiomonitor.features.monitoring.fakes.FakeActivityHistory
import ru.kardiomonitor.features.rehab.fakes.FakeRehabPlanRepository
import ru.kardiomonitor.features.reminders.domain.ReminderDecision
import ru.kardiomonitor.features.reminders.domain.ReminderKind
import ru.kardiomonitor.features.reminders.fakes.FakeReminderRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReminderCheckTest {
    private val now = Instant.parse("2026-11-02T11:00:00Z")

    @Test
    fun `перед проверкой телефон пытается получить свежую историю`() = runBlocking {
        val refresh = FakeActivityHistoryRefresh()
        val notification = FakeReminderNotification()
        val check = check(refresh, notification)

        check()

        assertEquals(1, refresh.calls)
        assertEquals(listOf(ReminderKind.Move), notification.shown.map(ReminderDecision::kind))
    }

    @Test
    fun `повторная проверка одного интервала не дублирует напоминание`() = runBlocking {
        val notification = FakeReminderNotification()
        val check = check(FakeActivityHistoryRefresh(), notification)

        check()
        check()

        assertEquals(1, notification.shown.size)
    }

    @Test
    fun `неудачная короткая синхронизация не мешает проверить сохранённые минуты`() = runBlocking {
        val notification = FakeReminderNotification()
        val check = check(FakeActivityHistoryRefresh(result = false), notification)

        check()

        assertEquals(listOf(ReminderKind.Move), notification.shown.map(ReminderDecision::kind))
    }

    @Test
    fun `неподтверждённый мониторинг не использует старую историю для напоминаний`() = runBlocking {
        val refresh = FakeActivityHistoryRefresh()
        val notification = FakeReminderNotification()
        val check = ReminderCheck(
            refresh,
            FakeActivityHistory(completeHour()),
            FakeRehabPlanRepository(),
            FakeReminderRepository(),
            notification,
            Clock.fixed(now, ZoneOffset.UTC),
            isMonitoringAvailable = { false },
        )

        check()

        assertEquals(0, refresh.calls)
        assertEquals(emptyList<ReminderDecision>(), notification.shown)
    }

    private fun check(refresh: ActivityHistoryRefresh, notification: ReminderNotification): ReminderCheck = ReminderCheck(
        refresh,
        FakeActivityHistory(completeHour()),
        FakeRehabPlanRepository(),
        FakeReminderRepository(),
        notification,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun completeHour(): List<ActivityMinute> = (60 downTo 1).map { ago ->
        ActivityMinute(
            start = now.minusSeconds(ago * 60L),
            steps = StepCount(2),
            heartRate = null,
            kind = ActivityKind.Other(80),
            intensity = Intensity(0),
        )
    }
}

private class FakeActivityHistoryRefresh(private val result: Boolean = true) : ActivityHistoryRefresh {
    var calls: Int = 0
        private set

    override suspend fun refresh(): Boolean {
        calls += 1
        return result
    }
}

private class FakeReminderNotification : ReminderNotification {
    val shown = mutableListOf<ReminderDecision>()

    override fun show(decision: ReminderDecision): Boolean {
        shown += decision
        return true
    }
}
