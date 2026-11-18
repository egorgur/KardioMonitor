package ru.kardiomonitor.features.rehab.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Прогресс всей программы и сегодняшняя норма по локальной истории пациента. */
data class RehabOverview(
    val programDay: Int?,
    val durationDays: Int,
    val completedDays: Int,
    val metDays: Int,
    val currentStage: RehabStage?,
    val todayProgress: DailyTargetProgress?,
    val isCompleted: Boolean,
) {
    companion object {
        fun of(
            plan: RehabPlan,
            today: LocalDate,
            minutesByDay: Map<LocalDate, List<RehabActivityMinute>>,
        ): RehabOverview {
            val program = plan.current.program
            val completedDays = completedDays(program, today)
            val metDays = (0 until completedDays).count { offset ->
                val day = program.startDate.plusDays(offset.toLong())
                plan.progressOn(day, minutesByDay[day].orEmpty(), isFinal = true)?.isMet == true
            }
            return RehabOverview(
                programDay = program.dayNumber(today),
                durationDays = program.durationDays,
                completedDays = completedDays,
                metDays = metDays,
                currentStage = program.stageOn(today),
                todayProgress = plan.progressOn(today, minutesByDay[today].orEmpty(), isFinal = false),
                isCompleted = today.isAfter(program.lastDate),
            )
        }

        private fun completedDays(program: RehabProgram, today: LocalDate): Int {
            if (!today.isAfter(program.startDate)) return 0
            val beforeToday = ChronoUnit.DAYS.between(program.startDate, today).toInt()
            return beforeToday.coerceAtMost(program.durationDays)
        }
    }
}
