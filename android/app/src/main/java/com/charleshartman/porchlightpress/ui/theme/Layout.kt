package com.charleshartman.porchlightpress.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout parameters – Classic/Modern/Compact/Large Text are *parameters*,
 * not separate screen implementations (Phase 6 spec).
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
)

val ClassicNewspaper = PorchlightLayout(
    name = "Classic",
    columns = 1,
    heroImageHeight = 220.dp,
    cardImageHeight = 160.dp,
    showDek = true,
    typeScaleFactor = 1f,
    contentPadding = 16.dp,
    gutter = 12.dp,
)
val ModernNewspaper = PorchlightLayout(
    name = "Modern",
    columns = 1,
    heroImageHeight = 200.dp,
    cardImageHeight = 140.dp,
    showDek = true,
    typeScaleFactor = 0.95f,
    contentPadding = 16.dp,
    gutter = 12.dp,
)
val CompactLayout = PorchlightLayout(
    name = "Compact",
    columns = 1,
    heroImageHeight = 160.dp,
    cardImageHeight = 120.dp,
    showDek = false,
    typeScaleFactor = 0.9f,
    contentPadding = 12.dp,
    gutter = 8.dp,
)
val LargeTextLayout = PorchlightLayout(
    name = "LargeText",
    columns = 1,
    heroImageHeight = 240.dp,
    cardImageHeight = 180.dp,
    showDek = true,
    typeScaleFactor = 1.2f,
    contentPadding = 16.dp,
    gutter = 12.dp,
)

val LocalLayout = compositionLocalOf { ClassicNewspaper }

/** Resolve columns from width + layout: 2 columns hero-spans on medium/expanded. */
@Composable
fun rememberColumns(layout: PorchlightLayout): Int {
    val width = LocalConfiguration.current.screenWidthDp
    return if (width >= 600) 2 else layout.columns
}

@Composable
fun currentLayout(themePref: String, textScale: Double): PorchlightLayout {
    return when {
        textScale >= 1.3 -> LargeTextLayout
        themePref == "compact" -> CompactLayout
        themePref == "modern" -> ModernNewspaper
        else -> ClassicNewspaper
    }
}
