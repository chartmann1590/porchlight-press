package com.charleshartman.porchlightpress.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val ClassicLightScheme = lightColorScheme(
    primary = AccentClassic,
    onPrimary = Color.White,
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = Color(0xFFEDE6D6),
    onSurfaceVariant = InkMedium,
    outline = RuleLight,
)
private val ClassicDarkScheme = darkColorScheme(
    primary = Color(0xFFE57373),
    onPrimary = Color.Black,
    background = PaperDark,
    onBackground = Color(0xFFE8E2D5),
    surface = Color(0xFF252220),
    onSurface = Color(0xFFE8E2D5),
    surfaceVariant = Color(0xFF3A352F),
    onSurfaceVariant = Color(0xFFCFC8BA),
    outline = RuleDark,
)
private val ModernLightScheme = lightColorScheme(
    primary = AccentModern,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    outline = Color(0xFFE0E0E0),
)
private val ModernDarkScheme = darkColorScheme(
    primary = Color(0xFF82B1FF),
    onPrimary = Color.Black,
    background = Color(0xFF121212),
    onBackground = Color.White,
    surface = Color(0xFF1E1E1E),
    onSurface = Color.White,
    outline = Color(0xFF3A3A3A),
)

/**
 * Porchlight newspaper themes: Classic (paper tone, serif-heavy) and Modern
 * (clean Material 3), each with light/dark.
 * Theme/layout are stored in DataStore (Prefs theme/layout); darkTheme follows
 * system unless the theme pref forces one variant. Type scale follows
 * system fontScale via sp units (Phase 6 spec).
 */
@Composable
fun PorchlightTheme(
    themePref: String = "classic",
    layout: PorchlightLayout = ClassicNewspaper,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = when {
        themePref == "modern" && darkTheme -> ModernDarkScheme
        themePref == "modern" -> ModernLightScheme
        themePref == "classic" && darkTheme -> ClassicDarkScheme
        darkTheme -> ClassicDarkScheme
        themePref == "classic" -> ClassicLightScheme
        else -> if (darkTheme) darkColorScheme() else lightColorScheme()
    }
    CompositionLocalProvider(LocalLayout provides layout) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PorchlightTypography,
            content = content,
        )
    }
}
