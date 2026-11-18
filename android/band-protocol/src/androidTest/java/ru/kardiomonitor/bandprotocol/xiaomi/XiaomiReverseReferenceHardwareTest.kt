@file:Suppress("DEPRECATION")

package ru.kardiomonitor.bandprotocol.xiaomi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.test.InstrumentationTestCase
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Два exact чтения до запуска Mi Fitness; далее только UI и RAM, не Bluetooth или raw-файлы. */
class XiaomiReverseReferenceHardwareTest : InstrumentationTestCase() {
    fun testSampleBeforeReference(): Unit = runBlocking {
        val files = instrumentation.context.filesDir
        val keyFile = File(files, "auth-key")
        val requestFile = File(files, "reverse-request.json")
        val signalFile = File(files, "reverse-capture.signal")
        val reportFile = File(files, "reverse-result.json")
        val result = XiaomiReverseReferenceResult()
        val sample = XiaomiHistoryReferenceSample()
        try {
            val request = consumeRequest(requestFile)
            result.latestSelection = request.latest
            result.write(reportFile)
            if (signalFile.exists()) throw IOException()
            result.requestRemoved = !requestFile.exists()
            acquire(sample, request.offset, keyFile, result)
            result.stage = XiaomiReverseReferenceResult.Stage.SAMPLE_READY
            result.write(reportFile)
            awaitSignal(signalFile)
            result.signalRemoved = !signalFile.exists()
            result.stage = XiaomiReverseReferenceResult.Stage.CAPTURE
            result.write(reportFile)
            val reference = XiaomiMiFitnessTooltip(instrumentation).capture(request.day)
            result.referenceCaptured = true
            result.windowRelation = sample.windowRelation(reference)
            result.comparison = sample.compare(reference)
            if (result.comparison?.matches != true) result.failureStage = "comparison"
            result.stage = XiaomiReverseReferenceResult.Stage.COMPLETE
        } catch (_: Exception) {
            if (result.failureStage == null) result.failureStage = failureStage(result.stage)
            result.stage = XiaomiReverseReferenceResult.Stage.FAILED
        } finally {
            sample.close()
            result.sampleMemoryCleared = sample.cleared
            result.keyRemoved = remove(keyFile)
            result.requestRemoved = remove(requestFile)
            result.signalRemoved = remove(signalFile)
            if ((!result.sampleMemoryCleared || !result.keyRemoved || !result.requestRemoved || !result.signalRemoved) &&
                result.failureStage == null) result.failureStage = "cleanup"
            result.write(reportFile)
        }
        if (!result.passed) throw IOException("reverse_reference_check_failed")
    }
    fun testSyntheticPrivateRequestAndSignalCleanup() {
        val file = File(instrumentation.context.filesDir, "reverse-synthetic-${java.util.UUID.randomUUID()}")
        try {
            for (json in listOf("{}", "{\"day\":\"2024-02-30\",\"offset\":0}",
                "{\"day\":\"2024-02-29\",\"offset\":\"0\"}",
                "{\"day\":\"2024-02-29\",\"offset\":256}",
                "{\"day\":\"2024-02-29\",\"offset\":0,\"extra\":true}",
                "{\"day\":\"2024-02-29\",\"selection\":\"latest\",\"offset\":0}",
                "{\"day\":\"2024-02-29\",\"selection\":\"other\"}")) {
                file.writeText(json, Charsets.US_ASCII)
                try { consumeRequest(file); fail("Ожидался отказ") } catch (_: Exception) { }
                assertFalse(file.exists())
            }
            file.writeText("{\"day\":\"2024-02-29\",\"offset\":0}", Charsets.US_ASCII)
            val numeric = consumeRequest(file)
            assertEquals(LocalDate.of(2024, 2, 29), numeric.day)
            assertEquals(0, numeric.offset)
            assertFalse(numeric.latest)
            assertFalse(file.exists())
            file.writeText("{\"day\":\"2024-02-29\",\"selection\":\"latest\"}", Charsets.US_ASCII)
            assertTrue(consumeRequest(file).latest)
            assertFalse(file.exists())
            file.writeText("wrong!!", Charsets.US_ASCII)
            try { awaitSignal(file); fail("Ожидался отказ") } catch (_: IOException) { }
            assertFalse(file.exists())
            file.writeText("capture", Charsets.US_ASCII)
            awaitSignal(file)
            assertFalse(file.exists())
        } finally { file.delete() }
    }
    private suspend fun acquire(
        sample: XiaomiHistoryReferenceSample,
        offset: Int,
        keyFile: File,
        result: XiaomiReverseReferenceResult,
    ) {
        val key = consumeKey(keyFile)
        val client = XiaomiReadOnlyClient(instrumentation.context)
        try {
            result.stage = XiaomiReverseReferenceResult.Stage.OPEN
            client.open(bondedAddress(instrumentation.context), key)
            result.stage = XiaomiReverseReferenceResult.Stage.HISTORY
            val now = Instant.now()
            val target = if (result.latestSelection) XiaomiHistoryLatestTarget(now.epochSecond,
                ZoneId.systemDefault().rules.getOffset(now).totalSeconds) else null
            val history = client.captureHistoryReferenceSample(offset, sample, XiaomiHistoryIndex.TODAY, target)
            result.sampleRepeatVerified = history.exactTransfers == 2 && history.repeatedBytesIdentical == true &&
                history.repeatedAggregatesConsistent == true
            result.sampleRetentionUnchanged = history.retentionUnchanged
            result.clinicalImportBlocked = !history.clinicalImportAllowed && !history.timeAndValuesVerified && !history.fullCoverageVerified
            result.sampleVerified = sample.verified && result.sampleRepeatVerified && result.sampleRetentionUnchanged && result.clinicalImportBlocked
            if (!result.sampleVerified) throw IOException()
        } finally {
            key.fill(0)
            try { client.close() }
            catch (_: Exception) { result.failureStage = "close"; throw IOException("reverse_reference_close_failed") }
            result.clientClosedBeforeReference = true
            result.keyRemoved = !keyFile.exists()
        }
    }
    private class Request(val day: LocalDate, val offset: Int, val latest: Boolean) {
        override fun toString(): String = "ReverseReferenceRequest"
    }
    private fun consumeRequest(file: File): Request {
        try {
            if (file.length() !in 1..128) throw IOException()
            val json = JSONObject(file.readText(Charsets.US_ASCII))
            if (json.length() != 2 || !json.has("day") || json.get("day") !is String) throw IOException()
            val latest = json.has("selection")
            val offset = if (latest) {
                if (json.get("selection") != "latest") throw IOException()
                0
            } else {
                val value = json.get("offset")
                if (value !is Int || value !in 0..255) throw IOException()
                value
            }
            val day = json.getString("day")
            if (!day.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) throw IOException()
            return Request(LocalDate.parse(day), offset, latest)
        } finally { if (!remove(file)) throw IOException("reverse_reference_cleanup_failed") }
    }
    private fun awaitSignal(file: File) {
        val deadline = SystemClock.elapsedRealtime() + 300_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (file.exists()) {
                try {
                    if (file.length() != 7L || file.readText(Charsets.US_ASCII) != "capture") throw IOException()
                    return
                } finally { if (!remove(file)) throw IOException("reverse_reference_cleanup_failed") }
            }
            SystemClock.sleep(250)
        }
        throw IOException("reverse_reference_wait_expired")
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
                    if (next == 0) throw IOException()
                    count += next
                }
                count
            }
            if (count != 32) throw IOException()
            for (index in decoded.indices) {
                val high = (encoded[2 * index].toInt() and 255).toChar().digitToIntOrNull(16)
                val low = (encoded[2 * index + 1].toInt() and 255).toChar().digitToIntOrNull(16)
                if (high == null || low == null) throw IOException()
                decoded[index] = ((high shl 4) or low).toByte()
            }
            if (decoded.all { it == 0.toByte() }) throw IOException()
            return decoded
        } catch (_: Exception) { decoded.fill(0); throw IOException("reverse_reference_key_failed") }
        finally {
            encoded.fill(0)
            if (!remove(file)) { decoded.fill(0); throw IOException("reverse_reference_cleanup_failed") }
        }
    }
    @SuppressLint("MissingPermission")
    private fun bondedAddress(context: Context): String {
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw IOException()
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException()
        val matches = adapter.bondedDevices.filter {
            it.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED &&
                it.name?.matches(Regex("Xiaomi Smart Band 9(?: [A-Za-z0-9]{4})?")) == true
        }
        if (matches.size != 1) throw IOException()
        return matches.single().address
    }
    private fun failureStage(stage: XiaomiReverseReferenceResult.Stage): String = when (stage) {
        XiaomiReverseReferenceResult.Stage.SAMPLE_READY, XiaomiReverseReferenceResult.Stage.AWAIT_REFERENCE -> "await_reference"
        XiaomiReverseReferenceResult.Stage.COMPLETE -> "comparison"
        XiaomiReverseReferenceResult.Stage.FAILED -> "cleanup"
        else -> stage.name.lowercase(java.util.Locale.ROOT)
    }
    private fun remove(file: File): Boolean = try { (!file.exists() || file.delete()) && !file.exists() }
        catch (_: Exception) { false }
}
