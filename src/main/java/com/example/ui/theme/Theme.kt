package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppTheme(val titleAr: String, val primaryColorHex: Long) {
    LIGHT("فاتح عصري", 0xFF2563EB),
    DARK("داكن فخم", 0xFF0F172A),
    CRIMSON("أحمر فاخر", 0xFFBE123C),
    NAVY("كحلي ملكي", 0xFF1D4ED8),
    EMERALD("زمردي فاخر", 0xFF047857),
    AMBER("ذهبي عنبري", 0xFFD97706)
}

private val LightPalette = lightColorScheme(
    primary = ClassicPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = ClassicSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF312E81),
    tertiary = ClassicTertiary,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = Color(0xFF0F172A),
    surface = LightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

private val DarkPalette = darkColorScheme(
    primary = CharcoalPrimary,
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF0369A1),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = CharcoalSecondary,
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF4338CA),
    onSecondaryContainer = Color(0xFFEEF2FF),
    tertiary = CharcoalTertiary,
    onTertiary = Color(0xFF0F172A),
    background = DarkBackground,
    onBackground = Color(0xFFF8FAFC),
    surface = DarkSurface,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF475569)
)

private val CrimsonPalette = lightColorScheme(
    primary = CrimsonPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4E6),
    onPrimaryContainer = Color(0xFF881337),
    secondary = CrimsonSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFECDD3),
    onSecondaryContainer = Color(0xFF9F1239),
    tertiary = AmberPrimary,
    onTertiary = Color.White,
    background = CrimsonBackgroundLight,
    onBackground = Color(0xFF1C0A10),
    surface = CrimsonSurfaceLight,
    onSurface = Color(0xFF1C0A10),
    surfaceVariant = Color(0xFFFFE4E6),
    onSurfaceVariant = Color(0xFF701A75),
    outline = Color(0xFFFDA4AF)
)

private val NavyPalette = lightColorScheme(
    primary = NavyPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF172554),
    secondary = NavySecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBFDBFE),
    onSecondaryContainer = Color(0xFF1E3A8A),
    tertiary = AmberSecondary,
    onTertiary = Color.White,
    background = NavyBackgroundLight,
    onBackground = Color(0xFF0F172A),
    surface = NavySurfaceLight,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE0E7FF),
    onSurfaceVariant = Color(0xFF334155),
    outline = Color(0xFF93C5FD)
)

private val EmeraldPalette = lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = EmeraldSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFA7F3D0),
    onSecondaryContainer = Color(0xFF065F46),
    tertiary = AmberPrimary,
    onTertiary = Color.White,
    background = EmeraldBackgroundLight,
    onBackground = Color(0xFF06281D),
    surface = EmeraldSurfaceLight,
    onSurface = Color(0xFF06281D),
    surfaceVariant = Color(0xFFD1FAE5),
    onSurfaceVariant = Color(0xFF044E36),
    outline = Color(0xFF6EE7B7)
)

private val AmberPalette = lightColorScheme(
    primary = AmberPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEF3C7),
    onPrimaryContainer = Color(0xFF78350F),
    secondary = AmberSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDE68A),
    onSecondaryContainer = Color(0xFF92400E),
    tertiary = EmeraldSecondary,
    onTertiary = Color.White,
    background = AmberBackgroundLight,
    onBackground = Color(0xFF231707),
    surface = AmberSurfaceLight,
    onSurface = Color(0xFF231707),
    surfaceVariant = Color(0xFFFEF3C7),
    onSurfaceVariant = Color(0xFF5A3E1B),
    outline = Color(0xFFFCD34D)
)

fun getThemeColorScheme(theme: AppTheme): ColorScheme {
    return when (theme) {
        AppTheme.LIGHT -> LightPalette
        AppTheme.DARK -> DarkPalette
        AppTheme.CRIMSON -> CrimsonPalette
        AppTheme.NAVY -> NavyPalette
        AppTheme.EMERALD -> EmeraldPalette
        AppTheme.AMBER -> AmberPalette
    }
}

@Composable
fun SmartInventoryTheme(
    theme: AppTheme = AppTheme.LIGHT,
    content: @Composable () -> Unit
) {
    val colorScheme = getThemeColorScheme(theme)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
