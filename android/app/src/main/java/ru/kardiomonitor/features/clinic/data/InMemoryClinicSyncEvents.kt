package ru.kardiomonitor.features.clinic.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.kardiomonitor.features.clinic.domain.ClinicSyncEvents

/** События обмена внутри одного процесса; данные события не содержат сведений пациента. */
class InMemoryClinicSyncEvents : ClinicSyncEvents {
    private val completed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val minuteUploadCompleted: Flow<Unit> = completed.asSharedFlow()

    override fun publishMinuteUploadCompleted() {
        completed.tryEmit(Unit)
    }
}
