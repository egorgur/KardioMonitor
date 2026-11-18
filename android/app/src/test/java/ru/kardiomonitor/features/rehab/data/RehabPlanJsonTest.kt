package ru.kardiomonitor.features.rehab.data

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.features.rehab.domain.DailyTargets
import ru.kardiomonitor.features.rehab.domain.ProgramRevision
import ru.kardiomonitor.features.rehab.domain.RehabPlan
import ru.kardiomonitor.features.rehab.domain.RehabProgram
import ru.kardiomonitor.features.rehab.domain.RehabStage
import ru.kardiomonitor.features.rehab.domain.TrainingHeartRateZone
import java.time.LocalDate
import java.time.ZoneId

class RehabPlanJsonTest {
    @Test
    fun `все версии плана переживают JSON round trip`() {
        val expected = RehabPlan(
            timeZone = ZoneId.of("Europe/Moscow"),
            revisions = listOf(
                revision("2026-10-01", 60, 30),
                revision("2026-10-12", 45, 35),
            ),
        )

        val restored = RehabPlanJson.decode(RehabPlanJson.encode(expected))

        assertEquals(expected, restored)
    }

    private fun revision(effectiveFrom: String, threshold: Int, minutes: Int) = ProgramRevision(
        effectiveFrom = LocalDate.parse(effectiveFrom),
        walkingStepsThreshold = threshold,
        program = RehabProgram(
            startDate = LocalDate.parse("2026-10-01"),
            durationDays = 42,
            stages = listOf(
                RehabStage(
                    firstDay = 1,
                    lastDay = 42,
                    targets = DailyTargets(
                        trainingZone = TrainingHeartRateZone(90, 110),
                        trainingMinutes = minutes,
                        steps = 10_000,
                    ),
                ),
            ),
        ),
    )
}
