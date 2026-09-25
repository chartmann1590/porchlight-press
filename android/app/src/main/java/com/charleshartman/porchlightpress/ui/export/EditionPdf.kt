package com.charleshartman.porchlightpress.ui.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.ui.frontpage.FrontPageUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exports exactly the cached edition and translated text currently displayed. */
object EditionPdf {
    suspend fun export(context: Context, container: AppContainer, edition: FrontPageUiState, language: String): File =
        withContext(Dispatchers.IO) {
            require(edition.sections.isNotEmpty()) { "No edition is available" }
            val place = edition.place?.label ?: "Local"
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val slug = place.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val file = File(File(context.filesDir, "papers").apply { mkdirs() },
                "porchlight-press-$slug-$date-${language.lowercase(Locale.US)}.pdf")
            val pdf = PdfDocument()
            try {
                val page = Pages(pdf)
                page.masthead(place, date)
                suspend fun label(key: String, english: String): String = runCatching {
                    container.translationRepository.uiTextOnce(key, english, language)
                }.getOrDefault(english)
                val aiLabel = label("pdf.aiDisclosure", "AI NEWSROOM  •  AI-written brief; sources below")
                val sourceCardLabel = label("pdf.sourceCard", "SOURCE CARD  •  Original reporting by")
                val sourceLabel = label("pdf.sources", "REPORTING SOURCES")
                val translationNote = label("pdf.translationNote", "Translated on device with Google ML Kit. Original reporting linked above.")
                edition.sections.forEach { section ->
                    page.write(label("pdf.section.${section.id}", section.title).uppercase(Locale.forLanguageTag(language)), 15f, true, 10f)
                    section.stories.forEach { item ->
                        val story = item.story
                        val translated = item.translation.takeIf { language != "en" }
                        page.write(translated?.headline ?: story.headline, 16f, true, 7f)
                        (translated?.dek ?: story.dek)?.takeIf(String::isNotBlank)?.let { page.write(it, 11f, false, 7f) }
                        page.write(if (story.aiGenerated) aiLabel
                            else "$sourceCardLabel ${item.sourceLabel ?: "publisher"}", 8f, true, 6f)
                        (translated?.body ?: story.body)?.takeIf(String::isNotBlank)?.let { page.write(it, 10f, false, 8f) }
                        val sources = container.db.storyDao().sourcesFor(story.id)
                        if (sources.isNotEmpty()) page.write(sourceLabel, 8f, true, 4f)
                        sources.forEach { source ->
                            page.write("${source.publisher}: ${source.headline}", 8f, false, 2f)
                            page.write(source.url, 8f, false, 4f)
                        }
                        if (translated != null) page.write(translationNote, 8f, false, 5f)
                        page.space(12f)
                    }
                }
                page.finish()
                file.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            copyToDownloads(context, file)
            file
        }

    fun papers(context: Context): List<File> = File(context.filesDir, "papers")
        .listFiles { f -> f.isFile && f.extension.equals("pdf", true) }
        ?.sortedByDescending(File::lastModified).orEmpty()

    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun share(context: Context, file: File) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share newspaper"))
    }

    fun openWith(context: Context, file: File) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri(context, file), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Open newspaper with"))
    }

    private fun copyToDownloads(context: Context, file: File) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Porchlight Press")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    try {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("Downloads destination is unavailable")
                        output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
                        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    } catch (e: Exception) { context.contentResolver.delete(uri, null, null); throw e }
                }
            } else {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), file.name)
                    .outputStream().use { output -> file.inputStream().use { it.copyTo(output) } }
            }
        } catch (e: Exception) {
            // Keep the app-private copy available on devices that restrict public Downloads.
            android.util.Log.w("Porchlight", "PDF could not be copied to Downloads", e)
        }
    }

    private class Pages(private val pdf: PdfDocument) {
        private val margin = 38f
        private val width = 612
        private val height = 792
        private val gap = 20f
        private val columnWidth = ((width - 2 * margin - gap) / 2).toInt()
        private var number = 0
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var column = 0
        private var y = margin
        private var firstPageContentTop = margin

        init { nextPage() }

        private fun nextPage() {
            page?.let(pdf::finishPage)
            number++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, number).create())
            canvas = page!!.canvas.apply { drawColor(Color.WHITE) }
            column = 0; y = margin
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 9f }
            canvas!!.drawText("Porchlight Press  •  $number", margin, height - 20f, pen)
        }

        private fun advance(required: Float = 0f) {
            if (y + required <= height - 42f) return
            if (column == 0) { column = 1; y = if (number == 1) firstPageContentTop else margin } else nextPage()
        }

        fun masthead(place: String, date: String) {
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.create("serif", Typeface.BOLD) }
            pen.textSize = 26f
            canvas!!.drawText("PORCHLIGHT PRESS", (width - pen.measureText("PORCHLIGHT PRESS")) / 2, y + 26f, pen)
            y += 42f; pen.textSize = 12f
            val subtitle = "$place  •  $date"
            canvas!!.drawText(subtitle, (width - pen.measureText(subtitle)) / 2, y + 12f, pen)
            y += 28f
            canvas!!.drawLine(margin, y, width - margin, y, pen)
            y += 15f
            firstPageContentTop = y
        }

        fun write(text: String, size: Float, bold: Boolean, after: Float) {
            val pen = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = size
                typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
            text.split('\n').forEach { part ->
                var left = part.ifEmpty { " " }
                while (left.isNotEmpty()) {
                    val layout = StaticLayout.Builder.obtain(left, 0, left.length, pen, columnWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
                    val available = (height - 43f - y).toInt()
                    val count = (0 until layout.lineCount).takeWhile { layout.getLineBottom(it) <= available }.count()
                    if (count == 0) { advance(height.toFloat()); continue }
                    val end = layout.getLineEnd(count - 1)
                    canvas!!.save()
                    canvas!!.translate(margin + column * (columnWidth + gap), y)
                    canvas!!.clipRect(0f, 0f, columnWidth.toFloat(), layout.getLineBottom(count - 1).toFloat())
                    layout.draw(canvas!!)
                    canvas!!.restore()
                    y += layout.getLineBottom(count - 1) + 1f
                    left = left.substring(end)
                    if (left.isNotEmpty()) advance(height.toFloat())
                }
                y += after
                advance()
            }
        }

        fun space(amount: Float) { y += amount; advance() }
        fun finish() { page?.let(pdf::finishPage); page = null; canvas = null }
    }
}
