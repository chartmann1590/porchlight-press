package com.charleshartman.porchlightpress.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.charleshartman.porchlightpress.R

/**
 * Downloadable Google Fonts (OFL): Playfair Display for masthead/display,
 * Source Serif 4 for headlines, Source Sans 3 for body. Falls back to system
 * serif/sans if the provider is unavailable (emulator offline, etc.).
 */
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val playfair = GoogleFont("Playfair Display")
private val sourceSerif = GoogleFont("Source Serif 4")
private val sourceSans = GoogleFont("Source Sans 3")

private val SerifDisplay = FontFamily(
    Font(googleFont = playfair, fontProvider = provider, weight = FontWeight.Bold),
    Font(googleFont = playfair, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = playfair, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = playfair, fontProvider = provider, weight = FontWeight.Normal),
)

private val SerifHeadline = FontFamily(
    Font(googleFont = sourceSerif, fontProvider = provider, weight = FontWeight.Bold),
    Font(googleFont = sourceSerif, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = sourceSerif, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = sourceSerif, fontProvider = provider, weight = FontWeight.Normal),
)

private val BodySans = FontFamily(
    Font(googleFont = sourceSans, fontProvider = provider, weight = FontWeight.Bold),
    Font(googleFont = sourceSans, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = sourceSans, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = sourceSans, fontProvider = provider, weight = FontWeight.Normal),
)

val PorchlightTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SerifDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = SerifDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = SerifDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = SerifHeadline,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = SerifHeadline,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = SerifHeadline,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = SerifHeadline,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = BodySans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = BodySans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = BodySans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = BodySans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = BodySans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = BodySans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)
