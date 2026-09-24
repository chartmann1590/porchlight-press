package com.charleshartman.porchlightpress.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout parameters – Classic/Modern/Compact/Large Text are *parameters*,
 * not separate screen implementations (Phase 6 spec).
 * Stitch defaults: taller cinematic heroes, 20–28dp card radii.
 */
data class PorchlightLayout(
    val name: String,
    val columns: Int,
    val heroImageHeight: Dp,
    val cardImageHeight: Dp,
    val showDek: Boolean,
    val typeScaleFactor: Float,
    val contentPadding: Dp,
    val gutter: Dp,
    val cardRadius: Dp = 24.dp,
    val heroRadius: Dp = 28.dp,
)

val ClassicNewspaper = PorchlightLayout(
    name = "Classic",
    columns = 1,
    heroImageHeight = 320.dp,
    cardImageHeight = 180.dp,
    showDek = true,
    typeScaleFactor = 1f,
    contentPadding = 16.dp,
    gutter = 14.dp,
    cardRadius = 24.dp,
    heroRadius = 28.dp,
)
val ModernNewspaper = PorchlightLayout(
    name = "Modern",
    columns = 1,
    heroImageHeight = 300.dp,
    cardImageHeight = 160.dp,
    showDek = true,
    typeScaleFactor = 0.98f,
    contentPadding = 16.dp,
    gutter = 14.dp,
    cardRadius = 22.dp,
    heroRadius = 26.dp,
)
val CompactLayout = PorchlightLayout(
    name = "Compact",
    columns = 1,
    heroImageHeight = 240.dp,
    cardImageHeight = 130.dp,
    showDek = false,
    typeScaleFactor = 0.9f,
    contentPadding = 12.dp,
    gutter = 10.dp,
    cardRadius = 20.dp,
    heroRadius = 22.dp,
)
val LargeTextLayout = PorchlightLayout(
    name = "LargeText",
    columns = 1,
    heroImageHeight = 340.dp,
    cardImageHeight = 200.dp,
    showDek = true,
    typeScaleFactor = 1.2f,
    contentPadding = 16.dp,
    gutter = 14.dp,
    cardRadius = 24.dp,
    heroRadius = 28.dp,
)

val LocalLayout = compositionLocalOf { ClassicNewspaper }

/** Resolve columns from width + layout: 2 columns hero-spans on medium/expanded. */
@Composable
fun rememberColumns(layout: PorchlightLayout): Int {
    val width = LocalConfiguration.current.screenWidthDp
    return if (width >= 600) 2 else layout.columns
}

fun currentLayout(themePref: String, textScale: Double): PorchlightLayout {
    return when {
        textScale >= 1.3 -> LargeTextLayout
        themePref == "compact" -> CompactLayout
        themePref == "modern" -> ModernNewspaper
        else -> ClassicNewspaper
    }
}
