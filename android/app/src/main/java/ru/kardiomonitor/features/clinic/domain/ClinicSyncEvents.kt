package ru.kardiomonitor.features.clinic.domain

import kotlinx.coroutines.flow.Flow

/** События успешного обмена с клиникой для независимых слайсов-подписчиков. */
interface ClinicSyncEvents {
    val minuteUploadCompleted: Flow<Unit>

    fun publishMinuteUploadCompleted()
}
