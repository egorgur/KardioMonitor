package ru.kardiomonitor.features.rehab.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Программа реабилитации: дата начала, длительность и непрерывные этапы. */
data class RehabProgram(
    val startDate: LocalDate,
    val durationDays: Int,
    val stages: List<RehabStage>,
) {
    init {
        require(durationDays in 1..MAX_DAYS) { "длительность программы должна быть от 1 до $MAX_DAYS дней" }
        require(stages == stages.sortedBy { it.firstDay }) { "этапы программы должны идти по порядку" }
        checkCoverage()
    }

    val lastDate: LocalDate get() = startDate.plusDays(durationDays.toLong() - 1)

    fun dayNumber(day: LocalDate): Int? {
        val number = ChronoUnit.DAYS.between(startDate, day).toInt() + 1
        return number.takeIf { it in 1..durationDays }
    }

    fun stageOn(day: LocalDate): RehabStage? {
        val number = dayNumber(day) ?: return null
        return stages.first { it.covers(number) }
    }

    fun targetsOn(day: LocalDate): DailyTargets? = stageOn(day)?.targets

    private fun checkCoverage() {
        var nextDay = 1
        for (stage in stages) {
            require(stage.lastDay <= durationDays) { "этап выходит за длительность программы" }
            require(stage.firstDay == nextDay) { "этапы должны покрывать программу без пропусков и пересечений" }
            nextDay = stage.lastDay + 1
        }
        require(nextDay == durationDays + 1) { "этапы должны покрывать всю программу" }
    }

    private companion object {
        const val MAX_DAYS = 365
    }
}
