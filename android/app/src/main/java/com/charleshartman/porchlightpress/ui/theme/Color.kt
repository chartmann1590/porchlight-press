package com.charleshartman.porchlightpress.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Classic — warm newsprint, lamp-ink, deep crimson accent (editorial paper)
// ---------------------------------------------------------------------------
val PaperCream = Color(0xFFF7EFDC)
val PaperWarm = Color(0xFFFDF6E3)
val PaperAged = Color(0xFFEDE3CB)
val PaperEdge = Color(0xFFD9CDB8)
val InkBlack = Color(0xFF141210)
val InkCharcoal = Color(0xFF2A2622)
val InkMuted = Color(0xFF5C534A)
val CrimsonDeep = Color(0xFF9B1B1B)
val CrimsonHot = Color(0xFFC62828)
val GoldLamp = Color(0xFFC9A227)
val ClassicDarkBg = Color(0xFF12100E)
val ClassicDarkSurface = Color(0xFF1E1A16)
val ClassicDarkElevated = Color(0xFF2C2620)
val ClassicDarkInk = Color(0xFFF0E6D2)
val ClassicDarkMuted = Color(0xFFB8A990)
val ClassicDarkRule = Color(0xFF4A4036)
val ClassicDarkAccent = Color(0xFFFF6B6B)

// ---------------------------------------------------------------------------
// Modern — premium editorial night/day (Bloomberg-light / Apple News energy)
// ---------------------------------------------------------------------------
val ModernInk = Color(0xFF0A0E17)
val ModernSlate = Color(0xFF141B2D)
val ModernSurface = Color(0xFFF4F6FA)
val ModernCard = Color(0xFFFFFFFF)
val ModernElevated = Color(0xFFE8ECF4)
val ModernPrimary = Color(0xFF1A56DB)
val ModernPrimaryBright = Color(0xFF3B82F6)
val ModernAccent = Color(0xFF0EA5E9)
val ModernOnInk = Color(0xFFF8FAFC)
val ModernMuted = Color(0xFF64748B)
val ModernOutline = Color(0xFFCBD5E1)
val ModernDarkBg = Color(0xFF070B14)
val ModernDarkSurface = Color(0xFF0F1624)
val ModernDarkCard = Color(0xFF1A2336)
val ModernDarkElevated = Color(0xFF243049)
val ModernDarkPrimary = Color(0xFF60A5FA)
val ModernDarkMuted = Color(0xFF94A3B8)
val ModernDarkOutline = Color(0xFF334155)

// Legacy aliases kept so any older references compile.
val PaperLight = PaperWarm
val PaperDark = ClassicDarkBg
val InkLight = InkBlack
val InkMedium = InkMuted
val RuleLight = PaperEdge
val RuleDark = ClassicDarkRule
val AccentClassic = CrimsonDeep
val AccentModern = ModernPrimary

/** Soft paper wash for Classic light backgrounds. */
fun classicPaperBrush(): Brush = Brush.verticalGradient(
    colors = listOf(PaperWarm, PaperCream, PaperAged.copy(alpha = 0.55f)),
)

/** Subtle tonal wash for Modern light surfaces. */
fun modernSurfaceBrush(): Brush = Brush.verticalGradient(
    colors = listOf(ModernSurface, Color(0xFFEEF2FF), ModernElevated),
)

/** Cinematic hero scrim — bottom-heavy for legible type over imagery. */
fun heroScrimBrush(dark: Boolean): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color.Transparent,
        0.35f to Color.Black.copy(alpha = if (dark) 0.25f else 0.15f),
        0.7f to Color.Black.copy(alpha = if (dark) 0.72f else 0.62f),
        1.0f to Color.Black.copy(alpha = if (dark) 0.92f else 0.88f),
    ),
)
