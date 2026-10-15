package ru.kardiomonitor.features.band.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.kardiomonitor.features.band.domain.BatteryLevel
import ru.kardiomonitor.features.band.domain.BatteryLog
import ru.kardiomonitor.platform.db.KardioDatabase
import ru.kardiomonitor.platform.db.KardioDatabase.Companion.BATTERY_TABLE
import java.time.Instant

/** Журнал заряда в SQLite: одна запись на подключение. */
class SqliteBatteryLog(private val database: KardioDatabase) : BatteryLog {

    override suspend fun record(level: BatteryLevel, at: Instant) {
        val values = ContentValues().apply {
            put("ts", at.epochSecond)
            put("level", level.percent)
        }
        withContext(Dispatchers.IO) {
            database.writableDatabase.insertWithOnConflict(BATTERY_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
}
