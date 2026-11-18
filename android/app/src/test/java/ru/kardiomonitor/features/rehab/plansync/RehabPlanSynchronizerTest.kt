package ru.kardiomonitor.features.rehab.plansync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.features.clinic.domain.PhoneRehabPlanReport
import ru.kardiomonitor.features.clinic.fakes.FakeClinicServer
import ru.kardiomonitor.features.clinic.fakes.FakeServerRegistrationRepository
import ru.kardiomonitor.features.monitoring.fakes.FakeActivityHistory
import ru.kardiomonitor.features.rehab.fakes.FakeRehabPlanRepository
import java.time.LocalDate
import java.time.ZoneId

class RehabPlanSynchronizerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stopScope() = scope.cancel()

    @Test
    fun `после регистрации телефон получает план и порог ходьбы`() {
        val server = FakeClinicServer().apply { phoneRehabPlan = report(threshold = 45) }
        val registrations = FakeServerRegistrationRepository(FakeClinicServer.REGISTRATION)
        val plans = FakeRehabPlanRepository()

        RehabPlanSynchronizer(server, registrations, plans, FakeActivityHistory(), scope).start()

        assertEquals("patient-1", plans.selectedPatientId)
        assertEquals(45, plans.plan.value?.current?.walkingStepsThreshold)
        assertEquals(42, plans.plan.value?.current?.program?.durationDays)
        assertEquals(30, plans.plan.value?.current?.program?.stages?.first()?.targets?.trainingMinutes)
    }

    @Test
    fun `повторное обновление того же дня заменяет версию`() {
        val server = FakeClinicServer().apply { phoneRehabPlan = report(threshold = 45) }
        val registrations = FakeServerRegistrationRepository(FakeClinicServer.REGISTRATION)
        val plans = FakeRehabPlanRepository()
        val synchronizer = RehabPlanSynchronizer(server, registrations, plans, FakeActivityHistory(), scope)
        synchronizer.start()
        server.phoneRehabPlan = report(threshold = 40)

        synchronizer.refreshNow()

        assertEquals(1, plans.plan.value?.revisions?.size)
        assertEquals(40, plans.plan.value?.current?.walkingStepsThreshold)
    }

    @Test
    fun `ошибка обновления не удаляет сохранённый план`() {
        val server = FakeClinicServer().apply { phoneRehabPlan = report(threshold = 45) }
        val plans = FakeRehabPlanRepository()
        val synchronizer = RehabPlanSynchronizer(
            server,
            FakeServerRegistrationRepository(FakeClinicServer.REGISTRATION),
            plans,
            FakeActivityHistory(),
            scope,
        )
        synchronizer.start()
        server.failure = "нет сети"

        synchronizer.refreshNow()

        assertEquals(45, plans.plan.value?.current?.walkingStepsThreshold)
    }

    @Test
    fun `этапы ответа сервера нормализуются по первому дню`() {
        val unsorted = report(threshold = 45).copy(
            stages = listOf(
                PhoneRehabPlanReport.Stage(22, 42, 100, 120, 40, 12_000),
                PhoneRehabPlanReport.Stage(1, 21, 90, 110, 30, 10_000),
            ),
        )
        val server = FakeClinicServer().apply { phoneRehabPlan = unsorted }
        val plans = FakeRehabPlanRepository()

        RehabPlanSynchronizer(
            server,
            FakeServerRegistrationRepository(FakeClinicServer.REGISTRATION),
            plans,
            FakeActivityHistory(),
            scope,
        ).start()

        assertEquals(listOf(1, 22), plans.plan.value?.current?.program?.stages?.map { it.firstDay })
    }

    private fun report(threshold: Int): PhoneRehabPlanReport = PhoneRehabPlanReport(
        timeZone = ZoneId.of("Europe/Moscow"),
        effectiveFrom = LocalDate.parse("2026-10-12"),
        walkingStepsThreshold = threshold,
        startDate = LocalDate.parse("2026-10-01"),
        durationDays = 42,
        stages = listOf(PhoneRehabPlanReport.Stage(1, 42, 90, 110, 30, 10_000)),
    )
}
