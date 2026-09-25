package com.charleshartman.porchlightpress.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ClassicLightScheme = lightColorScheme(
    primary = CrimsonDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = GoldLamp,
    onSecondary = InkBlack,
    secondaryContainer = Color(0xFFFFE08A),
    onSecondaryContainer = Color(0xFF241A00),
    tertiary = InkCharcoal,
    onTertiary = PaperWarm,
    background = PaperWarm,
    onBackground = InkBlack,
    surface = PaperCream,
    onSurface = InkBlack,
    surfaceVariant = PaperAged,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = PaperWarm,
    surfaceContainerLow = PaperCream,
    surfaceContainer = PaperAged,
    surfaceContainerHigh = PaperEdge,
    surfaceContainerHighest = Color(0xFFD0C4AE),
    outline = PaperEdge,
    outlineVariant = Color(0xFFC4B59A),
    error = CrimsonHot,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val ClassicDarkScheme = darkColorScheme(
    primary = ClassicDarkAccent,
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = GoldLamp,
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF5B4300),
    onSecondaryContainer = Color(0xFFFFE08A),
    tertiary = ClassicDarkMuted,
    onTertiary = ClassicDarkBg,
    background = ClassicDarkBg,
    onBackground = ClassicDarkInk,
    surface = ClassicDarkSurface,
    onSurface = ClassicDarkInk,
    surfaceVariant = ClassicDarkElevated,
    onSurfaceVariant = ClassicDarkMuted,
    surfaceContainerLowest = Color(0xFF0C0A08),
    surfaceContainerLow = ClassicDarkSurface,
    surfaceContainer = ClassicDarkElevated,
    surfaceContainerHigh = Color(0xFF3A322A),
    surfaceContainerHighest = Color(0xFF483E34),
    outline = ClassicDarkRule,
    outlineVariant = Color(0xFF5C5044),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val ModernLightScheme = lightColorScheme(
    primary = ModernPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = ModernAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8EFFF),
    onSecondaryContainer = Color(0xFF001E2B),
    tertiary = Color(0xFF6366F1),
    onTertiary = Color.White,
    background = ModernSurface,
    onBackground = ModernInk,
    surface = ModernCard,
    onSurface = ModernInk,
    surfaceVariant = ModernElevated,
    onSurfaceVariant = ModernMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = ModernCard,
    surfaceContainer = ModernElevated,
    surfaceContainerHigh = Color(0xFFDDE3F0),
    surfaceContainerHighest = Color(0xFFD0D8E8),
    outline = ModernOutline,
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color.White,
)

private val ModernDarkScheme = darkColorScheme(
    primary = ModernDarkPrimary,
    onPrimary = Color(0xFF002F6C),
    primaryContainer = Color(0xFF004A9F),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = ModernAccent,
    onSecondary = Color(0xFF003547),
    secondaryContainer = Color(0xFF004D65),
    onSecondaryContainer = Color(0xFFC8EFFF),
    tertiary = Color(0xFFA5B4FC),
    onTertiary = Color(0xFF1E1B4B),
    background = ModernDarkBg,
    onBackground = ModernOnInk,
    surface = ModernDarkSurface,
    onSurface = ModernOnInk,
    surfaceVariant = ModernDarkCard,
    onSurfaceVariant = ModernDarkMuted,
    surfaceContainerLowest = Color(0xFF05080F),
    surfaceContainerLow = ModernDarkSurface,
    surfaceContainer = ModernDarkCard,
    surfaceContainerHigh = ModernDarkElevated,
    surfaceContainerHighest = Color(0xFF2E3A54),
    outline = ModernDarkOutline,
    outlineVariant = Color(0xFF1E293B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/** Editorial shapes — larger card radii, pill chips. */
val PorchlightShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val LocalIsClassic = staticCompositionLocalOf { true }
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * Porchlight newspaper themes: Classic (warm paper, crimson, serif-heavy) and
 * Modern (premium tonal surfaces, refined blue). Distinctive — not Material
 * defaults with a tint.
 */
@Composable
fun PorchlightTheme(
    themePref: String = "classic",
    layout: PorchlightLayout = ClassicNewspaper,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val isClassic = themePref != "modern"
    val scheme = when {
        themePref == "modern" && darkTheme -> ModernDarkScheme
        themePref == "modern" -> ModernLightScheme
        darkTheme -> ClassicDarkScheme
        else -> ClassicLightScheme
    }
    CompositionLocalProvider(
        LocalLayout provides layout,
        LocalIsClassic provides isClassic,
        LocalIsDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PorchlightTypography,
            shapes = PorchlightShapes,
            content = content,
        )
    }
}
