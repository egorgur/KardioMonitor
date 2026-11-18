package ru.kardiomonitor.staff.platform.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaffDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun createVersionOneDatabase() {
        context.deleteDatabase(NAME)
        context.openOrCreateDatabase(NAME, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL(V1_BANDS)
            database.execSQL(V1_CHECKS)
            database.execSQL(V1_ISSUANCES)
            database.execSQL("CREATE UNIQUE INDEX unique_band_label ON clinic_bands(label COLLATE NOCASE)")
            insertBand(database, "AA:BB:CC:DD:EE:01", "Café")
            insertBand(database, "AA:BB:CC:DD:EE:02", "Cafe\u0301")
            insertIssuance(database, "AA:BB:CC:DD:EE:01")
            insertIssuance(database, "AA:BB:CC:DD:EE:01")
            database.version = 1
        }
    }

    @After
    fun deleteDatabase() {
        context.deleteDatabase(NAME)
    }

    @Test
    fun upgradeInstallsCanonicalLabelAndOpenIssuanceIndexes() {
        StaffDatabase(context, NAME).use { helper ->
            val database = helper.writableDatabase
            assertEquals(1, count(database, "clinic_bands", "label_key IS NOT NULL"))
            assertEquals(1, count(database, "clinic_bands", "label IS NULL"))
            assertEquals(1, count(database, "issuances", "returned_at IS NULL"))
            assertEquals(1, count(database, "issuances", "returned_at IS NOT NULL"))
            assertEquals(3, database.version)
            assertEquals(2, count(database, "clinic_bands", "protocol = 'mi-band6-ble'"))
            assertEquals(2, count(database, "issuances", "protocol = 'mi-band6-ble'"))
            database.query("clinic_bands", arrayOf("key_sealed"), null, null, null, null, null).use {
                while (it.moveToNext()) org.junit.Assert.assertArrayEquals(byteArrayOf(1), it.getBlob(0))
            }

            assertThrows(SQLiteConstraintException::class.java) {
                insertBand(database, "AA:BB:CC:DD:EE:03", "Other", "café")
            }
            assertThrows(SQLiteConstraintException::class.java) {
                insertIssuance(database, "AA:BB:CC:DD:EE:01")
            }
        }
    }

    @Test
    fun upgradeVersionTwoPreservesSealedKeyAndMarksLegacyProtocols() {
        context.openOrCreateDatabase(NAME, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("ALTER TABLE clinic_bands ADD COLUMN label_key TEXT")
            database.version = 2
        }
        StaffDatabase(context, NAME).use { helper ->
            val database = helper.writableDatabase
            assertEquals(2, count(database, "clinic_bands", "protocol = 'mi-band6-ble'"))
            assertEquals(2, count(database, "issuances", "protocol = 'mi-band6-ble'"))
            database.query("clinic_bands", arrayOf("key_sealed"), null, null, null, null, null).use {
                while (it.moveToNext()) org.junit.Assert.assertArrayEquals(byteArrayOf(1), it.getBlob(0))
            }
        }
    }

    private fun insertBand(
        database: android.database.sqlite.SQLiteDatabase,
        address: String,
        label: String,
        labelKey: String? = null,
    ) {
        val values = ContentValues().apply {
            put("address", address)
            put("key_sealed", byteArrayOf(1))
            put("label", label)
            if (labelKey != null) put("label_key", labelKey)
            put("model", "Mi Smart Band 6")
            put("key_origin", "Manual")
            put("key_received_at", 1L)
            put("in_cloud", 0)
        }
        database.insertOrThrow("clinic_bands", null, values)
    }

    private fun insertIssuance(database: android.database.sqlite.SQLiteDatabase, address: String) {
        val values = ContentValues().apply {
            put("address", address)
            put("issued_at", 1L)
        }
        database.insertOrThrow("issuances", null, values)
    }

    private fun count(database: android.database.sqlite.SQLiteDatabase, table: String, selection: String): Int =
        database.query(table, arrayOf("COUNT(*)"), selection, null, null, null, null).use {
            it.moveToFirst()
            it.getInt(0)
        }

    private companion object {
        const val NAME = "staff-migration-test.db"
        val V1_BANDS = """
            CREATE TABLE clinic_bands (
                address TEXT PRIMARY KEY NOT NULL,
                key_sealed BLOB NOT NULL,
                label TEXT,
                model TEXT NOT NULL,
                firmware TEXT,
                key_origin TEXT NOT NULL,
                key_received_at INTEGER NOT NULL,
                in_cloud INTEGER NOT NULL
            )
        """.trimIndent()
        val V1_CHECKS = """
            CREATE TABLE band_checks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                address TEXT NOT NULL,
                checked_at INTEGER NOT NULL,
                outcome TEXT NOT NULL,
                detail TEXT,
                firmware TEXT,
                battery_percent INTEGER
            )
        """.trimIndent()
        val V1_ISSUANCES = """
            CREATE TABLE issuances (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                address TEXT NOT NULL,
                label TEXT,
                issued_at INTEGER NOT NULL,
                returned_at INTEGER
            )
        """.trimIndent()
    }
}
