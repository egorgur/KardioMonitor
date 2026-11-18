package ru.kardiomonitor.staff.platform.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object QrPalette {
    val Background = Color.White
    val Foreground = Color(0xFF101816)
    val SecondaryText = Color(0xFF3F4946)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF075E52),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA4F2DE),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF456179),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E5FF),
    onSecondaryContainer = Color(0xFF001D33),
    tertiary = Color(0xFF865300),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF2A1700),
    background = Color(0xFFF2F8F6),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFFAFDFC),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDBE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF88D6C3),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFFA4F2DE),
    secondary = Color(0xFFADC9E5),
    onSecondary = Color(0xFF153349),
    secondaryContainer = Color(0xFF2D4A61),
    onSecondaryContainer = Color(0xFFD1E5FF),
    tertiary = Color(0xFFFFB95F),
    onTertiary = Color(0xFF482900),
    tertiaryContainer = Color(0xFF663D00),
    onTertiaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDFE4E1),
    surface = Color(0xFF171D1B),
    onSurface = Color(0xFFDFE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C5),
    outline = Color(0xFF89938F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val StaffShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun StaffTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = StaffShapes,
        content = content,
    )
}
