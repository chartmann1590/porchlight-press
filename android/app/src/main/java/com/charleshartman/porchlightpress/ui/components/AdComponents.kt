package com.charleshartman.porchlightpress.ui.components

import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.charleshartman.porchlightpress.data.ads.AdMobGate
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView

// ---------------------------------------------------------------------------
// Banner at foot of section lists (also anchored at the front-page bottom);
// native Sponsored card between front-page sections. Both collapse (no gap)
// when ads disabled, consent unresolved, or load fails (MASTER_PLAN §13).
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

/**
 * Real native ad rendered like a story card and clearly labeled
 * "Sponsored", with the AdChoices attribution the SDK requires. Debug
 * builds load Google's native test ad; release uses the unit ID from CI
 * secrets. Never tapped by tests or the on-device pass.
 */
@Composable
fun NativeAdBox(
    gate: AdMobGate,
    modifier: Modifier = Modifier,
) {
    val ready by gate.adsReady.collectAsState(initial = false)
    if (!gate.config.enabled || !ready) return
    val context = LocalContext.current
    var nativeAd by remember { mutableStateOf<NativeAd?>(null) }
    // Load once per composition lifetime; the slot stays collapsed until an
    // ad arrives (and collapses again on failure).
    LaunchedEffect(gate) {
        gate.loadNative(onLoaded = { nativeAd = it })
    }
    DisposableEffect(nativeAd) {
        // Capture at entry: reading state in onDispose would see the CURRENT
        // value and destroy the just-loaded ad when the key changes.
        val adToDestroy = nativeAd
        onDispose { adToDestroy?.destroy() }
    }
    val ad = nativeAd ?: return
    Card(
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("ad-native-box"),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                "Sponsored",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp).testTag("ad-native-label"),
            )
            AndroidView(
                factory = { ctx -> buildNativeAdView(ctx) },
                update = { view -> view.setNativeAd(ad) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Programmatic NativeAdView skeleton: child views are registered by role
 * before setNativeAd, per the AdMob native-ads contract. */
private fun buildNativeAdView(ctx: android.content.Context): NativeAdView {
    val dark = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    val fg = if (dark) 0xFFFFFFFF.toInt() else 0xFF1F1F1F.toInt()
    val sub = if (dark) 0xFFCACACA.toInt() else 0xFF5A5A5A.toInt()
    fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics,
    ).toInt()
    fun sp(v: Int): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v.toFloat(), ctx.resources.displayMetrics,
    )

    val root = NativeAdView(ctx).apply {
        layoutParams = ViewGroupLayout()
    }
    val col = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroupLayout()
    }
    val headline = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(16))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(fg)
    }
    val body = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14))
        setTextColor(sub)
        maxLines = 3
    }
    val media = MediaView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(180),
        ).apply { topMargin = dp(8); bottomMargin = dp(8) }
    }
    val ctaRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = ViewGroupLayout()
    }
    val advertiser = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12))
        setTextColor(sub)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val cta = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14))
    }
    val choices = com.google.android.gms.ads.nativead.AdChoicesView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginStart = dp(8) }
    }
    ctaRow.addView(advertiser)
    ctaRow.addView(cta)
    ctaRow.addView(choices)
    col.addView(headline)
    col.addView(body)
    col.addView(media)
    col.addView(ctaRow)
    root.addView(col)
    root.headlineView = headline
    root.bodyView = body
    root.mediaView = media
    root.advertiserView = advertiser
    root.callToActionView = cta
    root.adChoicesView = choices
    return root
}

private fun ViewGroupLayout(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
