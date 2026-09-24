package com.charleshartman.porchlightpress.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Stitch typography: serif display for masthead/heroes/article titles;
 * clean sans for body, chips, metadata. Hero ~32–40sp, article title 22–28sp.
 * System Serif/Sans keep the $0 font budget (MASTER_PLAN).
 */
private val SerifDisplay = FontFamily.Serif
private val BodySans = FontFamily.SansSerif

val PorchlightTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SerifDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.4).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = SerifDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.3).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = SerifDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = SerifDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = SerifDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = SerifDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = SerifDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = SerifDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = BodySans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
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
        fontWeight = FontWeight.Medium,
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
        lineHeight = 14.sp,
        letterSpacing = 0.7.sp,
    ),
)
