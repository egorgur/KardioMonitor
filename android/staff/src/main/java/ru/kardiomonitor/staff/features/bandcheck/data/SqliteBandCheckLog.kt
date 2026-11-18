package ru.kardiomonitor.staff.features.bandcheck.data

import android.content.ContentValues
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.kardiomonitor.staff.features.bandcheck.domain.BandCheck
import ru.kardiomonitor.staff.features.bandcheck.domain.BandCheckLog
import ru.kardiomonitor.staff.features.bandcheck.domain.BatteryLevel
import ru.kardiomonitor.staff.features.bandcheck.domain.CheckOutcome
import ru.kardiomonitor.staff.features.bandcheck.domain.CheckProblem
import ru.kardiomonitor.staff.features.bandcheck.domain.TestedFirmware
import ru.kardiomonitor.staff.platform.db.StaffDatabase
import java.time.Instant

class SqliteBandCheckLog(private val database: StaffDatabase) : BandCheckLog {
    override suspend fun save(check: BandCheck) = withContext(Dispatchers.IO) {
        database.writableDatabase.insert("band_checks", null, check.toValues())
        Unit
    }

    override suspend fun latestOf(address: String): BandCheck? = withContext(Dispatchers.IO) {
        database.readableDatabase.query(
            "band_checks",
            COLUMNS,
            "address = ?",
            arrayOf(address),
            null,
            null,
            "checked_at DESC, id DESC",
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toCheck() else null }
    }

    private fun BandCheck.toValues(): ContentValues = ContentValues().apply {
        put("address", address)
        put("checked_at", checkedAt.epochSecond)
        put("firmware", firmware?.value)
        put("battery_percent", battery?.percent)
        put("protocol", protocol.id)
        put("hr_interval_minutes", heartRateIntervalMinutes)
        put("hr_disabled", heartRateDisabled?.let { if (it) 1 else 0 })
        when (val result = outcome) {
            CheckOutcome.Ready -> put("outcome", "ready")
            is CheckOutcome.Warning -> {
                put("outcome", "warning")
                put("detail", result.reasons.joinToString("\n"))
            }
            is CheckOutcome.Failed -> {
                put("outcome", "failed")
                put("detail", result.problem.name)
            }
        }
    }

    private fun Cursor.toCheck(): BandCheck {
        val outcome = when (getString(getColumnIndexOrThrow("outcome"))) {
            "ready" -> CheckOutcome.Ready
            "warning" -> CheckOutcome.Warning(getStringOrNull("detail").orEmpty().lines())
            else -> CheckOutcome.Failed(CheckProblem.valueOf(getString(getColumnIndexOrThrow("detail"))))
        }
        return BandCheck(
            address = getString(getColumnIndexOrThrow("address")),
            checkedAt = Instant.ofEpochSecond(getLong(getColumnIndexOrThrow("checked_at"))),
            outcome = outcome,
            firmware = getStringOrNull("firmware")?.let(::TestedFirmware),
            battery = getNullableInt("battery_percent")?.let(::BatteryLevel),
            protocol = ru.kardiomonitor.staff.shared.BandProtocol.fromId(
                getString(getColumnIndexOrThrow("protocol")),
            ),
            heartRateIntervalMinutes = getNullableInt("hr_interval_minutes"),
            heartRateDisabled = getNullableInt("hr_disabled")?.let { it == 1 },
        )
    }

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getNullableInt(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    private companion object {
        val COLUMNS = arrayOf(
            "address", "checked_at", "outcome", "detail", "firmware", "battery_percent",
            "protocol", "hr_interval_minutes", "hr_disabled",
        )
    }
}
