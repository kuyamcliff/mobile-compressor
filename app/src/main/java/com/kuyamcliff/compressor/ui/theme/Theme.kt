package com.kuyamcliff.compressor.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kuyamcliff.compressor.data.prefs.ThemeMode

// A restrained, technical palette: ink neutrals with one teal accent.
private val Teal = Color(0xFF00897B)
private val TealLight = Color(0xFF4FD1C5)
private val Ink = Color(0xFF13202E)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFEA),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A6370),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE7EE),
    onSecondaryContainer = Color(0xFF0E1F28),
    tertiary = Color(0xFF7A5900),
    tertiaryContainer = Color(0xFFFFE1A6),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFF8F9FB),
    onBackground = Ink,
    surface = Color(0xFFF8F9FB),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE3E8EC),
    onSurfaceVariant = Color(0xFF43505A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F4F7),
    surfaceContainer = Color(0xFFECEFF2),
    surfaceContainerHigh = Color(0xFFE6E9ED),
    outline = Color(0xFF73808A),
    outlineVariant = Color(0xFFC3CBD2),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFFA8F2E6),
    secondary = Color(0xFFB2C9D6),
    onSecondary = Color(0xFF1D333E),
    secondaryContainer = Color(0xFF344A56),
    onSecondaryContainer = Color(0xFFCEE5F2),
    tertiary = Color(0xFFF2C14E),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFDF9A),
    background = Color(0xFF0F1419),
    onBackground = Color(0xFFE1E6EA),
    surface = Color(0xFF0F1419),
    onSurface = Color(0xFFE1E6EA),
    surfaceVariant = Color(0xFF3F4950),
    onSurfaceVariant = Color(0xFFBFC8CF),
    surfaceContainerLowest = Color(0xFF0A0F13),
    surfaceContainerLow = Color(0xFF171D22),
    surfaceContainer = Color(0xFF1B2127),
    surfaceContainerHigh = Color(0xFF252C32),
    outline = Color(0xFF89939B),
    outlineVariant = Color(0xFF3F4950),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/** Semantic colours not in Material's scheme (status pills), with text-safe contrast. */
@Immutable
data class StatusColors(val success: Color, val onSuccess: Color, val warning: Color, val onWarning: Color)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(Color(0xFF1E7B34), Color.White, Color(0xFF8A5A00), Color.White)
}

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/** Monospaced style for technical values (codec components, numbers). */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun CompressorTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    val status = if (dark) StatusColors(Color(0xFF7BD88F), Color(0xFF00390F), Color(0xFFF2C14E), Color(0xFF3F2E00))
    else StatusColors(Color(0xFF1E7B34), Color.White, Color(0xFF8A5A00), Color.White)
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
