package com.charleshartman.porchlightpress.ui.motion

import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** True when the user has enabled system reduce-motion / animator duration scale ~0. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
            scale == 0f
        } catch (_: Exception) {
            false
        }
    }
}

object PorchlightMotion {
    val enterSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
    val pressSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )
    fun fadeMs(reduce: Boolean) = if (reduce) 0 else 320
    fun slideMs(reduce: Boolean) = if (reduce) 0 else 420
    fun chipTween(reduce: Boolean) = tween<Float>(durationMillis = if (reduce) 0 else 280)
    fun pageTween(reduce: Boolean) = tween<Float>(durationMillis = if (reduce) 0 else 380)
    fun ruleTween(reduce: Boolean) = tween<Float>(durationMillis = if (reduce) 0 else 560)
}
