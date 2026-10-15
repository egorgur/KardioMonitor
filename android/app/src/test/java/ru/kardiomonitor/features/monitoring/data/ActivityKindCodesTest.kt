package ru.kardiomonitor.features.monitoring.data

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.features.monitoring.domain.ActivityKind

class ActivityKindCodesTest {

    @Test
    fun `тип активности хранится в базе прежним кодом браслета`() {
        assertEquals(115, ActivityKindCodes.code(ActivityKind.NotWorn))
        assertEquals(118, ActivityKindCodes.code(ActivityKind.Charging))
        assertEquals(120, ActivityKindCodes.code(ActivityKind.Sleep))
        assertEquals(80, ActivityKindCodes.code(ActivityKind.Other(80)))
    }

    @Test
    fun `история прототипа читается с прежними типами активности`() {
        for (code in listOf(115, 118, 120, 80, 1, 112)) {
            assertEquals(code, ActivityKindCodes.code(ActivityKindCodes.kind(code)))
        }
        assertEquals(ActivityKind.NotWorn, ActivityKindCodes.kind(115))
    }
}
