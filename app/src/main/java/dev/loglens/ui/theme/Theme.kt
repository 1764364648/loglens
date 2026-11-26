package dev.loglens.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

/*
 * 配色走 Material 3 语义角色：界面只认 primary / surfaceVariant / error 这些名字，
 * 不直接写死色值，深浅色各给一套，系统切主题时整页一起变。
 */

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9DC0FF),
    onPrimary = Color(0xFF00305F),
    primaryContainer = Color(0xFF10487F),
    onPrimaryContainer = Color(0xFFD4E3FF),
    secondary = Color(0xFF5FD6E8),
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF004F58),
    onSecondaryContainer = Color(0xFFA8EFF8),
    tertiary = Color(0xFFC9B6FF),
    onTertiary = Color(0xFF32245C),
    tertiaryContainer = Color(0xFF493B74),
    onTertiaryContainer = Color(0xFFE9DDFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE4E7EE),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFE4E7EE),
    surfaceVariant = Color(0xFF1A2029),
    onSurfaceVariant = Color(0xFFAAB4C3),
    outline = Color(0xFF5A6573),
    outlineVariant = Color(0xFF2A323D),
    inverseSurface = Color(0xFFE4E7EE),
    inverseOnSurface = Color(0xFF2A3038),
    inversePrimary = Color(0xFF2A5D95)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1B5E9B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E3FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF006874),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFA8EFF8),
    onSecondaryContainer = Color(0xFF001F24),
    tertiary = Color(0xFF5B4B8A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9DDFF),
    onTertiaryContainer = Color(0xFF170A3D),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8FAFF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8FAFF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE2E6EF),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6CF),
    inverseSurface = Color(0xFF2E3135),
    inverseOnSurface = Color(0xFFEFF1F6),
    inversePrimary = Color(0xFF9DC0FF)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
    )
)

/**
 * 日志级别的颜色。等级色是信息本身的一部分，不跟随 Material 主题走，
 * 所以单独放一套，深浅色各配一遍。
 */
@Immutable
data class LogPalette(
    val verbose: Color,
    val debug: Color,
    val info: Color,
    val warn: Color,
    val error: Color,
    val raw: Color,
    val crashBackground: Color,
    val codeBackground: Color
)

private val DarkLogPalette = LogPalette(
    verbose = Color(0xFF8C96A5),
    debug = Color(0xFF7FB2FF),
    info = Color(0xFF4ADE9B),
    warn = Color(0xFFFFC24B),
    error = Color(0xFFFF8585),
    raw = Color(0xFFC1A2FF),
    crashBackground = Color(0x1FFF8585),
    codeBackground = Color(0xFF0A0E14)
)

private val LightLogPalette = LogPalette(
    verbose = Color(0xFF667085),
    debug = Color(0xFF1F63C8),
    info = Color(0xFF13784A),
    warn = Color(0xFF9A5B00),
    error = Color(0xFFB3261E),
    raw = Color(0xFF6544A8),
    crashBackground = Color(0x14B3261E),
    codeBackground = Color(0xFFF1F4F9)
)

val LocalLogPalette = staticCompositionLocalOf { DarkLogPalette }

@Composable
fun LogLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalLogPalette provides if (darkTheme) DarkLogPalette else LightLogPalette
    ) {
        MiuixTheme(
            colors = if (darkTheme) miuixDarkColorScheme() else miuixLightColorScheme()
        ) {
            MaterialTheme(
                colorScheme = if (darkTheme) DarkScheme else LightScheme,
                typography = AppTypography,
                content = content
            )
        }
    }
}
