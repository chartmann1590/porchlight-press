package com.charleshartman.porchlightpress.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Google Stitch design tokens for Porchlight Press.
 * Light = warm newsprint; dark = charcoal porch-lamp night.
 */
object PorchlightColors {
    // Light surfaces — newsprint / ivory
    val Newsprint = Color(0xFFFFF8F0)
    val Ivory = Color(0xFFF3E8DC)
    val CardLight = Color(0xFFFFFCF7)
    val LilacTint = Color(0xFFEDE4F0)

    // Primary — deep crimson / burgundy
    val Crimson = Color(0xFF8F2030)
    val CrimsonRich = Color(0xFFA62F45)
    val CrimsonSoft = Color(0xFFC45A6A)

    // Ink
    val Ink = Color(0xFF1C1A1F)
    val InkMuted = Color(0xFF5C5660)
    val Rule = Color(0xFFDCCFBF)

    // Dark mode — charcoal + amber porch light
    val Charcoal = Color(0xFF24242A)
    val CharcoalElevated = Color(0xFF2D2B31)
    val CharcoalDeep = Color(0xFF1A191E)
    val Amber = Color(0xFFF5B84B)
    val Cream = Color(0xFFFFF4DF)
    val CreamMuted = Color(0xFFD9CDB8)

    // Status / pills
    val Sage = Color(0xFF5F8F7A)
    val SageContainer = Color(0xFFD8EDE4)
    val Teal = Color(0xFF3D7A7A)
    val TealContainer = Color(0xFFD5EBEB)
    val Blush = Color(0xFFF3D4D8)
    val BlushDeep = Color(0xFFE8B4BC)
    val Rose = Color(0xFFB54A5A)
    val MintMeta = Color(0xFF6FA89A)

    // Scrim / hero
    val ScrimTop = Color(0x00000000)
    val ScrimMid = Color(0x99000000)
    val ScrimBottom = Color(0xE6000000)
}

/** Cinematic bottom-heavy scrim for hero imagery. */
fun stitchHeroScrim(): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color.Transparent,
        0.28f to Color.Black.copy(alpha = 0.12f),
        0.55f to Color.Black.copy(alpha = 0.45f),
        0.78f to Color.Black.copy(alpha = 0.72f),
        1.0f to Color.Black.copy(alpha = 0.88f),
    ),
)

// Legacy aliases so any older references keep compiling.
val PaperLight = PorchlightColors.Newsprint
val PaperDark = PorchlightColors.CharcoalDeep
val InkLight = PorchlightColors.Ink
val InkMedium = PorchlightColors.InkMuted
val RuleLight = PorchlightColors.Rule
val RuleDark = Color(0xFF4A454C)
val AccentClassic = PorchlightColors.Crimson
val AccentModern = PorchlightColors.CrimsonRich
