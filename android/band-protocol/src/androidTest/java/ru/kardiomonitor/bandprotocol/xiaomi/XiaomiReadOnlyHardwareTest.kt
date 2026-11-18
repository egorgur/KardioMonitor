@file:Suppress("DEPRECATION")

package ru.kardiomonitor.bandprotocol.xiaomi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Только ручной изолированный прогон: auth-key в filesDir тестового APK, 32 ASCII hex.
 * readonly-result.json содержит только фиксированные флаги и failure_phase, не значения.
 * Фаза open включает auth и точный guard; guard_verified=true только после успешного open.
 * Подготовка/остановка Mi Fitness и восстановление выполняются внешним владельцем стенда.
 */
class XiaomiReadOnlyHardwareTest : InstrumentationTestCase() {
    private enum class Phase(val code: String) {
        KEY("key"), BOND("bond"), OPEN("open"), BATTERY("battery"), SETTINGS("settings"), HISTORY("history"), CLOSE("close"),
    }
    private class ReadOnlyResult {
        var phase = Phase.KEY
        var failure: Phase? = null
        var openFailure: XiaomiOpenPhase? = null
        var keyConsumed = false
        var permissionsGranted = false
        var bondUnique = false
        var clientCreated = false
        var openCompleted = false
        var batteryCompleted = false
        var batteryPresent = false
        var settingsCompleted = false
        var settingsDisabledPresent = false
        var settingsIntervalPresent = false
        var clientCloseCompleted = false
        var keyRemoved = false
        var historyRequested = false
        var historyCompleted = false
        var historyRetentionUnchanged = false
        var historyRepeatedBytesIdentical = false
        var historyAggregatesConsistent = false
        var historyImportBlocked = false
        var referenceRequested = false
        var referenceMatched = false
        var referenceRemoved = false
        var referenceComparison: XiaomiHistoryReferenceComparison? = null
        var historyDiagnostics: XiaomiHistoryDiagnostics? = null
        var historyIndex = XiaomiHistoryIndex.PAST
        val passed: Boolean get() = failure == null && settingsCompleted && clientCloseCompleted && keyRemoved &&
            (!historyRequested || historyCompleted && historyRetentionUnchanged && historyRepeatedBytesIdentical &&
                historyAggregatesConsistent && historyImportBlocked) &&
            (!referenceRequested || referenceMatched && referenceRemoved)
        fun write(file: File) {
            val report = JSONObject()
                .put("schema_version", if (referenceRequested) 3 else if (historyRequested) 2 else 1).put("test_passed", passed)
                .put("history_reference_requested", referenceRequested).put("history_reference_matched", referenceMatched)
                .put("history_reference_removed", referenceRemoved)
                .put("history_reference_record_located", referenceComparison?.recordLocated ?: JSONObject.NULL)
                .put("history_reference_timezone_matches", referenceComparison?.timezoneMatches ?: JSONObject.NULL)
                .put("history_reference_heart_rate_matches", referenceComparison?.heartRateMatches ?: JSONObject.NULL)
                .put("history_reference_steps_match", referenceComparison?.stepsMatch ?: JSONObject.NULL)
                .put("history_index", historyIndex.name.lowercase(java.util.Locale.ROOT))
                .put("history_stage", historyDiagnostics?.stage?.name?.lowercase(java.util.Locale.ROOT) ?: JSONObject.NULL)
                .put("history_selection", historyDiagnostics?.selection?.name?.lowercase(java.util.Locale.ROOT) ?: JSONObject.NULL)
                .put("history_requested", historyRequested).put("history_completed", historyCompleted)
                .put("history_retention_unchanged", historyRetentionUnchanged)
                .put("history_repeated_bytes_identical", historyRepeatedBytesIdentical)
                .put("history_aggregates_consistent", historyAggregatesConsistent)
                .put("history_import_blocked", historyImportBlocked)
                .put("key_consumed", keyConsumed).put("permissions_granted", permissionsGranted)
                .put("bond_unique", bondUnique).put("client_created", clientCreated)
                .put("open_completed", openCompleted).put("guard_verified", openCompleted)
                .put("battery_completed", batteryCompleted).put("settings_completed", settingsCompleted)
                .put("battery_present", batteryPresent).put("settings_disabled_present", settingsDisabledPresent)
                .put("settings_interval_present", settingsIntervalPresent)
                .put("client_close_completed", clientCloseCompleted).put("key_removed", keyRemoved)
                .put("failure_phase", failure?.code ?: JSONObject.NULL)
                .put("open_failure_phase", openFailure?.name?.lowercase(java.util.Locale.ROOT) ?: JSONObject.NULL)
            writeSelectionEvidence(report)
            try { file.writeText(report.toString(), Charsets.US_ASCII) }
            catch (_: Exception) { throw IOException("production_readonly_report_failed") }
        }
        private fun writeSelectionEvidence(report: JSONObject) {
            if (!referenceRequested) return
            val evidence = historyDiagnostics?.selectionEvidence
            fun Enum<*>?.code(): Any = this?.name?.lowercase(java.util.Locale.ROOT) ?: JSONObject.NULL
            report.put("history_reference_clock_relation", evidence?.referenceClockRelation.code())
                .put("history_candidate_clock_relation", evidence?.candidateClockRelation.code())
                .put("history_after_reference_gap", evidence?.afterReferenceGap.code())
                .put("history_after_reference_same_local_day", evidence?.afterReferenceSameLocalDay ?: JSONObject.NULL)
        }
    }
    fun testGuardedReadOnly(): Unit = runBlocking {
        val context = instrumentation.context
        val file = File(context.filesDir, "auth-key")
        val report = File(context.filesDir, "readonly-result.json")
        val referenceFile = File(context.filesDir, "history-reference.json")
        val result = ReadOnlyResult()
        val arguments = (instrumentation as InstrumentationTestRunner).arguments
        result.referenceRequested = arguments.getString("verify_history_reference") == "true"
        result.historyRequested = arguments.getString("verify_past_repeat") == "true" || result.referenceRequested
        val historyOffset = arguments.getString("history_offset")?.toIntOrNull() ?: 0
        var key: ByteArray? = null
        var client: XiaomiReadOnlyClient? = null
        try {
            if (report.exists() && !report.delete()) throw IOException("production_readonly_report_failed")
            val reference = if (result.referenceRequested) XiaomiHardwareReference.consume(referenceFile) else null
            result.historyIndex = when (arguments.getString("history_reference_index")) {
                null, "past" -> XiaomiHistoryIndex.PAST
                "today" -> if (reference != null) XiaomiHistoryIndex.TODAY else throw IOException("invalid_history_index")
                else -> throw IOException("invalid_history_index")
            }
            key = consumeKey(file)
            result.keyConsumed = true
            result.phase = Phase.BOND
            requirePermissions(context)
            result.permissionsGranted = true
            val address = bondedCandidate(context).address
            result.bondUnique = true
            result.phase = Phase.OPEN
            val current = XiaomiReadOnlyClient(context)
            client = current
            result.clientCreated = true
            current.open(address, key)
            result.openCompleted = true
            readSupported(current, result)
            if (result.historyRequested) verifyHistory(current, historyOffset, reference,
                arguments.getString("history_select_reference_time") == "true", result)
        } catch (error: CancellationException) {
            result.failure = result.phase
            throw error
        } catch (error: XiaomiOpenException) {
            result.failure = result.phase
            result.openFailure = error.phase
            throw IOException("production_readonly_failed")
        } catch (_: Exception) {
            result.failure = result.phase
            throw IOException("production_readonly_failed")
        } finally {
            finish(client, key, file, result)
            result.referenceRemoved = removeReference(referenceFile)
            if (!result.referenceRemoved && result.failure == null) result.failure = Phase.CLOSE
            result.write(report)
        }
        if (!result.passed) throw IOException("production_readonly_cleanup_failed")
    }
    private suspend fun readSupported(client: XiaomiReadOnlyClient, result: ReadOnlyResult) {
        result.phase = Phase.BATTERY
        val battery = client.readBattery()
        if (battery != null && battery !in 0..100) throw IOException("production_readonly_failed")
        result.batteryCompleted = true
        result.batteryPresent = battery != null
        result.phase = Phase.SETTINGS
        val settings = client.readHeartRateSettings()
        if (settings.intervalMinutes != null && settings.intervalMinutes < 0) throw IOException("production_readonly_failed")
        result.settingsCompleted = true
        result.settingsDisabledPresent = settings.disabled != null
        result.settingsIntervalPresent = settings.intervalMinutes != null
    }
    private suspend fun verifyHistory(
        client: XiaomiReadOnlyClient,
        offset: Int,
        reference: XiaomiHistoryReference?,
        selectReferenceTime: Boolean,
        result: ReadOnlyResult,
    ) {
        result.phase = Phase.HISTORY
        val diagnostics = if (reference != null) XiaomiHistoryDiagnostics(System.currentTimeMillis() / 1000) else null
        result.historyDiagnostics = diagnostics
        val history = if (reference == null) client.verifyPastHistory(offset, repeat = true)
            else client.verifyPastHistoryReference(offset, reference, selectReferenceTime, diagnostics, result.historyIndex)
        result.referenceMatched = history.referenceMatched == true
        result.referenceComparison = history.referenceComparison
        result.historyCompleted = history.exactTransfers == 2
        result.historyRetentionUnchanged = history.retentionUnchanged
        result.historyRepeatedBytesIdentical = history.repeatedBytesIdentical == true
        result.historyAggregatesConsistent = history.repeatedAggregatesConsistent == true
        result.historyImportBlocked = !history.clinicalImportAllowed && !history.timeAndValuesVerified && !history.fullCoverageVerified
        if (!result.historyCompleted || !result.historyRetentionUnchanged || !result.historyRepeatedBytesIdentical ||
            !result.historyAggregatesConsistent || !result.historyImportBlocked ||
            result.referenceRequested && !result.referenceMatched) throw IOException("production_history_verification_failed")
    }
    private fun removeReference(file: File): Boolean = try {
        (!file.exists() || file.delete()) && !file.exists()
    } catch (_: Exception) { false }
    private fun finish(client: XiaomiReadOnlyClient?, key: ByteArray?, file: File, result: ReadOnlyResult) {
        key?.fill(0)
        try {
            client?.close()
            result.clientCloseCompleted = true
        } catch (_: Exception) {
            if (result.failure == null) result.failure = Phase.CLOSE
        }
        try {
            result.keyRemoved = !file.exists() || file.delete()
        } catch (_: Exception) {
            result.keyRemoved = false
        }
        if (!result.keyRemoved && result.failure == null) result.failure = Phase.CLOSE
    }
    private fun consumeKey(file: File): ByteArray {
        val encoded = ByteArray(33)
        val decoded = ByteArray(16)
        try {
            val count = file.inputStream().use { input ->
                var count = 0
                while (count < encoded.size) {
                    val next = input.read(encoded, count, encoded.size - count)
                    if (next < 0) break
                    if (next == 0) throw IOException("Некорректный тестовый ключ")
                    count += next
                }
                count
            }
            if (count != 32) throw IOException("Некорректный тестовый ключ")
            for (index in decoded.indices) {
                val high = digit(encoded[2 * index]); val low = digit(encoded[2 * index + 1])
                if (high < 0 || low < 0) throw IOException("Некорректный тестовый ключ")
                decoded[index] = ((high shl 4) or low).toByte()
            }
            if (decoded.all { it == 0.toByte() }) throw IOException("Некорректный тестовый ключ")
            return decoded
        } catch (_: Exception) {
            decoded.fill(0)
            throw IOException("Некорректный тестовый ключ")
        } finally {
            encoded.fill(0)
            if (file.exists() && !file.delete()) { decoded.fill(0); throw IOException("Не удалось удалить тестовый ключ") }
        }
    }
    private fun digit(byte: Byte): Int = when (val value = byte.toInt() and 255) {
        in 48..57 -> value - 48
        in 65..70 -> value - 65 + 10
        in 97..102 -> value - 97 + 10
        else -> -1
    }
    private fun requirePermissions(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw IOException("Нет разрешения Bluetooth")
        }
    }
    @SuppressLint("MissingPermission")
    private fun bondedCandidate(context: Context): BluetoothDevice {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("Bluetooth недоступен")
        val matches = adapter.bondedDevices.filter {
            it.bondState == BluetoothDevice.BOND_BONDED &&
                it.name?.matches(Regex("Xiaomi Smart Band 9(?: [A-Za-z0-9]{4})?")) == true
        }
        if (matches.size != 1) throw IOException("Требуется единственный сопряжённый Band 9")
        return matches.single()
    }
}
