@file:Suppress("DEPRECATION")

package ru.kardiomonitor.bandprotocol.xiaomi

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.ArrayDeque

/** Читает только уже открытый независимый tooltip; не навигирует и не пишет reference на диск. */
internal class XiaomiMiFitnessTooltip(private val instrumentation: Instrumentation) {
    class Failure(val stage: String) : IOException("mi_fitness_reference_capture_failed")
    class VisibleText(val text: String, val top: Int, val bottom: Int) {
        override fun toString(): String = "VisibleText"
    }
    fun capture(day: LocalDate): XiaomiHistoryReference {
        val first = reference(visibleTexts(), day)
        SystemClock.sleep(500)
        val second = reference(visibleTexts(), day)
        if (first != second) throw Failure("stability")
        return first
    }
    private fun activeRoot(): AccessibilityNodeInfo {
        repeat(8) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { return it }
            SystemClock.sleep(250)
        }
        throw Failure("root_missing")
    }
    private fun visibleTexts(): List<VisibleText> {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(activeRoot())
        val texts = mutableListOf<VisibleText>()
        var nodes = 1
        try {
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                try {
                    if (node.packageName?.toString() != "com.xiaomi.wearable") throw Failure("foreign_window")
                    for (index in 0 until node.childCount) {
                        if (nodes >= 256) throw Failure("node_limit")
                        node.getChild(index)?.let { pending.add(it); nodes++ }
                    }
                    if (!node.isVisibleToUser) continue
                    val rect = Rect().also(node::getBoundsInScreen)
                    node.text?.toString()?.let { texts.add(VisibleText(it, rect.top, rect.bottom)) }
                } finally { node.recycle() }
            }
            return texts
        } finally { pending.forEach { it.recycle() } }
    }
    companion object {
        fun reference(texts: List<VisibleText>, expected: LocalDate, zone: ZoneId = ZoneId.systemDefault()): XiaomiHistoryReference {
            val format = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)
            val dates = texts.filter { it.top in 100..300 && it.text.matches(Regex("\\d{2}\\.\\d{2}\\.\\d{4}")) }
                .map { LocalDate.parse(it.text, format) }.distinct()
            if (dates.isEmpty()) throw Failure("date_missing")
            if (dates != listOf(expected)) throw Failure("date_mismatch")
            if (texts.any { it.text == "Нет данных" }) throw Failure("no_data")
            val tooltips = texts.filter { it.top in 400..1000 }
            val heart = tooltips.filter { it.text.matches(Regex("[0-9]{1,3} BPM")) }.singleOrNull()
                ?: throw Failure("heart_rate_tooltip_missing")
            val time = tooltips.filter { it.text.matches(Regex("[0-9]{2}:[0-9]{2}")) }.singleOrNull()
                ?: throw Failure("time_tooltip_missing")
            if (heart.top != time.top) throw Failure("tooltip_position_mismatch")
            val local = expected.atTime(LocalTime.parse(time.text))
            val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: throw Failure("timezone_ambiguous")
            val hr = heart.text.substringBefore(' ').toInt()
            if (hr !in 1..254) throw Failure("invalid_heart_rate")
            return XiaomiHistoryReference(local.toEpochSecond(offset), offset.totalSeconds, hr, null)
        }
    }
}
