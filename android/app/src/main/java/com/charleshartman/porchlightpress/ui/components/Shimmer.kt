package com.charleshartman.porchlightpress.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.charleshartman.porchlightpress.ui.motion.rememberReduceMotion
import com.charleshartman.porchlightpress.ui.theme.LocalIsClassic
import com.charleshartman.porchlightpress.ui.theme.GoldLamp

/** Branded shimmer placeholder — warm gold flicker in Classic, cool blue in Modern. */
@Composable
fun PorchlightShimmer(
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
) {
    val reduce = rememberReduceMotion()
    val classic = LocalIsClassic.current
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = if (classic) GoldLamp.copy(alpha = 0.55f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val shimmerX = if (reduce) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "shimmer")
        val v by transition.animateFloat(
            initialValue = -200f,
            targetValue = 1200f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmer-x",
        )
        v
    }
    val brush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(shimmerX, 0f),
        end = Offset(shimmerX + 280f, 280f),
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(18.dp))
            .background(brush)
            .testTag("porchlight-shimmer"),
    )
}
