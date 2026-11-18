package ru.kardiomonitor.features.rehab.plansync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.kardiomonitor.features.clinic.domain.ClinicServer
import ru.kardiomonitor.features.clinic.domain.PhoneRehabPlanReport
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.rehab.domain.DailyTargets
import ru.kardiomonitor.features.rehab.domain.ProgramRevision
import ru.kardiomonitor.features.rehab.domain.RehabPlan
import ru.kardiomonitor.features.rehab.domain.RehabPlanRepository
import ru.kardiomonitor.features.rehab.domain.RehabProgram
import ru.kardiomonitor.features.rehab.domain.RehabStage
import ru.kardiomonitor.features.rehab.domain.TrainingHeartRateZone

/** Загружает план после регистрации и каждой синхронизации истории; сохраняет известные версии. */
class RehabPlanSynchronizer(
    private val server: ClinicServer,
    private val registrations: ServerRegistrationRepository,
    private val plans: RehabPlanRepository,
    private val history: ActivityHistory,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    fun start() {
        scope.launch {
            combine(registrations.registration, history.changes.onStart { emit(Unit) }) { registration, _ -> registration }
                .conflate()
                .collect { registration ->
                    plans.selectPatient(registration?.patientId)
                    if (registration != null) refresh(registration)
                }
        }
    }

    fun refreshNow() {
        val registration = registrations.registration.value ?: return
        scope.launch { refresh(registration) }
    }

    private suspend fun refresh(registration: ServerRegistration) = mutex.withLock {
        try {
            val report = server.rehabPlan(registration)
            val revision = report.toRevision()
            val plan = plans.plan.value?.updated(revision, report.timeZone)
                ?: RehabPlan(report.timeZone, listOf(revision))
            plans.save(registration.patientId, plan)
            Log.i(TAG, "план реабилитации обновлён с ${revision.effectiveFrom}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "не удалось обновить план реабилитации", e)
        }
    }

    private fun PhoneRehabPlanReport.toRevision(): ProgramRevision = ProgramRevision(
        effectiveFrom = effectiveFrom,
        walkingStepsThreshold = walkingStepsThreshold,
        program = RehabProgram(
            startDate = startDate,
            durationDays = durationDays,
            stages = stages.sortedBy { it.firstDay }.map { stage ->
                RehabStage(
                    firstDay = stage.firstDay,
                    lastDay = stage.lastDay,
                    targets = DailyTargets(
                        trainingZone = TrainingHeartRateZone(stage.lowerBpm, stage.upperBpm),
                        trainingMinutes = stage.trainingMinutes,
                        steps = stage.steps,
                    ),
                )
            },
        ),
    )

    private companion object {
        const val TAG = "KardioServer"
    }
}
