package ru.kardiomonitor.staff.platform.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.kardiomonitor.staff.shared.canonicalTextKey

class StaffDatabase private constructor(context: Context, name: String, version: Int) :
    SQLiteOpenHelper(context, name, null, version) {
    constructor(context: Context) : this(context, NAME, VERSION)

    internal constructor(context: Context, testName: String) : this(context, testName, VERSION)
    override fun onCreate(database: SQLiteDatabase) {
        createBands(database)
        createChecks(database)
        createIssuances(database)
    }

    private fun createBands(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE clinic_bands (
                address TEXT PRIMARY KEY NOT NULL,
                key_sealed BLOB NOT NULL,
                label TEXT,
                label_key TEXT,
                model TEXT NOT NULL,
                firmware TEXT,
                key_origin TEXT NOT NULL,
                key_received_at INTEGER NOT NULL,
                in_cloud INTEGER NOT NULL,
                protocol TEXT NOT NULL DEFAULT 'mi-band6-ble'
            )
            """.trimIndent(),
        )
        createBandLabelIndex(database)
    }

    private fun createChecks(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE band_checks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                address TEXT NOT NULL,
                checked_at INTEGER NOT NULL,
                outcome TEXT NOT NULL,
                detail TEXT,
                firmware TEXT,
                battery_percent INTEGER,
                protocol TEXT NOT NULL DEFAULT 'mi-band6-ble',
                hr_interval_minutes INTEGER,
                hr_disabled INTEGER
            )
            """.trimIndent(),
        )
    }

    private fun createIssuances(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE issuances (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                address TEXT NOT NULL,
                label TEXT,
                issued_at INTEGER NOT NULL,
                returned_at INTEGER,
                protocol TEXT NOT NULL DEFAULT 'mi-band6-ble'
            )
            """.trimIndent(),
        )
        createOpenIssuanceIndex(database)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addUnicodeLabelKey(database)
        if (oldVersion < 3) {
            listOf("clinic_bands", "band_checks", "issuances").forEach { table ->
                database.execSQL("ALTER TABLE $table ADD COLUMN protocol TEXT NOT NULL DEFAULT 'mi-band6-ble'")
            }
            database.execSQL("ALTER TABLE band_checks ADD COLUMN hr_interval_minutes INTEGER")
            database.execSQL("ALTER TABLE band_checks ADD COLUMN hr_disabled INTEGER")
        }
        closeDuplicateOpenIssuances(database)
        createOpenIssuanceIndex(database)
    }

    private fun closeDuplicateOpenIssuances(database: SQLiteDatabase) {
        database.execSQL(
            """
            UPDATE issuances
            SET returned_at = issued_at
            WHERE returned_at IS NULL
              AND id NOT IN (
                  SELECT MAX(id) FROM issuances WHERE returned_at IS NULL GROUP BY address
              )
            """.trimIndent(),
        )
    }

    private fun addUnicodeLabelKey(database: SQLiteDatabase) {
        database.execSQL("DROP INDEX IF EXISTS unique_band_label")
        database.execSQL("ALTER TABLE clinic_bands ADD COLUMN label_key TEXT")
        val used = mutableSetOf<String>()
        database.query("clinic_bands", arrayOf("address", "label"), "label IS NOT NULL", null, null, null, "address")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val address = cursor.getString(0)
                    val label = cursor.getString(1)
                    val key = canonicalTextKey(label)
                    val values = android.content.ContentValues().apply {
                        if (used.add(key)) {
                            put("label_key", key)
                        } else {
                            putNull("label")
                            putNull("label_key")
                        }
                    }
                    database.update("clinic_bands", values, "address = ?", arrayOf(address))
                }
            }
        createBandLabelIndex(database)
    }

    private fun createBandLabelIndex(database: SQLiteDatabase) =
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS unique_band_label ON clinic_bands(label_key)")

    private fun createOpenIssuanceIndex(database: SQLiteDatabase) =
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS one_open_issuance " +
                "ON issuances(address) WHERE returned_at IS NULL",
        )

    private companion object {
        const val NAME = "staff.db"
        const val VERSION = 3
    }
}
