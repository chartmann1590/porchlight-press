package com.charleshartman.porchlightpress.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.ui.components.PorchlightMark
import com.charleshartman.porchlightpress.ui.theme.LocalIsClassic
import com.charleshartman.porchlightpress.ui.theme.classicPaperBrush
import com.charleshartman.porchlightpress.ui.theme.modernSurfaceBrush

// ---------------------------------------------------------------------------
// Source information (Screen 15): per-source name, homepage, rights mode
// ---------------------------------------------------------------------------
@Composable
fun SourceInfoScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val sources by produceState(initialValue = emptyList<com.charleshartman.porchlightpress.data.local.SourceInfo>(), container) {
        value = container.db.sourceDao().all()
    }
    val classic = LocalIsClassic.current
    val bg = if (classic) Modifier.background(classicPaperBrush()) else Modifier.background(modernSurfaceBrush())
    Column(modifier.fillMaxSize().then(bg).verticalScroll(rememberScrollState()).padding(16.dp).testTag("source-info-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("source-info-back")) { Text("‹ Back") }
        Text("Sources", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }.testTag("source-info-title"))
        Text("Every story links the original reporting. Rights modes shown in plain language.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("source-info-intro"))
        if (sources.isEmpty()) {
            Text("Source list will appear after your first edition sync.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("source-info-empty"))
        }
        sources.forEach { src ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("source-item-${src.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(src.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("source-item-name"))
                Text(src.homepage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(plainRights(src.rightsMode), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("source-item-rights"))
            }
        }
    }
}

private fun plainRights(mode: String): String = when (mode) {
    "PUBLIC_DOMAIN" -> "Public domain — full text may be quoted, images may be reused."
    "OPEN_LICENSE" -> "Open license — full text and image reuse with attribution."
    "RSS_EXCERPT_ALLOWED" -> "RSS excerpt — up to 300 chars may be shown; no image reuse."
    "METADATA_ONLY" -> "Headlines only — no excerpt or image reuse; we link the original."
    "LINK_ONLY" -> "Link only — joins clusters but never the basis of a brief."
    "BLOCKED" -> "Blocked — not ingested."
    else -> "Original reporting linked below; reuse varies by publisher."
}

// ---------------------------------------------------------------------------
// About / AI transparency (Screen 16): what AI is, model, validation, disclaimer,
// attributions, OSS licenses.
// ---------------------------------------------------------------------------
@Composable
fun AboutScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val prefs by container.prefs.prefs.collectAsState(initial = null)
    val classic = LocalIsClassic.current
    val bg = if (classic) Modifier.background(classicPaperBrush()) else Modifier.background(modernSurfaceBrush())
    Column(modifier.fillMaxSize().then(bg).verticalScroll(rememberScrollState()).padding(16.dp).testTag("about-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("about-back")) { Text("‹ Back") }
        PorchlightMark(size = 40.dp)
        Text("About Porchlight Press", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }.testTag("about-title"))
        Text(
            "Porchlight Press is a free, ad-supported personal newspaper. Stories are AI-written briefs that always link the original reporting and say so on every story. The AI is never a single point of failure — when no brief is available, the app shows only the linked original reporting.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("about-intro"),
        )
        SectionH("AI newsroom")
        Text(
            "Briefs are generated on GitHub Actions runners with Qwen3-4B Q4_K_M (Apache-2.0), served via llama.cpp with JSON-schema grammar and thinking disabled. A cloud fallback (Cloudflare Workers AI) is optional and off by default. When validation fails, the app shows only the linked original reporting instead — no fabricated summary.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("about-ai-model"),
        )
        SectionH("Validation rules")
        BulletList(
            listOf(
                "Schema-valid and required fields present",
                "sourceIds ⊆ cluster members; every source has a URL",
                "Every name/number/date in the output appears in the source text",
                "No sentence <60% covered by sources; disagreements (300 vs 500) must show both",
                "Headline ≤110 chars, dek ≤200, body 60–220 words",
                "No ≥12 consecutive words copied from non-PD sources; no fake quotes",
                "On failure: one retry, then only the linked original reporting. Published only if quality checks pass",
            ),
        )
        SectionH("Disclaimer")
        Text(
            "AI briefs may contain mistakes. The original reporting is always linked — open it for the full story. Not legal, medical, or emergency advice. Severe-weather alerts are shown verbatim from the National Weather Service; machine translation may be imperfect — the original English is one tap away.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("about-disclaimer"),
        )
        SectionH("Attributions")
        Text("GeoNames cities15000 & postal data — CC BY 4.0 (geonames.org). US Census Gazetteer & ZCTA — public domain. NWS & MET Norway — public/ CC BY 4.0, credited on the Weather screen. Wikimedia Commons images — per-image CC0/PD/CC BY/CC BY-SA, shown with attribution. ML Kit Translation — Google on-device.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("about-attributions"))
        SectionH("Open source")
        Text("Apache-2.0 / MIT engine licenses as applicable. Source: https://github.com/chartmann1590/porchlight-press", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("about-oss"))
        SectionH("Privacy & ads")
        Text(
            "Location stays on your device (rounded to two decimals; weather uses a 0.1° bucket). Analytics/Crashlytics are opt-in, default off. Ads via AdMob require UMP consent (EEA/UK/US states) before any request; with no consent, non-personalized ads only. Debug builds use Google test ad IDs (app ca-app-pub-3940256099942544~3347511713).",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("about-privacy"),
        )
    }
}

@Composable
private fun SectionH(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }.testTag("about-h-$text"))
}

@Composable
private fun BulletList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("about-bullets")) {
        items.forEach { t ->
            Text("• $t", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
