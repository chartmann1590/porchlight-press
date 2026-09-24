package com.charleshartman.porchlightpress.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
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
import com.charleshartman.porchlightpress.ui.theme.PorchlightColors

/**
 * Porch-lamp mark from the Stitch masthead — warm amber glow over crimson metal.
 */
@Composable
fun PorchlightMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    glow: Color = PorchlightColors.Amber,
    metal: Color = PorchlightColors.Crimson,
) {
    Canvas(modifier.size(size).testTag("porchlight-mark")) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    glow.copy(alpha = 0.65f),
                    glow.copy(alpha = 0.18f),
                    Color.Transparent,
                ),
                center = Offset(cx, h * 0.40f),
                radius = w * 0.52f,
            ),
            radius = w * 0.52f,
            center = Offset(cx, h * 0.40f),
        )
        val roof = Path().apply {
            moveTo(w * 0.18f, h * 0.30f)
            lineTo(cx, h * 0.12f)
            lineTo(w * 0.82f, h * 0.30f)
        }
        drawPath(
            roof,
            color = metal,
            style = Stroke(width = w * 0.045f, cap = StrokeCap.Round),
        )
        val shade = Path().apply {
            moveTo(w * 0.22f, h * 0.30f)
            lineTo(w * 0.78f, h * 0.30f)
            lineTo(w * 0.70f, h * 0.50f)
            lineTo(w * 0.30f, h * 0.50f)
            close()
        }
        drawPath(shade, color = glow)
        drawCircle(
            color = Color.White.copy(alpha = 0.95f),
            radius = w * 0.07f,
            center = Offset(cx, h * 0.54f),
        )
        drawLine(
            color = metal,
            start = Offset(cx, h * 0.60f),
            end = Offset(cx, h * 0.78f),
            strokeWidth = w * 0.05f,
            cap = StrokeCap.Round,
        )
        drawRoundRect(
            color = metal,
            topLeft = Offset(w * 0.33f, h * 0.78f),
            size = Size(w * 0.34f, h * 0.09f),
            cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
        )
    }
}
