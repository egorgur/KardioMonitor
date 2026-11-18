package ru.kardiomonitor.features.reminders.remindercheck

import android.util.Log
import kotlinx.coroutines.CancellationException
import ru.kardiomonitor.features.monitoring.api.ActivityHistoryRefresh
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.monitoring.domain.ActivityKind
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.rehab.domain.RehabPlanRepository
import ru.kardiomonitor.features.reminders.domain.ReminderActivityMinute
import ru.kardiomonitor.features.reminders.domain.ReminderDailyProgress
import ru.kardiomonitor.features.reminders.domain.ReminderDecision
import ru.kardiomonitor.features.reminders.domain.ReminderKind
import ru.kardiomonitor.features.reminders.domain.ReminderRepository
import ru.kardiomonitor.features.reminders.domain.ReminderRules
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Обновляет историю, проверяет три локальных условия и показывает не больше разрешённого. */
class ReminderCheck(
    private val historyRefresh: ActivityHistoryRefresh,
    private val history: ActivityHistory,
    private val plans: RehabPlanRepository,
    private val repository: ReminderRepository,
    private val notification: ReminderNotification,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val isMonitoringAvailable: () -> Boolean = { true },
) {
    suspend operator fun invoke() {
        if (!isMonitoringAvailable()) return
        refreshHistory()
        val now = ZonedDateTime.now(clock)
        val day = now.toLocalDate()
        val minutes = loadMinutes(day, now.zone) ?: return
        val progress = dailyProgress(now)
        ReminderKind.entries.forEach { kind -> decideAndShow(kind, now, minutes, progress) }
    }

    private suspend fun refreshHistory() {
        try {
            historyRefresh.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "не удалось обновить историю перед напоминанием", e)
        }
    }

    private suspend fun loadMinutes(day: LocalDate, zone: ZoneId): List<ReminderActivityMinute>? = try {
        history.minutesOf(day, zone).map(ActivityMinute::toReminderMinute)
    } catch (e: IOException) {
        Log.w(TAG, "не удалось прочитать историю для напоминания", e)
        null
    }

    private suspend fun dailyProgress(now: ZonedDateTime): ReminderDailyProgress? {
        val plan = plans.plan.value ?: return null
        val day = now.withZoneSameInstant(plan.timeZone).toLocalDate()
        val minutes = try {
            history.minutesOf(day, plan.timeZone).map(ActivityMinute::toRehabActivityMinute)
        } catch (e: IOException) {
            return null
        }
        val progress = plan.progressOn(day, minutes, isFinal = false) ?: return null
        return ReminderDailyProgress(
            progress.trainingMinutes,
            progress.targets.trainingMinutes,
            progress.steps,
            progress.targets.steps,
            progress.isMet,
        )
    }

    private fun decideAndShow(
        kind: ReminderKind,
        now: ZonedDateTime,
        minutes: List<ReminderActivityMinute>,
        progress: ReminderDailyProgress?,
    ) {
        val day = now.toLocalDate()
        val decision = ReminderRules.decide(kind, now, repository.settings.value, repository.facts(day), minutes, progress)
            ?: return
        if (notification.show(decision)) repository.recordShown(day, kind, now.toInstant())
    }

    private companion object {
        const val TAG = "KardioReminder"
    }
}

private fun ActivityMinute.toReminderMinute(): ReminderActivityMinute = ReminderActivityMinute(
    start = start,
    steps = steps.value,
    state = when (kind) {
        ActivityKind.NotWorn -> ReminderActivityMinute.State.NotWorn
        ActivityKind.Charging -> ReminderActivityMinute.State.Charging
        ActivityKind.Sleep -> ReminderActivityMinute.State.Sleep
        is ActivityKind.Other -> ReminderActivityMinute.State.Worn
    },
)

private fun ActivityMinute.toRehabActivityMinute(): ru.kardiomonitor.features.rehab.domain.RehabActivityMinute =
    ru.kardiomonitor.features.rehab.domain.RehabActivityMinute(steps.value, heartRate?.bpm, kind.isWorn)
