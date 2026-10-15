package ru.kardiomonitor.platform.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Цвета графиков: одна серия на график, свой оттенок для тёмной поверхности. */
data class ChartColors(val series: Color, val grid: Color, val axisText: Color)

val LocalChartColors = staticCompositionLocalOf { lightChart }

private val lightChart = ChartColors(series = Color(0xFF2A78D6), grid = Color(0xFFE4E3DF), axisText = Color(0xFF52514E))
private val darkChart = ChartColors(series = Color(0xFF3987E5), grid = Color(0xFF34332F), axisText = Color(0xFFC3C2B7))

private val lightScheme = lightColorScheme(
    primary = Color(0xFF2A78D6),
    onPrimary = Color.White,
    background = Color(0xFFF4F4F2),
    surface = Color(0xFFFCFCFB),
    surfaceVariant = Color(0xFFFCFCFB),
    onSurface = Color(0xFF0B0B0B),
    onSurfaceVariant = Color(0xFF52514E),
    error = Color(0xFFC62828),
)

private val darkScheme = darkColorScheme(
    primary = Color(0xFF3987E5),
    onPrimary = Color.White,
    background = Color(0xFF111110),
    surface = Color(0xFF1A1A19),
    surfaceVariant = Color(0xFF1A1A19),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFC3C2B7),
    error = Color(0xFFEF9A9A),
)

@Composable
fun KardioTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalChartColors provides if (dark) darkChart else lightChart) {
        MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, content = content)
    }
}
