package ru.kardiomonitor.platform.huami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kardiomonitor.features.monitoring.domain.ActivityKind
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.StepCount
import java.time.Instant

class ActivityRecordsTest {
    @Test fun `сырая запись протокола переводится в доменные типы пациента`() {
        val recordedAt = Instant.parse("2026-10-09T05:03:00Z")
        val minute = ActivityRecords.fromProtocol(
            ActivityRecord(recordedAt, kind = 112, intensity = 6, steps = 5, heartRate = 0, 0, 0, 0, 0),
        )
        assertEquals(recordedAt, minute.start)
        assertEquals(ActivityKind.Other(112), minute.kind)
        assertEquals(StepCount(5), minute.steps)
        assertNull(minute.heartRate)
    }

    @Test fun `пульс и специальные коды активности переводятся на границе приложения`() {
        val at = Instant.EPOCH
        val heartRate = ActivityRecords.fromProtocol(
            ActivityRecord(at, 80, 9, 0, 73, 0, 0, 0, 0),
        )
        assertEquals(HeartRate(73), heartRate.heartRate)

        val kinds = listOf(115, 118, 120).map { code ->
            ActivityRecords.fromProtocol(ActivityRecord(at, code, 0, 0, 0, 0, 0, 0, 0)).kind
        }
        assertEquals(listOf(ActivityKind.NotWorn, ActivityKind.Charging, ActivityKind.Sleep), kinds)
    }
}
