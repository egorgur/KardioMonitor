package ru.kardiomonitor.platform.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val MINUTES_PER_DAY = 24 * 60
private val LEFT_AXIS = 32.dp
private val BOTTOM_AXIS = 20.dp

/** Ближайшая к касанию точка не дальше 30 минут. */
private const val PICK_RADIUS_MINUTES = 30

/** Значение пульса в минуту суток (0–1439) — точка графика [HeartRateChart]. */
data class HeartRatePoint(val minuteOfDay: Int, val bpm: Int)

private fun hhmm(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

private fun nearestPoint(points: List<HeartRatePoint>, x: Float, left: Float, width: Float): HeartRatePoint? {
    val minute = ((x - left) / (width - left) * MINUTES_PER_DAY).roundToInt()
    return points.minByOrNull { abs(it.minuteOfDay - minute) }
        ?.takeIf { abs(it.minuteOfDay - minute) <= PICK_RADIUS_MINUTES }
}

/** Пульс за сутки поминутно. Разрывы линии там, где пульса нет дольше 3 минут. */
@Composable
fun HeartRateChart(points: List<HeartRatePoint>, modifier: Modifier = Modifier) {
    val colors = LocalChartColors.current
    val surface = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    var selected by remember(points) { mutableStateOf<HeartRatePoint?>(null) }

    val yMin = (floor(((points.minOfOrNull { it.bpm } ?: 60) - 5) / 20.0) * 20).toInt().coerceAtMost(40)
    val yMax = (ceil(((points.maxOfOrNull { it.bpm } ?: 120) + 5) / 20.0) * 20).toInt().coerceAtLeast(140)

    Column(modifier) {
        Text(
            selected?.let { "${hhmm(it.minuteOfDay)} · ${it.bpm} уд/мин" } ?: "Нажмите на график, чтобы увидеть значение",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                // касание и перетаскивание — в разных pointerInput: detectTapGestures не возвращает управление
                .pointerInput(points) {
                    detectTapGestures { selected = nearestPoint(points, it.x, LEFT_AXIS.toPx(), size.width.toFloat()) }
                }
                .pointerInput(points) {
                    detectDragGestures { change, _ ->
                        selected = nearestPoint(points, change.position.x, LEFT_AXIS.toPx(), size.width.toFloat())
                    }
                }
        ) {
            val plot = plotArea()
            val step = if (yMax - yMin > 120) 40 else 20
            drawYGrid(yMin, yMax, step, plot, colors, measurer)
            drawHourAxis(plot, colors, measurer)

            fun x(minute: Int) = plot.left + minute / MINUTES_PER_DAY.toFloat() * plot.width
            fun y(v: Int) = plot.bottom - (v - yMin) / (yMax - yMin).toFloat() * plot.height

            val path = Path()
            var prev: Int? = null
            for ((minute, value) in points) {
                if (prev == null || minute - prev > 3) path.moveTo(x(minute), y(value)) else path.lineTo(x(minute), y(value))
                prev = minute
            }
            drawPath(path, colors.series, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            selected?.let { (m, v) ->
                drawLine(colors.axisText, Offset(x(m), plot.top), Offset(x(m), plot.bottom), 1.dp.toPx())
                drawCircle(surface, 6.dp.toPx(), Offset(x(m), y(v)))
                drawCircle(colors.series, 4.dp.toPx(), Offset(x(m), y(v)))
            }
        }
    }
}

/** Шаги по часам: [hourlySteps] — 24 значения, с 00:00 до 23:00. */
@Composable
fun StepsChart(hourlySteps: List<Int>, modifier: Modifier = Modifier) {
    val colors = LocalChartColors.current
    val measurer = rememberTextMeasurer()
    val hours = hourlySteps
    var selected by remember(hours) { mutableStateOf<Int?>(null) }
    val maxSteps = hours.maxOrNull()?.takeIf { it > 0 } ?: 100
    val yMax = niceCeil(maxSteps)

    Column(modifier) {
        Text(
            selected?.let { h -> "%02d:00–%02d:00 · %d шагов".format(h, h + 1, hours[h]) }
                ?: "Нажмите на столбец, чтобы увидеть значение",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .pointerInput(hours) {
                    detectTapGestures { pos ->
                        val left = LEFT_AXIS.toPx()
                        val h = ((pos.x - left) / (size.width - left) * 24).toInt()
                        selected = h.takeIf { it in 0..23 }
                    }
                }
        ) {
            val plot = plotArea()
            drawYGrid(0, yMax, yMax / 2, plot, colors, measurer)
            drawHourAxis(plot, colors, measurer)

            val slot = plot.width / 24
            val gap = 2.dp.toPx()
            val radius = 4.dp.toPx()
            for (h in 0 until 24) {
                if (hours[h] == 0) continue
                val height = hours[h] / yMax.toFloat() * plot.height
                val left = plot.left + h * slot + gap / 2
                val width = slot - gap
                val top = plot.bottom - height
                val alpha = if (selected == null || selected == h) 1f else 0.4f
                // скругление только сверху: скруглённый прямоугольник + прямоугольник на нижнюю половину
                drawRoundRect(colors.series.copy(alpha = alpha), Offset(left, top), Size(width, height), CornerRadius(radius))
                if (height > radius) {
                    drawRect(colors.series.copy(alpha = alpha), Offset(left, plot.bottom - height / 2), Size(width, height / 2))
                }
            }
        }
    }
}

private fun niceCeil(value: Int): Int {
    val steps = listOf(100, 200, 500, 1000, 2000, 5000, 10000)
    return steps.firstOrNull { it >= value } ?: (ceil(value / 10000.0) * 10000).toInt()
}

private data class Plot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

private fun DrawScope.plotArea() = Plot(LEFT_AXIS.toPx(), 6.dp.toPx(), size.width, size.height - BOTTOM_AXIS.toPx())

private fun DrawScope.drawYGrid(
    min: Int, max: Int, step: Int, plot: Plot, colors: ChartColors,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val style = TextStyle(fontSize = 10.sp, color = colors.axisText)
    var v = min
    while (v <= max) {
        val y = plot.bottom - (v - min) / (max - min).toFloat() * plot.height
        drawLine(colors.grid, Offset(plot.left, y), Offset(plot.right, y), 1.dp.toPx())
        val label = measurer.measure(v.toString(), style)
        drawText(label, topLeft = Offset(plot.left - label.size.width - 4.dp.toPx(), y - label.size.height / 2))
        v += step
    }
}

private fun DrawScope.drawHourAxis(plot: Plot, colors: ChartColors, measurer: androidx.compose.ui.text.TextMeasurer) {
    val style = TextStyle(fontSize = 10.sp, color = colors.axisText)
    for (h in listOf(0, 6, 12, 18, 24)) {
        val label = measurer.measure("%02d".format(h), style)
        val x = plot.left + h / 24f * plot.width
        val left = (x - label.size.width / 2).coerceIn(plot.left, size.width - label.size.width)
        drawText(label, topLeft = Offset(left, plot.bottom + 4.dp.toPx()))
    }
}
