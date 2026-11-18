package ru.kardiomonitor.features.rehab.domain

/** Непрерывный диапазон дней программы с одинаковыми нормами. */
data class RehabStage(
    val firstDay: Int,
    val lastDay: Int,
    val targets: DailyTargets,
) {
    init {
        require(firstDay >= 1 && firstDay <= lastDay) { "неверные границы этапа $firstDay–$lastDay" }
    }

    fun covers(programDay: Int): Boolean = programDay in firstDay..lastDay
}
