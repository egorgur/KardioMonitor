package ru.kardiomonitor.features.rehab.domain

import java.time.LocalDate
import java.time.ZoneId

/** Локально известные версии плана пациента; текущая версия приходит с сервера при синхронизации. */
data class RehabPlan(
    val timeZone: ZoneId,
    val revisions: List<ProgramRevision>,
) {
    init {
        require(revisions.isNotEmpty()) { "у плана должна быть хотя бы одна версия" }
        require(revisions == revisions.sortedBy { it.effectiveFrom }) { "версии плана должны идти по датам" }
        require(revisions.map { it.effectiveFrom }.distinct().size == revisions.size) { "даты версий не должны повторяться" }
    }

    val current: ProgramRevision get() = revisions.last()

    fun updated(revision: ProgramRevision, newTimeZone: ZoneId): RehabPlan {
        val kept = revisions.filter { it.effectiveFrom != revision.effectiveFrom }
        return RehabPlan(newTimeZone, (kept + revision).sortedBy { it.effectiveFrom })
    }

    fun revisionOn(day: LocalDate): ProgramRevision =
        revisions.lastOrNull { !it.effectiveFrom.isAfter(day) } ?: revisions.first()

    fun programOn(day: LocalDate): RehabProgram {
        val revision = revisionOn(day)
        return revision.program.takeIf { it.dayNumber(day) != null } ?: current.program
    }

    fun progressOn(day: LocalDate, minutes: List<RehabActivityMinute>, isFinal: Boolean): DailyTargetProgress? {
        val targets = programOn(day).targetsOn(day) ?: return null
        return DailyTargetProgress.of(
            minutes,
            targets,
            walkingStepsThreshold = revisionOn(day).walkingStepsThreshold,
            isFinal = isFinal,
        )
    }
}
