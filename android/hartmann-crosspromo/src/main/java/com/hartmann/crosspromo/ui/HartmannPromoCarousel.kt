package com.hartmann.crosspromo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hartmann.crosspromo.HartmannCrossPromo
import com.hartmann.crosspromo.model.PromoApp

/**
 * Material 3 Horizontal Carousel showcasing Hartmann Studios apps.
 * Automatically loads recommendations, tracks impressions and clicks,
 * and gracefully renders nothing if network/cache yields zero apps or the
 * user has opted out.
 */
@Composable
fun HartmannPromoCarousel(
    modifier: Modifier = Modifier,
    placement: String = "home",
    limit: Int = 3,
    title: String = "More from Hartmann Studios",
    subtitle: String? = "Discover other apps from the same creator",
    actionButtonText: String = "View App"
) {
    // Silent failure / opt-out: if disabled or nothing to show, render nothing.
    if (!HartmannCrossPromo.enabled || HartmannCrossPromo.isUserOptedOut()) {
        return
    }

    val context = LocalContext.current
    // Remember the flow so recomposition (e.g. scrolling parents) does not
    // restart collection and re-trigger stale-while-revalidate fetches.
    val appsFlow = remember(placement, limit) {
        HartmannCrossPromo.getRecommendations(placement = placement, limit = limit)
    }
    val apps by appsFlow.collectAsState(initial = emptyList())

    if (apps.isEmpty()) {
        return
    }

    // Track which indices are actually visible on screen so pre-composed
    // off-screen LazyRow items do not record impressions.
    val listState = rememberLazyListState()
    val visibleIndexes by remember {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo
            info.map { it.index }.toSet()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        // Section Title & Subtitle
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal Carousel
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = apps,
                key = { it.packageName }
            ) { app ->
                val index = apps.indexOf(app)
                HartmannPromoCard(
                    app = app,
                    visible = index in visibleIndexes,
                    actionButtonText = actionButtonText,
                    onClick = {
                        HartmannCrossPromo.onAppClicked(context, app, placement)
                    },
                    onImpression = {
                        HartmannCrossPromo.onAppImpression(app, placement)
                    }
                )
            }
        }
    }
}
