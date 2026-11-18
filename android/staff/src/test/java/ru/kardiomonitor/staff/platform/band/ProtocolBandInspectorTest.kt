package ru.kardiomonitor.staff.platform.band

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.staff.features.bandcheck.domain.BandInspector
import ru.kardiomonitor.staff.features.bandcheck.domain.CheckProblem
import ru.kardiomonitor.staff.features.bandcheck.domain.InspectionResult
import ru.kardiomonitor.staff.features.bandcheck.domain.InspectionStage
import ru.kardiomonitor.staff.shared.BandProtocol

class ProtocolBandInspectorTest {
    @Test
    fun `выбранный протокол вызывает только свой inspector без fallback`() = runTest {
        val band6 = RecordingInspector()
        val band9 = RecordingInspector()
        val router = ProtocolBandInspector(band6, band9)
        val key = ByteArray(16)
        router.inspect("E7:19:F3:6C:04:9E", key, 1, {}, BandProtocol.XiaomiBand9Spp)
        assertEquals(emptyList<BandProtocol>(), band6.calls)
        assertEquals(listOf(BandProtocol.XiaomiBand9Spp), band9.calls)
        router.inspect("E7:19:F3:6C:04:9E", key, 1, {}, BandProtocol.MiBand6Ble)
        assertEquals(listOf(BandProtocol.MiBand6Ble), band6.calls)
        val result = router.inspect("E7:19:F3:6C:04:9E", key, 1, {}, BandProtocol.Unknown)
        assertEquals(InspectionResult.Refused(CheckProblem.UnsupportedProtocol), result)
        assertEquals(1, band6.calls.size)
        assertEquals(1, band9.calls.size)
    }
}

private class RecordingInspector : BandInspector {
    val calls = mutableListOf<BandProtocol>()
    override suspend fun inspect(
        address: String, key: ByteArray, heartRateIntervalMinutes: Int,
        onStage: (InspectionStage) -> Unit, protocol: BandProtocol,
    ): InspectionResult {
        calls += protocol
        assertEquals(1, heartRateIntervalMinutes)
        return InspectionResult.Refused(CheckProblem.LinkFailed)
    }
}
