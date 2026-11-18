package ru.kardiomonitor.features.rehab.domain

import kotlinx.coroutines.flow.StateFlow

/** Локальный кеш плана текущего зарегистрированного пациента. */
interface RehabPlanRepository {
    val plan: StateFlow<RehabPlan?>

    /** Выбирает владельца кеша; чужой план не публикуется. */
    fun selectPatient(patientId: String?)

    fun save(patientId: String, plan: RehabPlan)
}
