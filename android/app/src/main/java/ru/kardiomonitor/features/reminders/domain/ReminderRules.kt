package ru.kardiomonitor.features.reminders.domain

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/** Чистые правила условий, окна, сна, лимита, отложения и выполненной нормы. */
object ReminderRules {
    private const val MAX_PER_DAY = 8
    private const val WEAR_BAND_MINUTES = 60

    fun decide(
        kind: ReminderKind,
        now: ZonedDateTime,
        settings: ReminderSettings,
        facts: ReminderDayFacts,
        minutes: List<ReminderActivityMinute>,
        progress: ReminderDailyProgress?,
    ): ReminderDecision? {
        if (!isGenerallyAllowed(kind, now, settings, facts, minutes, progress)) return null
        return when (kind) {
            ReminderKind.Move -> move(settings.move, now.toInstant(), minutes)
            ReminderKind.DailyTarget -> dailyTarget(settings.dailyTarget, progress)
            ReminderKind.WearBand -> wearBand(settings.wearBand, now.toInstant(), minutes)
        }
    }

    private fun isGenerallyAllowed(
        kind: ReminderKind,
        now: ZonedDateTime,
        settings: ReminderSettings,
        facts: ReminderDayFacts,
        minutes: List<ReminderActivityMinute>,
        progress: ReminderDailyProgress?,
    ): Boolean {
        if (facts.isMuted || facts.shownCount >= MAX_PER_DAY) return false
        val snoozedUntil = facts.snoozedUntil[kind]
        if (snoozedUntil?.isAfter(now.toInstant()) == true) return false
        val isSnoozeDue = snoozedUntil != null && !snoozedUntil.isAfter(now.toInstant())
        if (minutes.lastOrNull()?.state == ReminderActivityMinute.State.Sleep) return false
        if (progress?.isMet == true && kind != ReminderKind.WearBand) return false
        if (kind == ReminderKind.DailyTarget && facts.shown[kind].orEmpty().isNotEmpty() && !isSnoozeDue) return false
        return when (kind) {
            ReminderKind.Move -> inIntervalSchedule(settings.move.schedule, now) &&
                (isSnoozeDue || hasNoShowInSlot(kind, settings.move.schedule, now, facts))
            ReminderKind.DailyTarget -> settings.dailyTarget.enabled && !now.toLocalTime().isBefore(settings.dailyTarget.time)
            ReminderKind.WearBand -> inIntervalSchedule(settings.wearBand, now) &&
                (isSnoozeDue || hasNoShowInSlot(kind, settings.wearBand, now, facts))
        }
    }

    private fun inIntervalSchedule(schedule: IntervalReminderSettings, now: ZonedDateTime): Boolean =
        schedule.enabled && now.dayOfWeek in schedule.days && schedule.window.contains(now.toLocalTime())

    private fun hasNoShowInSlot(
        kind: ReminderKind,
        schedule: IntervalReminderSettings,
        now: ZonedDateTime,
        facts: ReminderDayFacts,
    ): Boolean {
        val elapsed = Duration.between(schedule.window.start, now.toLocalTime()).toMinutes()
        if (elapsed < schedule.interval.minutes) return false
        val slotStart = now.minusMinutes(elapsed % schedule.interval.minutes).toInstant()
        return facts.shown[kind].orEmpty().none { !it.isBefore(slotStart) }
    }

    private fun move(
        settings: MoveReminderSettings,
        now: Instant,
        minutes: List<ReminderActivityMinute>,
    ): ReminderDecision? {
        val intervalMinutes = settings.schedule.interval.minutes
        val interval = completeInterval(minutes, now, intervalMinutes) ?: return null
        if (interval.any { it.state == ReminderActivityMinute.State.Sleep }) return null
        val steps = interval.sumOf(ReminderActivityMinute::steps)
        val threshold = settings.stepsPerHour * intervalMinutes.toInt() / 60
        return if (steps < threshold) ReminderDecision(ReminderKind.Move, ReminderDecision.Details.Move(steps, threshold)) else null
    }

    private fun dailyTarget(
        settings: DailyTargetReminderSettings,
        progress: ReminderDailyProgress?,
    ): ReminderDecision? {
        if (!settings.enabled || progress == null || progress.isMet) return null
        return ReminderDecision(
            ReminderKind.DailyTarget,
            ReminderDecision.Details.DailyTarget(progress.remainingTrainingMinutes, progress.remainingSteps),
        )
    }

    private fun wearBand(
        settings: IntervalReminderSettings,
        now: Instant,
        minutes: List<ReminderActivityMinute>,
    ): ReminderDecision? {
        if (!settings.enabled) return null
        val interval = completeInterval(minutes, now, WEAR_BAND_MINUTES.toLong()) ?: return null
        val isNotWorn = interval.all { it.state == ReminderActivityMinute.State.NotWorn }
        return if (isNotWorn) {
            ReminderDecision(ReminderKind.WearBand, ReminderDecision.Details.WearBand(WEAR_BAND_MINUTES))
        } else null
    }

    /** Нет данных за любую минуту интервала — условие неизвестно и напоминание не показывается. */
    private fun completeInterval(
        minutes: List<ReminderActivityMinute>,
        now: Instant,
        intervalMinutes: Long,
    ): List<ReminderActivityMinute>? {
        val from = now.minus(Duration.ofMinutes(intervalMinutes))
        val selected = minutes.filter { !it.start.isBefore(from) && it.start.isBefore(now) }
        val distinctMinutes = selected.map { it.start.epochSecond / 60 }.distinct().size
        return selected.takeIf { distinctMinutes >= intervalMinutes }
    }
}
