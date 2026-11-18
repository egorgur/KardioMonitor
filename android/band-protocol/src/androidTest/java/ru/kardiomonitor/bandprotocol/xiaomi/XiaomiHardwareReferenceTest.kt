@file:Suppress("DEPRECATION")

package ru.kardiomonitor.bandprotocol.xiaomi

import android.test.InstrumentationTestCase
import java.io.File
import java.io.IOException
import java.util.UUID

/** Проверяет только private-файл на фиктивных данных; Bluetooth и auth-key не используются. */
class XiaomiHardwareReferenceTest : InstrumentationTestCase() {
    private val valid = """{"epoch_seconds":1700000040,"utc_offset_seconds":25200,"heart_rate":72,"steps":null}"""

    fun testValidReferenceIsConsumedAndDeleted() {
        withFile(valid) { file ->
            val reference = XiaomiHardwareReference.consume(file)
            assertEquals(1_700_000_040L, reference.epochSeconds)
            assertEquals(25_200, reference.utcOffsetSeconds)
            assertEquals(72, reference.heartRate)
            assertNull(reference.steps)
            assertFalse(file.exists())
        }
    }
    fun testInvalidReferenceIsDeletedWithoutReturningValues() {
        for (text in listOf("", " ".repeat(1025), "[]", "{", valid.replace("72", "255"),
            valid.replace("72", "0"), valid.replace("72", "null"), valid.replace("72", "\"72\""),
            valid.replace("25200", "25201"), valid.replace("1700000040", "1700000041"),
            valid.replace("1700000040", "9223372036854775807"), valid.dropLast(1) + ",\"extra\":1}")) {
            withFile(text) { file ->
                try {
                    XiaomiHardwareReference.consume(file)
                    fail("Ожидался отказ")
                } catch (error: IOException) { assertEquals("Некорректный эталон", error.message) }
                assertFalse(file.exists())
            }
        }
    }
    fun testZeroStepsAreNotMissingHeartRate() {
        withFile(valid.replace("72", "null").replace("\"steps\":null", "\"steps\":0")) { file ->
            val reference = XiaomiHardwareReference.consume(file)
            assertNull(reference.heartRate)
            assertEquals(0, reference.steps)
        }
    }
    private fun withFile(text: String, test: (File) -> Unit) {
        val file = File(instrumentation.context.filesDir, "synthetic-reference-${UUID.randomUUID()}.json")
        try {
            file.writeText(text, Charsets.UTF_8)
            test(file)
        } finally { file.delete() }
    }
}
