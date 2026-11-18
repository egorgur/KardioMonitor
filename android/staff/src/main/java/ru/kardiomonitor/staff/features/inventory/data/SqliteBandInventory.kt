package ru.kardiomonitor.staff.features.inventory.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import ru.kardiomonitor.staff.features.inventory.domain.AuthKey
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.inventory.domain.BandInventory
import ru.kardiomonitor.staff.features.inventory.domain.BandLabel
import ru.kardiomonitor.staff.features.inventory.domain.BandModel
import ru.kardiomonitor.staff.features.inventory.domain.ClinicBand
import ru.kardiomonitor.staff.features.inventory.domain.ClinicBandSummary
import ru.kardiomonitor.staff.features.inventory.domain.Firmware
import ru.kardiomonitor.staff.features.inventory.domain.KeyOrigin
import ru.kardiomonitor.staff.platform.db.StaffDatabase
import ru.kardiomonitor.staff.platform.keystore.SecretBox
import java.time.Instant

class SqliteBandInventory(
    private val database: StaffDatabase,
    private val secretBox: SecretBox,
) : BandInventory {
    private val current = MutableStateFlow(loadSummaries())
    override val bands: Flow<List<ClinicBandSummary>> = current

    override suspend fun byAddress(address: BandAddress): ClinicBand? = withContext(Dispatchers.IO) {
        database.readableDatabase.query(
            TABLE,
            COLUMNS,
            "address = ?",
            arrayOf(address.value),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toBand() else null }
    }

    override suspend fun save(band: ClinicBand) = withContext(Dispatchers.IO) {
        try {
            val writable = database.writableDatabase
            val values = band.toValues()
            val updated = writable.update(TABLE, values, "address = ?", arrayOf(band.address.value))
            if (updated == 0) writable.insertOrThrow(TABLE, null, values)
        } catch (failure: SQLiteConstraintException) {
            throw IllegalArgumentException("Такая метка уже используется", failure)
        }
        current.value = loadSummaries()
    }

    override suspend fun remove(address: BandAddress) = withContext(Dispatchers.IO) {
        database.writableDatabase.delete(TABLE, "address = ?", arrayOf(address.value))
        current.value = loadSummaries()
    }

    override suspend fun summaryByAddress(address: BandAddress): ClinicBandSummary? = withContext(Dispatchers.IO) {
        database.readableDatabase.query(
            TABLE,
            SUMMARY_COLUMNS,
            "address = ?",
            arrayOf(address.value),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toSummary() else null }
    }

    override suspend fun hasLabel(label: BandLabel, except: BandAddress?): Boolean = withContext(Dispatchers.IO) {
        val selection = if (except == null) "label_key = ?" else "label_key = ? AND address != ?"
        val arguments = listOfNotNull(label.comparisonKey, except?.value).toTypedArray()
        database.readableDatabase.query(TABLE, arrayOf("address"), selection, arguments, null, null, null)
            .use(Cursor::moveToFirst)
    }

    override suspend fun updateLabel(address: BandAddress, label: BandLabel): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("label", label.value)
            put("label_key", label.comparisonKey)
        }
        try {
            val updated = database.writableDatabase.update(TABLE, values, "address = ?", arrayOf(address.value)) == 1
            if (updated) current.value = loadSummaries()
            updated
        } catch (failure: SQLiteConstraintException) {
            throw IllegalArgumentException("Такая метка уже используется", failure)
        }
    }

    private fun loadSummaries(): List<ClinicBandSummary> = database.readableDatabase.query(
        TABLE,
        SUMMARY_COLUMNS,
        null,
        null,
        null,
        null,
        "label COLLATE NOCASE, address",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toSummary()) } }

    private fun ClinicBand.toValues(): ContentValues = ContentValues().apply {
        put("address", address.value)
        put("key_sealed", key.useBytes { bytes ->
            try {
                secretBox.seal(bytes)
            } catch (_: java.security.GeneralSecurityException) {
                throw ru.kardiomonitor.staff.features.inventory.domain.StoredKeyUnavailable()
            }
        })
        put("label", label?.value)
        put("label_key", label?.comparisonKey)
        put("model", model.value)
        put("firmware", firmware?.value)
        put("key_origin", keyOrigin.name)
        put("key_received_at", keyReceivedAt.epochSecond)
        put("in_cloud", if (isInCloud) 1 else 0)
        put("protocol", protocol.id)
    }

    private fun Cursor.toBand(): ClinicBand {
        val key = openKey(getBlob(getColumnIndexOrThrow("key_sealed")))
        return ClinicBand(
            address = BandAddress.of(getString(getColumnIndexOrThrow("address"))),
            key = key,
            label = getStringOrNull("label")?.let(BandLabel::of),
            model = BandModel(getString(getColumnIndexOrThrow("model"))),
            firmware = getStringOrNull("firmware")?.let(::Firmware),
            keyOrigin = KeyOrigin.valueOf(getString(getColumnIndexOrThrow("key_origin"))),
            keyReceivedAt = Instant.ofEpochSecond(getLong(getColumnIndexOrThrow("key_received_at"))),
            isInCloud = getInt(getColumnIndexOrThrow("in_cloud")) == 1,
            protocol = ru.kardiomonitor.staff.shared.BandProtocol.fromId(
                getString(getColumnIndexOrThrow("protocol")),
            ),
        )
    }

    private fun openKey(sealed: ByteArray): AuthKey = try {
        val plainKey = secretBox.open(sealed)
        try {
            AuthKey.fromBytes(plainKey)
        } finally {
            plainKey.fill(0)
        }
    } catch (_: java.security.GeneralSecurityException) {
        throw ru.kardiomonitor.staff.features.inventory.domain.StoredKeyUnavailable()
    } catch (_: IllegalArgumentException) {
        throw ru.kardiomonitor.staff.features.inventory.domain.StoredKeyUnavailable()
    }

    private fun Cursor.toSummary(): ClinicBandSummary = ClinicBandSummary(
        address = BandAddress.of(getString(getColumnIndexOrThrow("address"))),
        label = getStringOrNull("label")?.let(BandLabel::of),
        model = BandModel(getString(getColumnIndexOrThrow("model"))),
        firmware = getStringOrNull("firmware")?.let(::Firmware),
        keyOrigin = KeyOrigin.valueOf(getString(getColumnIndexOrThrow("key_origin"))),
        keyReceivedAt = Instant.ofEpochSecond(getLong(getColumnIndexOrThrow("key_received_at"))),
        isInCloud = getInt(getColumnIndexOrThrow("in_cloud")) == 1,
        protocol = ru.kardiomonitor.staff.shared.BandProtocol.fromId(
            getString(getColumnIndexOrThrow("protocol")),
        ),
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private companion object {
        const val TABLE = "clinic_bands"
        val COLUMNS = arrayOf(
            "address",
            "key_sealed",
            "label",
            "model",
            "firmware",
            "key_origin",
            "key_received_at",
            "in_cloud",
            "protocol",
        )
        val SUMMARY_COLUMNS = COLUMNS.filterNot { it == "key_sealed" }.toTypedArray()
    }
}
