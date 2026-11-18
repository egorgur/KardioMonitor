package ru.kardiomonitor.staff.features.bandcheck.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BandCheckTest {
    private val now = Instant.parse("2026-10-29T10:00:00Z")

    @Test
    fun `проверка из будущего не считается свежей`() {
        assertFalse(checkAt(Instant.parse("2026-10-29T10:00:01Z")).isFreshAt(now))
    }

    @Test
    fun `успешная проверка младше суток считается свежей`() {
        assertTrue(checkAt(Instant.parse("2026-10-28T10:00:01Z")).isFreshAt(now))
    }

    @Test
    fun `версия прошивки допускает префикс V`() {
        assertTrue(TestedFirmware("1.0.6.10").isKnown)
        assertTrue(TestedFirmware("V1.0.6.10").isKnown)
    }

    private fun checkAt(at: Instant) = BandCheck(
        address = "E7:19:F3:6C:04:9E",
        checkedAt = at,
        outcome = CheckOutcome.Ready,
        firmware = TestedFirmware("V1.0.6.10"),
        battery = BatteryLevel(80),
    )
}
