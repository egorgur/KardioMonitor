package ru.kardiomonitor.features.monitoring.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Завершение каждой попытки синхронизации, даже без новых минут или при сбое браслета. */
interface HistorySyncEvents {
    val attemptsCompleted: Flow<Unit>

    /** Не блокирует завершение и не заменяет исходный результат синхронизации. */
    fun publishAttemptCompleted()

    object None : HistorySyncEvents {
        override val attemptsCompleted: Flow<Unit> = emptyFlow()
        override fun publishAttemptCompleted() = Unit
    }
}
