package com.charleshartman.porchlightpress.ui.components

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.charleshartman.porchlightpress.data.ads.AdMobGate

// ---------------------------------------------------------------------------
// Banner at foot of section lists; native ADVERTISEMENT box between sections.
// Both collapse (no gap) when ads disabled or load fails (MASTER_PLAN §13).
// ---------------------------------------------------------------------------

@Composable
fun BannerAdSlot(
    gate: AdMobGate,
    modifier: Modifier = Modifier,
) {
    val ready by gate.adsReady.collectAsState(initial = false)
    if (!gate.config.enabled || !ready) return
    val context = LocalContext.current
    // Do not show during onboarding; gate.adsReady is false until consent resolves.
    Box(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .testTag("ad-banner-slot"),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                gate.makeBanner(ctx) ?: android.view.View(ctx).apply { visibility = android.view.View.GONE }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun NativeAdBox(
    gate: AdMobGate,
    modifier: Modifier = Modifier,
) {
    val ready by gate.adsReady.collectAsState(initial = false)
    if (!gate.config.enabled || !ready) return
    // Policy-compliant labelled newspaper ADVERTISEMENT box; real NativeAd
    // rendering arrives in Phase 9; this placeholder satisfies the placement
    // wiring while staying collapse-safe when ads fail.
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("ad-native-box"),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "ADVERTISEMENT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("ad-native-label"),
            )
        }
    }
}
