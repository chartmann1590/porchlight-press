package com.charleshartman.porchlightpress.ui.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DownloadedPapersScreen(onViewPdf: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val papers = remember { mutableStateListOf<File>().apply { addAll(EditionPdf.papers(context)) } }
    if (papers.isEmpty()) {
        Column(modifier.padding(24.dp)) { Text("No downloaded papers yet.", style = MaterialTheme.typography.bodyLarge) }
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        itemsIndexed(papers, key = { _, file -> file.absolutePath }) { _, file ->
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(file.name.removeSuffix(".pdf").replace('-', ' '), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onViewPdf(file.name) }) { Text("View") }
                    TextButton(onClick = { EditionPdf.openWith(context, file) }) { Text("Open with") }
                    TextButton(onClick = { EditionPdf.share(context, file) }) { Text("Share") }
                    TextButton(onClick = { if (file.delete()) papers.remove(file) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
fun PdfReaderScreen(fileName: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val file = remember(fileName) { File(File(context.filesDir, "papers"), File(fileName).name) }
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
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            if (pageCount > 0) {
                TextButton(onClick = { EditionPdf.share(context, file) }) { Text("Share") }
                TextButton(onClick = { EditionPdf.openWith(context, file) }) { Text("Open with") }
            }
        }
        if (loading) CircularProgressIndicator(Modifier.padding(24.dp))
        error?.let { Text(it, Modifier.padding(24.dp)) }
        LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            items(pageCount) { index -> PdfPage(file, index, pageCount) }
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
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Text("${index + 1} / $total", style = MaterialTheme.typography.labelSmall)
        image?.let { bitmap ->
            Image(bitmap.asImageBitmap(), "Page ${index + 1} of $total", Modifier.fillMaxWidth()
                .pointerInput(index) { detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 4f) } }
                .graphicsLayer { scaleX = scale; scaleY = scale })
        }
    }
}
