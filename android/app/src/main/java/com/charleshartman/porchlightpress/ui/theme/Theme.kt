package com.charleshartman.porchlightpress.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val StitchLightScheme = lightColorScheme(
    primary = PorchlightColors.Crimson,
    onPrimary = Color.White,
    primaryContainer = PorchlightColors.Blush,
    onPrimaryContainer = PorchlightColors.Crimson,
    secondary = PorchlightColors.Sage,
    onSecondary = Color.White,
    secondaryContainer = PorchlightColors.SageContainer,
    onSecondaryContainer = PorchlightColors.Teal,
    tertiary = PorchlightColors.Amber,
    onTertiary = PorchlightColors.Ink,
    tertiaryContainer = PorchlightColors.Ivory,
    onTertiaryContainer = PorchlightColors.Ink,
    background = PorchlightColors.Newsprint,
    onBackground = PorchlightColors.Ink,
    surface = PorchlightColors.CardLight,
    onSurface = PorchlightColors.Ink,
    surfaceVariant = PorchlightColors.Ivory,
    onSurfaceVariant = PorchlightColors.InkMuted,
    outline = PorchlightColors.Rule,
    error = PorchlightColors.Rose,
    onError = Color.White,
    errorContainer = PorchlightColors.Blush,
    onErrorContainer = PorchlightColors.Crimson,
)

private val StitchDarkScheme = darkColorScheme(
    primary = PorchlightColors.Amber,
    onPrimary = PorchlightColors.CharcoalDeep,
    primaryContainer = PorchlightColors.CharcoalElevated,
    onPrimaryContainer = PorchlightColors.Cream,
    secondary = PorchlightColors.MintMeta,
    onSecondary = PorchlightColors.CharcoalDeep,
    secondaryContainer = Color(0xFF2A3A36),
    onSecondaryContainer = PorchlightColors.Cream,
    tertiary = PorchlightColors.CrimsonSoft,
    onTertiary = PorchlightColors.Cream,
    background = PorchlightColors.CharcoalDeep,
    onBackground = PorchlightColors.Cream,
    surface = PorchlightColors.Charcoal,
    onSurface = PorchlightColors.Cream,
    surfaceVariant = PorchlightColors.CharcoalElevated,
    onSurfaceVariant = PorchlightColors.CreamMuted,
    outline = Color(0xFF4A454C),
    error = PorchlightColors.CrimsonSoft,
    onError = PorchlightColors.Cream,
)

/** Soft elevated cards: 20–28dp radii per Stitch handoff. */
val PorchlightShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Porchlight Stitch themes. Classic + Modern both map to the Stitch visual
 * system (crimson newsprint / charcoal amber); Modern keeps a slightly cooler
 * surface variant. Layout is provided via [LocalLayout].
 */
@Composable
fun PorchlightTheme(
    themePref: String = "classic",
    layout: PorchlightLayout = ClassicNewspaper,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = when {
        darkTheme -> StitchDarkScheme
        themePref == "modern" -> StitchLightScheme.copy(
            background = Color(0xFFFFFAF5),
            surface = Color.White,
        )
        else -> StitchLightScheme
    }
    CompositionLocalProvider(LocalLayout provides layout) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PorchlightTypography,
            shapes = PorchlightShapes,
            content = content,
        )
    }
}
