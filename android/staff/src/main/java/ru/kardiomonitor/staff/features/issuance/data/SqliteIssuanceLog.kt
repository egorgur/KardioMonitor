package ru.kardiomonitor.staff.features.issuance.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.kardiomonitor.staff.features.issuance.domain.Issuance
import ru.kardiomonitor.staff.features.issuance.domain.IssuanceLog
import ru.kardiomonitor.staff.platform.db.StaffDatabase
import java.time.Instant
import ru.kardiomonitor.staff.shared.BandProtocol

class SqliteIssuanceLog(private val database: StaffDatabase) : IssuanceLog {
    private val writeMutex = Mutex()

    override suspend fun openOrCurrent(
        address: String,
        label: String?,
        at: Instant,
        protocol: BandProtocol,
    ): Issuance =
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                require(protocol != BandProtocol.Unknown) { "Неизвестный протокол" }
                currentBlocking(address)?.let {
                    require(it.protocol == protocol) { "Протокол открытой выдачи не совпадает" }
                    return@withContext it
                }
                val values = ContentValues().apply {
                    put("address", address)
                    put("label", label)
                    put("issued_at", at.epochSecond)
                    put("protocol", protocol.id)
                }
                try {
                    val id = database.writableDatabase.insertOrThrow("issuances", null, values)
                    Issuance(id, address, label, at, null, protocol)
                } catch (failure: SQLiteConstraintException) {
                    val current = currentBlocking(address) ?: throw failure
                    require(current.protocol == protocol) { "Протокол открытой выдачи не совпадает" }
                    current
                }
            }
        }

    override suspend fun current(address: String): Issuance? = withContext(Dispatchers.IO) {
        currentBlocking(address)
    }

    override suspend fun returned(address: String, at: Instant): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val current = currentBlocking(address) ?: return@withLock false
            require(!at.isBefore(current.issuedAt)) { "Возврат не может быть раньше выдачи" }
            val values = ContentValues().apply { put("returned_at", at.epochSecond) }
            database.writableDatabase.update(
                "issuances",
                values,
                "id = ? AND returned_at IS NULL",
                arrayOf(current.id.toString()),
            ) == 1
        }
    }

    override suspend fun history(): List<Issuance> = withContext(Dispatchers.IO) {
        database.readableDatabase.query(
            "issuances",
            COLUMNS,
            null,
            null,
            null,
            null,
            "issued_at DESC, id DESC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toIssuance()) } }
    }

    private fun currentBlocking(address: String): Issuance? = database.readableDatabase.query(
        "issuances",
        COLUMNS,
        "address = ? AND returned_at IS NULL",
        arrayOf(address),
        null,
        null,
        "issued_at DESC",
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toIssuance() else null }

    private fun Cursor.toIssuance(): Issuance = Issuance(
        id = getLong(getColumnIndexOrThrow("id")),
        address = getString(getColumnIndexOrThrow("address")),
        label = getStringOrNull("label"),
        issuedAt = Instant.ofEpochSecond(getLong(getColumnIndexOrThrow("issued_at"))),
        returnedAt = getLongOrNull("returned_at")?.let(Instant::ofEpochSecond),
        protocol = BandProtocol.fromId(getString(getColumnIndexOrThrow("protocol"))),
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private companion object {
        val COLUMNS = arrayOf("id", "address", "label", "issued_at", "returned_at", "protocol")
    }
}
