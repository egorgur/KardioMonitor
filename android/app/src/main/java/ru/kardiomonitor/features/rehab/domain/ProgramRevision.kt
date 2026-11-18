package ru.kardiomonitor.features.rehab.domain

import java.time.LocalDate

/** Версия программы и порога ходьбы, действующая с указанного дня пациента. */
data class ProgramRevision(
    val effectiveFrom: LocalDate,
    val walkingStepsThreshold: Int,
    val program: RehabProgram,
) {
    init {
        require(walkingStepsThreshold in 1..MAX_THRESHOLD) { "порог ходьбы должен быть от 1 до $MAX_THRESHOLD" }
    }

    private companion object {
        const val MAX_THRESHOLD = 250
    }
}
