@file:Suppress("DEPRECATION")

package ru.kardiomonitor.bandprotocol.xiaomi

import android.test.InstrumentationTestCase
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import ru.kardiomonitor.bandprotocol.xiaomi.XiaomiMiFitnessTooltip.VisibleText
import ru.kardiomonitor.bandprotocol.xiaomi.XiaomiMiFitnessTooltip.Companion.reference

/** Явное чтение уже открытого tooltip Mi Fitness без waitForIdle, навигации и Bluetooth. */
class XiaomiMiFitnessReferenceCaptureTest : InstrumentationTestCase() {
    fun testCaptureReference() {
        val context = instrumentation.context
        val output = File(context.filesDir, "mi-fitness-reference.json")
        val report = File(context.filesDir, "reference-capture-result.json")
        var phase = "prepare"
        var success = false
        try {
            if (output.exists() && !output.delete()) throw IOException()
            val day = consumeDay(File(context.filesDir, "capture-request.json"))
            phase = "snapshot"
            val captured = XiaomiMiFitnessTooltip(instrumentation).capture(day)
            phase = "write"
            val json = JSONObject().put("epoch_seconds", captured.epochSeconds)
                .put("utc_offset_seconds", captured.utcOffsetSeconds).put("heart_rate", captured.heartRate)
                .put("steps", captured.steps ?: JSONObject.NULL)
            output.writeText(json.toString(), Charsets.UTF_8)
            success = true
        } catch (error: Exception) {
            if (error is XiaomiMiFitnessTooltip.Failure) phase = error.stage
            throw IOException("mi_fitness_reference_capture_failed")
        } finally {
            if (!success) output.delete()
            report.writeText(JSONObject().put("captured", success).put("stage", phase).toString(), Charsets.US_ASCII)
        }
    }
    private fun consumeDay(file: File): LocalDate {
        try {
            if (file.length() !in 1..64) throw IOException()
            return LocalDate.parse(file.readText(Charsets.US_ASCII))
        } finally {
            if (file.exists() && !file.delete()) throw IOException()
        }
    }
    fun testSyntheticReferenceRejectsWrongDayMissingTooltipAndAmbiguousClock() {
        val day = LocalDate.of(2024, 11, 3)
        val texts = listOf(VisibleText("03.11.2024", 191, 249), VisibleText("72 BPM", 626, 532),
            VisibleText("01:30", 626, 589))
        val result = reference(texts, day, ZoneId.of("Asia/Krasnoyarsk"))
        assertEquals(72, result.heartRate)
        assertNull(result.steps)
        for (invalid in listOf(texts.drop(1), texts.dropLast(1), texts + VisibleText("01:31", 626, 589),
            texts + VisibleText("Нет данных", 463, 586), texts.map {
                if (it.text == "72 BPM") VisibleText("255 BPM", it.top, it.bottom) else it
            })) {
            try {
                reference(invalid, day, ZoneId.of("Asia/Krasnoyarsk"))
                fail("Ожидался отказ")
            } catch (_: IOException) { }
        }
        try {
            reference(texts, day.plusDays(1), ZoneId.of("Asia/Krasnoyarsk"))
            fail("Ожидался отказ")
        } catch (_: IOException) { }
        try {
            reference(texts, day, ZoneId.of("America/New_York"))
            fail("Ожидался отказ")
        } catch (_: IOException) { }
    }
}
