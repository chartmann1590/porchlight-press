package com.hartmann.crosspromo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hartmann.crosspromo.HartmannCrossPromo
import com.hartmann.crosspromo.model.PromoApp

/**
 * Compact full-width banner/row presentation designed for Settings, About, or list footers.
 */
@Composable
fun HartmannPromoRow(
    modifier: Modifier = Modifier,
    placement: String = "settings",
    title: String = "More from Hartmann Studios",
    actionButtonText: String = "View on Google Play"
) {
    // Silent failure / opt-out: if disabled or nothing to show, render nothing.
    if (!HartmannCrossPromo.enabled || HartmannCrossPromo.isUserOptedOut()) {
        return
    }

    val context = LocalContext.current
    // Remember the flow so recomposition does not restart collection.
    val appsFlow = remember(placement) {
        HartmannCrossPromo.getRecommendations(placement = placement, limit = 1)
    }
    val apps by appsFlow.collectAsState(initial = emptyList())

    val app = apps.firstOrNull() ?: return

    // Only count the impression while the row actually lays out on screen
    // (not clipped away inside a scroll container or hidden behind a sheet).
    var isVisible by remember { mutableStateOf(false) }

    val a11yDesc = "$title: ${app.name}. ${app.shortDescription ?: ""}"

    LaunchedEffect(app.packageName, isVisible) {
        if (isVisible) {
            HartmannCrossPromo.onAppImpression(app, placement)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                isVisible = bounds.width > 0f && bounds.height > 0f && bounds.top >= 0f
            }
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    HartmannCrossPromo.onAppClicked(context, app, placement)
                }
                .semantics { contentDescription = a11yDesc },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(app.iconUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!app.shortDescription.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = app.shortDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (app.rating != null && app.rating > 0.0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "★ ${"%.1f".format(app.rating)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        HartmannCrossPromo.onAppClicked(context, app, placement)
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = actionButtonText,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
