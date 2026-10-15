package ru.kardiomonitor.features.band.domain

/** Результат установки соединения с браслетом ([BandLink.open]). */
sealed interface OpenResult {
    data object Opened : OpenResult

    data class Refused(val problem: ConnectionProblem) : OpenResult
}
