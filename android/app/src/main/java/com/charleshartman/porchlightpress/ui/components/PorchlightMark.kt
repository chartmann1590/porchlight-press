package com.charleshartman.porchlightpress.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.charleshartman.porchlightpress.ui.theme.GoldLamp
import com.charleshartman.porchlightpress.ui.theme.LocalIsClassic

/**
 * Porchlight / lamp mark — vector drawn with Compose Canvas.
 * Classic: warm gold lamp glow. Modern: cool primary glow.
 */
@Composable
fun PorchlightMark(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val classic = LocalIsClassic.current
    val primary = MaterialTheme.colorScheme.primary
    val glow = if (classic) GoldLamp else primary
    val ink = MaterialTheme.colorScheme.onBackground
    Canvas(modifier.size(size).testTag("porchlight-mark")) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        // Soft glow halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.55f), glow.copy(alpha = 0.12f), Color.Transparent),
                center = Offset(cx, h * 0.38f),
                radius = w * 0.48f,
            ),
            radius = w * 0.48f,
            center = Offset(cx, h * 0.38f),
        )
        // Lamp shade
        val shade = Path().apply {
            moveTo(w * 0.22f, h * 0.28f)
            lineTo(w * 0.78f, h * 0.28f)
            lineTo(w * 0.68f, h * 0.48f)
            lineTo(w * 0.32f, h * 0.48f)
            close()
        }
        drawPath(shade, color = glow)
        // Bulb
        drawCircle(color = Color.White.copy(alpha = 0.92f), radius = w * 0.08f, center = Offset(cx, h * 0.52f))
        // Stem
        drawLine(
            color = ink.copy(alpha = 0.85f),
            start = Offset(cx, h * 0.58f),
            end = Offset(cx, h * 0.78f),
            strokeWidth = w * 0.045f,
            cap = StrokeCap.Round,
        )
        // Base
        drawRoundRect(
            color = ink.copy(alpha = 0.9f),
            topLeft = Offset(w * 0.34f, h * 0.78f),
            size = Size(w * 0.32f, h * 0.08f),
            cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
        )
        // Tiny porch roof hint above shade
        val roof = Path().apply {
            moveTo(w * 0.18f, h * 0.28f)
            lineTo(cx, h * 0.12f)
            lineTo(w * 0.82f, h * 0.28f)
        }
        drawPath(roof, color = ink.copy(alpha = 0.75f), style = Stroke(width = w * 0.04f, cap = StrokeCap.Round))
    }
}
