package com.voicekanban.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.R
import com.voicekanban.app.data.Priority
import com.voicekanban.app.data.ThemeMode

/**
 * Design system: soft sage-porcelain ground (#EFF1EC), crisp white cards (#FFFFFF),
 * muted sage-grey secondary blocks (#B5BDB4), electric chartreuse-lime highlight (#EAFF87),
 * and deep botanical ink (#1B201C). Pill & squircle corners (16-28 dp) and smooth springs.
 */
@Immutable
data class AppColors(
    val background: Color,      // soft sage-porcelain page ground
    val surface: Color,         // outer container / subtle card
    val surfaceWell: Color,     // inset inputs / wells
    val raised: Color,          // pure white elevated cards & pills
    val sageTile: Color,        // muted sage-grey stat & task card block (#B5BDB4)
    val limeTile: Color,        // electric chartreuse-lime highlight block (#EAFF87)
    val onLime: Color,          // dark ink on lime highlight
    val border: Color,          // soft border
    val borderStrong: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val accent: Color,          // deep botanical green / lime accent for active marks
    val accentText: Color,      // high-contrast botanical green for text
    val accentSoft: Color,      // soft lime-tinted surface
    val onAccent: Color,
    val voicePrimary: Color,    // Voice AI gradient start
    val voiceSecondary: Color,  // Voice AI gradient mid
    val voiceTertiary: Color,   // Voice AI gradient end
    val high: Color, val highSoft: Color,
    val medium: Color, val mediumSoft: Color,
    val low: Color, val lowSoft: Color,
    val danger: Color, val dangerSoft: Color,
    val recording: Color,
    val snackbar: Color, val onSnackbar: Color, val snackbarAction: Color,
    val isDark: Boolean,
) {
    fun priorityFg(p: Priority) = when (p) { Priority.HIGH -> high; Priority.MEDIUM -> medium; Priority.LOW -> low }
    fun priorityBg(p: Priority) = when (p) { Priority.HIGH -> highSoft; Priority.MEDIUM -> mediumSoft; Priority.LOW -> lowSoft }
}

val LightColors = AppColors(
    background = Color(0xFFEFF1EC),
    surface = Color(0xFFF7F8F5),
    surfaceWell = Color(0xFFE5E8E1),
    raised = Color(0xFFFFFFFF),
    sageTile = Color(0xFFB5BDB4),
    limeTile = Color(0xFFEAFF87),
    onLime = Color(0xFF1B201C),
    border = Color(0xFFE1E5DC),
    borderStrong = Color(0xFFCCD2C7),
    ink = Color(0xFF1B201C),
    inkSecondary = Color(0xFF525A53),
    inkTertiary = Color(0xFF7B847C),
    accent = Color(0xFF1B201C),
    accentText = Color(0xFF3E5A12),
    accentSoft = Color(0xFFEAFF87),
    onAccent = Color(0xFFFFFFFF),
    voicePrimary = Color(0xFFEAFF87),
    voiceSecondary = Color(0xFF8BE28B),
    voiceTertiary = Color(0xFF23533A),
    high = Color(0xFFB42318), highSoft = Color(0xFFFBEAE7),
    medium = Color(0xFF6B5900), mediumSoft = Color(0xFFEAFF87),
    low = Color(0xFF1E5E3A), lowSoft = Color(0xFFDFF2E6),
    danger = Color(0xFFB42318), dangerSoft = Color(0xFFFBEAE7),
    recording = Color(0xFF3E5A12),
    snackbar = Color(0xFF1B201C), onSnackbar = Color(0xFFF7F8F5), snackbarAction = Color(0xFFEAFF87),
    isDark = false,
)

val DarkColors = AppColors(
    background = Color(0xFF121613),
    surface = Color(0xFF1B211D),
    surfaceWell = Color(0xFF232B26),
    raised = Color(0xFF212823),
    sageTile = Color(0xFF354038),
    limeTile = Color(0xFFD9F26E),
    onLime = Color(0xFF121613),
    border = Color(0xFF2A332D),
    borderStrong = Color(0xFF3D4940),
    ink = Color(0xFFF4F6F2),
    inkSecondary = Color(0xFFB8C0B8),
    inkTertiary = Color(0xFF859086),
    accent = Color(0xFFEAFF87),
    accentText = Color(0xFFEAFF87),
    accentSoft = Color(0xFF2B381B),
    onAccent = Color(0xFF121613),
    voicePrimary = Color(0xFFEAFF87),
    voiceSecondary = Color(0xFF5AD28A),
    voiceTertiary = Color(0xFF1B4D3E),
    high = Color(0xFFF28B82), highSoft = Color(0xFF3A1F1C),
    medium = Color(0xFFEAFF87), mediumSoft = Color(0xFF2B381B),
    low = Color(0xFF7DCFA0), lowSoft = Color(0xFF173023),
    danger = Color(0xFFF28B82), dangerSoft = Color(0xFF3A1F1C),
    recording = Color(0xFFEAFF87),
    snackbar = Color(0xFFF4F6F2), onSnackbar = Color(0xFF121613), snackbarAction = Color(0xFF3E5A12),
    isDark = true,
)

val LocalAppColors = staticCompositionLocalOf { LightColors }

object VK {
    val colors: AppColors @Composable get() = LocalAppColors.current
}

@OptIn(ExperimentalTextApi::class)
private fun host(weight: Int) = Font(R.font.host_grotesk, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val HostGrotesk = FontFamily(host(400), host(500), host(600), host(700))

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = HostGrotesk, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal, letterSpacing = (-1.1).sp),
    titleLarge = TextStyle(fontFamily = HostGrotesk, fontSize = 28.sp, lineHeight = 33.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.7).sp),
    headlineSmall = TextStyle(fontFamily = HostGrotesk, fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontFamily = HostGrotesk, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontFamily = HostGrotesk, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = HostGrotesk, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontFamily = HostGrotesk, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = HostGrotesk, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = HostGrotesk, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = HostGrotesk, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

@Composable
fun VoiceKanbanTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val c = if (dark) DarkColors else LightColors
    val common = { s: androidx.compose.material3.ColorScheme ->
        s.copy(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.onLime,
            secondary = c.accentText, background = c.background, onBackground = c.ink, surface = c.raised, onSurface = c.ink,
            surfaceVariant = c.surface, onSurfaceVariant = c.inkSecondary, outline = c.borderStrong, outlineVariant = c.border,
            error = c.danger, surfaceContainer = c.raised, surfaceContainerHigh = c.raised, surfaceContainerLow = c.raised,
            surfaceContainerHighest = c.surfaceWell, surfaceContainerLowest = c.background,
        )
    }
    val scheme = common(if (dark) darkColorScheme() else lightColorScheme())
    CompositionLocalProvider(LocalAppColors provides c) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(18.dp),
                large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
            ),
        ) {
            androidx.compose.material3.ProvideTextStyle(TextStyle(fontFamily = HostGrotesk), content)
        }
    }
}
