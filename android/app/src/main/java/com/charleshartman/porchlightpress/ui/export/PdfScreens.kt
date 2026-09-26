package com.charleshartman.porchlightpress.ui.export

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.ui.components.PorchlightMark
import com.charleshartman.porchlightpress.ui.theme.CrimsonDeep
import com.charleshartman.porchlightpress.ui.theme.GoldLamp
import com.charleshartman.porchlightpress.ui.theme.LocalIsClassic
import com.charleshartman.porchlightpress.ui.theme.PaperAged
import com.charleshartman.porchlightpress.ui.theme.PaperCream
import com.charleshartman.porchlightpress.ui.theme.PaperWarm
import com.charleshartman.porchlightpress.ui.theme.classicPaperBrush
import com.charleshartman.porchlightpress.ui.theme.modernSurfaceBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Metadata extracted from an edition PDF file name. */
data class PaperMetadata(
    val file: File,
    val place: String,
    val dateline: String,
    val dateRaw: String,
    val language: String,
    val sizeLabel: String,
    val isToday: Boolean,
)

fun parsePaperMetadata(file: File): PaperMetadata {
    val name = file.name.removeSuffix(".pdf")
    val parts = name.removePrefix("porchlight-press-")
    val dateMatch = Regex("""\b(\d{4}-\d{2}-\d{2})\b""").find(parts)
    val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    val dateStr = dateMatch?.value ?: ""
    val isToday = dateStr == todayStr

    val dateline = if (dateStr.isNotEmpty()) {
        runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)
            if (parsed != null) SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(parsed)
            else dateStr
        }.getOrDefault(dateStr)
    } else {
        SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date(file.lastModified()))
    }

    val placeSlug = if (dateMatch != null) {
        parts.substring(0, dateMatch.range.first).trim('-')
    } else {
        parts
    }
    val place = placeSlug.split('-').filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            if (word.length <= 2) word.uppercase(Locale.US)
            else word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }.ifEmpty { "Local Edition" }

    val langCode = if (dateMatch != null && dateMatch.range.last + 1 < parts.length) {
        parts.substring(dateMatch.range.last + 1).trim('-')
    } else {
        "en"
    }
    val langLabel = runCatching {
        Locale.forLanguageTag(langCode).getDisplayLanguage(Locale.US)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: langCode.uppercase(Locale.US)

    val sizeBytes = file.length()
    val sizeLabel = when {
        sizeBytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", sizeBytes / (1024f * 1024f))
        sizeBytes > 0 -> "${(sizeBytes + 1023) / 1024} KB"
        else -> "0 KB"
    }

    return PaperMetadata(
        file = file,
        place = place,
        dateline = dateline,
        dateRaw = dateStr,
        language = langLabel,
        sizeLabel = sizeLabel,
        isToday = isToday,
    )
}

@Composable
fun NewspaperThumbnail(
    file: File,
    modifier: Modifier = Modifier,
) {
    var thumbnail by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(file.absolutePath) {
        onDispose {
            thumbnail?.recycle()
            thumbnail = null
        }
    }

    LaunchedEffect(file.absolutePath) {
        thumbnail = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        if (renderer.pageCount > 0) {
                            renderer.openPage(0).use { page ->
                                val targetW = (page.width / 2).coerceAtLeast(160)
                                val targetH = (page.height / 2).coerceAtLeast(210)
                                val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bitmap
                            }
                        } else null
                    }
                }
            }.getOrNull()
        }
    }

    Box(
        modifier = modifier
            .shadow(elevation = 5.dp, shape = RoundedCornerShape(3.dp))
            .background(Color.White, shape = RoundedCornerShape(3.dp))
            .border(width = 1.dp, color = Color(0xFFD4C8B0), shape = RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = thumbnail
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Newspaper front page thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            NewspaperPaperPlaceholder()
        }
    }
}

@Composable
private fun NewspaperPaperPlaceholder(modifier: Modifier = Modifier) {
    val classic = LocalIsClassic.current
    Canvas(modifier = modifier.fillMaxSize().background(if (classic) PaperCream else PaperWarm)) {
        val w = size.width
        val h = size.height
        // Outer paper edge
        drawRect(color = Color(0xFFD0C4AE), style = Stroke(width = 1.dp.toPx()))
        // Masthead banner
        drawRect(
            color = Color(0xFF2A2622).copy(alpha = 0.85f),
            topLeft = Offset(w * 0.15f, h * 0.08f),
            size = Size(w * 0.7f, h * 0.045f),
        )
        // Rule under masthead
        drawLine(
            color = Color(0xFF9B1B1B).copy(alpha = 0.7f),
            start = Offset(w * 0.1f, h * 0.15f),
            end = Offset(w * 0.9f, h * 0.15f),
            strokeWidth = 1.5.dp.toPx(),
        )
        // Hero headline bar
        drawRoundRect(
            color = Color(0xFF333333).copy(alpha = 0.6f),
            topLeft = Offset(w * 0.1f, h * 0.20f),
            size = Size(w * 0.8f, h * 0.035f),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
        // Two columns of story lines
        val colW = w * 0.36f
        val gap = w * 0.08f
        val col1X = w * 0.1f
        val col2X = col1X + colW + gap

        val inkLineColor = Color(0xFF888888).copy(alpha = 0.4f)
        for (i in 0..6) {
            val yPos = h * (0.28f + i * 0.045f)
            drawLine(
                color = inkLineColor,
                start = Offset(col1X, yPos),
                end = Offset(col1X + colW * (if (i % 3 == 2) 0.65f else 0.95f), yPos),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
        // Column divider rule
        drawLine(
            color = Color(0xFFCCCCCC),
            start = Offset(w * 0.5f, h * 0.26f),
            end = Offset(w * 0.5f, h * 0.85f),
            strokeWidth = 0.75.dp.toPx(),
        )
        for (i in 0..6) {
            val yPos = h * (0.28f + i * 0.045f)
            drawLine(
                color = inkLineColor,
                start = Offset(col2X, yPos),
                end = Offset(col2X + colW * (if (i % 2 == 1) 0.7f else 0.95f), yPos),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
}

@Composable
fun DownloadedPapersScreen(
    onViewPdf: (String) -> Unit,
    modifier: Modifier = Modifier,
    container: AppContainer? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val classic = LocalIsClassic.current
    val papers = remember { mutableStateListOf<File>().apply { addAll(EditionPdf.papers(context)) } }

    var paperToDelete by remember { mutableStateOf<File?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

    val prefs by (container?.prefs?.prefs ?: kotlinx.coroutines.flow.flowOf(null)).collectAsState(initial = null)
    val activeLocId = prefs?.activeLocationId
    val locations by (container?.db?.savedLocationDao()?.observeAll() ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val activeLocation = locations.firstOrNull { it.id == activeLocId } ?: locations.firstOrNull()

    val todayStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val todayDownloaded = papers.any { it.name.contains(todayStr) }

    fun refreshPapers() {
        papers.clear()
        papers.addAll(EditionPdf.papers(context))
    }

    if (paperToDelete != null) {
        val target = paperToDelete!!
        val meta = remember(target.absolutePath) { parsePaperMetadata(target) }
        AlertDialog(
            onDismissRequest = { paperToDelete = null },
            title = { Text("Delete Edition?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "Remove the downloaded print edition for ${meta.place} (${meta.dateline}) from this device?",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (target.delete()) {
                            papers.remove(target)
                        }
                        paperToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { paperToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    val bg = if (classic) Modifier.background(classicPaperBrush()) else Modifier.background(modernSurfaceBrush())

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .then(bg)
            .testTag("downloaded-papers-screen"),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // Newsstand Masthead Header
        item(key = "newsstand-header") {
            NewsstandHeader(classic = classic)
        }

        // Today's Print Edition Download / Status Card
        if (container != null && activeLocation != null) {
            item(key = "today-edition-card") {
                TodayEditionCard(
                    locationLabel = activeLocation.label,
                    todayDownloaded = todayDownloaded,
                    isExporting = isExporting,
                    exportError = exportError,
                    onDownload = {
                        isExporting = true
                        exportError = null
                        scope.launch {
                            try {
                                val file = EditionPdf.exportCurrent(context, container)
                                refreshPapers()
                                onViewPdf(file.name)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                exportError = e.localizedMessage ?: "Failed to typeset today's edition. Try again."
                            } finally {
                                isExporting = false
                            }
                        }
                    },
                    onReadToday = {
                        val latest = papers.firstOrNull { it.name.contains(todayStr) } ?: papers.firstOrNull()
                        if (latest != null) onViewPdf(latest.name)
                    },
                    classic = classic,
                )
            }
        }

        // Empty state or List of papers
        if (papers.isEmpty() && !isExporting) {
            item(key = "empty-newsstand") {
                NewsstandEmptyState(
                    hasTodayAction = container != null && activeLocation != null,
                    onDownloadToday = {
                        if (container != null) {
                            isExporting = true
                            exportError = null
                            scope.launch {
                                try {
                                    val file = EditionPdf.exportCurrent(context, container)
                                    refreshPapers()
                                    onViewPdf(file.name)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    exportError = e.localizedMessage ?: "Failed to generate newspaper."
                                } finally {
                                    isExporting = false
                                }
                            }
                        }
                    },
                    classic = classic,
                )
            }
        } else {
            item(key = "archive-section-title") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "SAVED PRINT EDITIONS",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${papers.size} ${if (papers.size == 1) "Edition" else "Editions"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            itemsIndexed(papers, key = { _, file -> file.absolutePath }) { _, file ->
                val meta = remember(file.absolutePath) { parsePaperMetadata(file) }
                PaperEditionCard(
                    metadata = meta,
                    onView = { onViewPdf(file.name) },
                    onOpenWith = { EditionPdf.openWith(context, file) },
                    onShare = { EditionPdf.share(context, file) },
                    onDelete = { paperToDelete = file },
                    classic = classic,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun NewsstandHeader(classic: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            PorchlightMark(size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    "PORCHLIGHT",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.5.sp,
                    ),
                    color = if (classic) GoldLamp else MaterialTheme.colorScheme.primary,
                )
                Text(
                    "NEWSSTAND",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "PRINT EDITIONS  •  OFFLINE BROADSHEETS",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Daily editions typeset in authentic 2-column broadsheet format.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        // Double rule
        Box(
            Modifier
                .fillMaxWidth(0.92f)
                .height(3.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopCenter)
                    .background(MaterialTheme.colorScheme.outline),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        if (classic) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    ),
            )
        }
    }
}

@Composable
private fun TodayEditionCard(
    locationLabel: String,
    todayDownloaded: Boolean,
    isExporting: Boolean,
    exportError: String?,
    onDownload: () -> Unit,
    onReadToday: () -> Unit,
    classic: Boolean,
    modifier: Modifier = Modifier,
) {
    val todayFormatted = remember {
        SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date())
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (classic) PaperCream else MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (classic) GoldLamp.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            if (classic) CrimsonDeep else MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        "TODAY'S FRONT PAGE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        ),
                        color = Color.White,
                    )
                }

                if (todayDownloaded) {
                    Text(
                        "✓ Downloaded",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFF2E7D32),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "$locationLabel Edition",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                todayFormatted,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Fresh 2-column print edition with local stories, weather, and AI briefs. Ready to print, share, or read offline without internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isExporting) {
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Typesetting broadsheet edition…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            exportError?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (todayDownloaded) {
                    Button(
                        onClick = onReadToday,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Read Today's Paper")
                    }
                    OutlinedButton(
                        onClick = onDownload,
                        enabled = !isExporting,
                    ) {
                        Text("Re-download")
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        enabled = !isExporting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Download Today's Broadsheet (PDF)")
                    }
                }
            }
        }
    }
}

@Composable
private fun PaperEditionCard(
    metadata: PaperMetadata,
    onView: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    classic: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (classic) PaperCream else MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                // Miniature Folded Broadsheet Thumbnail
                Box(
                    modifier = Modifier
                        .clickable { onView() }
                        .padding(end = 12.dp),
                ) {
                    NewspaperThumbnail(
                        file = metadata.file,
                        modifier = Modifier
                            .width(84.dp)
                            .height(112.dp),
                    )
                }

                // Edition Details
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Badge row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val badgeBg = if (metadata.isToday) {
                            if (classic) CrimsonDeep else MaterialTheme.colorScheme.primary
                        } else {
                            if (classic) PaperAged else MaterialTheme.colorScheme.surfaceVariant
                        }
                        val badgeColor = if (metadata.isToday) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        Box(
                            modifier = Modifier
                                .background(badgeBg, shape = RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                if (metadata.isToday) "TODAY" else "PRINT EDITION",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                ),
                                color = badgeColor,
                            )
                        }
                        Text(
                            metadata.language,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        metadata.place,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Text(
                        metadata.dateline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(2.dp))

                    // Spec pills
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "Broadsheet",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                metadata.sizeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            // Subtle hairline rule
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            )
            Spacer(Modifier.height(8.dp))

            // Action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onView,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Read Paper")
                    }
                    OutlinedButton(
                        onClick = onOpenWith,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Open with")
                    }
                    OutlinedButton(
                        onClick = onShare,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Share")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete paper",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NewsstandEmptyState(
    hasTodayAction: Boolean,
    onDownloadToday: () -> Unit,
    classic: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        PorchlightMark(size = 54.dp)
        Text(
            "Your Newsstand is Empty",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Porchlight Press formats every daily edition into an authentic two-column newspaper broadsheet. Download an edition to read offline, archive, or print.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(8.dp))

        // Feature highlights card
        Card(
            modifier = Modifier.fillMaxWidth(0.92f),
            colors = CardDefaults.cardColors(
                containerColor = if (classic) PaperCream else MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("• Authentic 2-column broadsheet styling", style = MaterialTheme.typography.bodySmall)
                Text("• Zero internet needed once downloaded", style = MaterialTheme.typography.bodySmall)
                Text("• Standard US Letter broadsheet for home printing", style = MaterialTheme.typography.bodySmall)
                Text("• Ad-free print format with AI disclosures & sources", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (hasTodayAction) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDownloadToday,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Download Today's Broadsheet (PDF)")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(fileName: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val file = remember(fileName) { File(File(context.filesDir, "papers"), File(fileName).name) }
    val meta = remember(file.absolutePath) { parsePaperMetadata(file) }
    val classic = LocalIsClassic.current

    var pageCount by remember(fileName) { mutableStateOf(0) }
    var error by remember(fileName) { mutableStateOf<String?>(null) }
    var loading by remember(fileName) { mutableStateOf(true) }

    LaunchedEffect(fileName) {
        if (!file.exists()) { error = "Paper was not found"; loading = false; return@LaunchedEffect }
        try {
            pageCount = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { it.pageCount }
                }
            }
        } catch (e: Exception) { error = e.localizedMessage ?: "Could not open paper" }
        loading = false
    }

    val bg = if (classic) Modifier.background(classicPaperBrush()) else Modifier.background(modernSurfaceBrush())

    Column(modifier.fillMaxSize().then(bg)) {
        // Newspaper Masthead TopAppBar
        TopAppBar(
            title = {
                Column {
                    Text(
                        "PORCHLIGHT PRESS",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        ),
                    )
                    Text(
                        "${meta.place}  •  ${meta.dateline}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            actions = {
                if (pageCount > 0) {
                    TextButton(onClick = { EditionPdf.share(context, file) }) {
                        Text("Share")
                    }
                    TextButton(onClick = { EditionPdf.openWith(context, file) }) {
                        Text("Open with")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (classic) PaperWarm else MaterialTheme.colorScheme.surface,
            ),
        )

        // Double hairline rule
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopCenter)
                    .background(MaterialTheme.colorScheme.outline),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.BottomCenter)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
            )
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text("Opening broadsheet edition…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        error?.let {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(it, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
            }
        }

        if (pageCount > 0) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(pageCount) { index ->
                    PdfPage(file, index, pageCount)
                }
            }
        }
    }
}

@Composable
private fun PdfPage(file: File, index: Int, total: Int) {
    var image by remember(file, index) { mutableStateOf<Bitmap?>(null) }
    var scale by remember(file, index) { mutableFloatStateOf(1f) }
    DisposableEffect(file, index) { onDispose { image?.recycle(); image = null } }
    LaunchedEffect(file, index) {
        image = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(index).use { page ->
                        Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Page chip
        Box(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(50),
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                "PAGE ${index + 1} OF $total  •  PRINT BROADSHEET",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        // Physical paper broadsheet container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 6.dp, shape = RoundedCornerShape(3.dp))
                .border(width = 1.dp, color = Color(0xFFD4C8B0), shape = RoundedCornerShape(3.dp))
                .background(Color.White, shape = RoundedCornerShape(3.dp))
                .padding(2.dp),
        ) {
            image?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Page ${index + 1} of $total",
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(index) {
                            detectTransformGestures { _, _, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 4f)
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                )
            }
        }
    }
}
