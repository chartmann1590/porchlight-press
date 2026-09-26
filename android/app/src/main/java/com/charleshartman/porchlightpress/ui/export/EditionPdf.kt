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
import com.charleshartman.porchlightpress.data.local.StorySource
import com.charleshartman.porchlightpress.ui.frontpage.FrontPageUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exports exactly the cached edition and translated text currently displayed. */
object EditionPdf {
    suspend fun exportCurrent(context: Context, container: AppContainer): File = withContext(Dispatchers.IO) {
        val prefs = container.prefs.snapshot()
        val locationId = prefs.activeLocationId ?: error("No active location selected")
        val lang = prefs.appLanguage
        val locRow = container.db.savedLocationDao().byId(locationId)
        val place = locRow?.let {
            com.charleshartman.porchlightpress.domain.Place(
                it.id, it.label, it.country, it.admin1, it.admin2, it.city, it.metro, it.lat, it.lon, it.tz
            )
        } ?: error("Location not found")
        // Attempt refresh first so today's edition is fetched if possible; fallback to cache if offline
        runCatching {
            container.editionRepository.sync(place, "latest", container.feedApi)
        }
        var content = container.editionRepository.cachedContent(locationId, "latest")
        if (content == null) {
            container.editionRepository.sync(place, "latest", container.feedApi)
            content = container.editionRepository.cachedContent(locationId, "latest")
                ?: error("No edition available for this location yet.")
        }
        val edition = content.edition
        val allStoriesRaw = content.sections.flatMap { it.second }.distinctBy { it.id }
        val uiStories = allStoriesRaw.map { story ->
            val sources = container.db.storyDao().sourcesFor(story.id)
            val label = sources.firstOrNull()?.publisher
            val translation = if (lang != "en") {
                val cached = container.db.translationDao().storyTranslation(story.id, story.version, lang)
                cached ?: runCatching {
                    container.translationRepository.translateStoryOnce(
                        story.id, story.version, story.headline, story.dek, story.body, lang,
                    )
                }.getOrNull()
            } else null
            com.charleshartman.porchlightpress.ui.frontpage.FrontStoryUi(story, translation, false, label)
        }
        val interests = prefs.interests
        val sections = content.sections.map { (sec, stories) ->
            val mapped = stories.map { story -> uiStories.first { it.story.id == story.id } }
                .sortedByDescending { it.story.category in interests }
            com.charleshartman.porchlightpress.ui.frontpage.SectionUi(sec.sectionId, sec.title, mapped)
        }
        val state = FrontPageUiState(
            isLoading = false,
            place = place,
            editionKind = edition.kind,
            generatedAt = edition.generatedAt,
            sections = sections,
            allStories = uiStories,
        )
        export(context, container, state, lang)
    }

    suspend fun export(context: Context, container: AppContainer, edition: FrontPageUiState, language: String): File =
        withContext(Dispatchers.IO) {
            require(edition.sections.isNotEmpty()) { "No edition is available" }
            val place = edition.place?.label ?: "Local"
            val date = edition.generatedAt?.take(10)?.ifBlank { null }
                ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val slug = place.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val file = File(File(context.filesDir, "papers").apply { mkdirs() },
                "porchlight-press-$slug-$date-${language.lowercase(Locale.US)}.pdf")
            val pdf = PdfDocument()
            try {
                val page = Pages(pdf)
                page.setHeaders(place, date)
                page.masthead(place, date)
                suspend fun label(key: String, english: String): String = runCatching {
                    container.translationRepository.uiTextOnce(key, english, language)
                }.getOrDefault(english)
                val aiLabel = label("pdf.aiDisclosure", "AI NEWSROOM  •  AI-written brief; sources below")
                val sourceCardLabel = label("pdf.sourceCard", "SOURCE CARD  •  Original reporting by")
                val sourceLabel = label("pdf.sources", "REPORTING SOURCES")
                val scanLabel = label("pdf.scanQr", "Scan for original reporting")
                val translationNote = label("pdf.translationNote", "Translated on device with Google ML Kit. Original reporting linked above.")
                edition.sections.forEach { section ->
                    page.sectionHeader(label("pdf.section.${section.id}", section.title).uppercase(Locale.forLanguageTag(language)))
                    section.stories.forEach { item ->
                        val story = item.story
                        val translated = item.translation.takeIf { language != "en" }
                        page.headline(translated?.headline ?: story.headline)
                        (translated?.dek ?: story.dek)?.takeIf(String::isNotBlank)?.let { page.write(it, 10.5f, false, 6f, isItalic = true) }
                        page.write(if (story.aiGenerated) aiLabel
                            else "$sourceCardLabel ${item.sourceLabel ?: "publisher"}", 7.5f, true, 5f)
                        (translated?.body ?: story.body)?.takeIf(String::isNotBlank)?.let { page.write(it, 9.5f, false, 7f) }
                        val sources = container.db.storyDao().sourcesFor(story.id)
                        if (sources.isNotEmpty()) {
                            page.storySourcesWithQr(
                                sources = sources,
                                sourceLabel = sourceLabel,
                                scanPrompt = scanLabel,
                                qrUrl = sources.first().url
                            )
                        }
                        if (translated != null) page.write(translationNote, 7.5f, false, 4f)
                        page.storyDivider()
                    }
                }
                val disclaimerHeader = label("pdf.disclaimerHeader", "AI NEWSROOM & TRANSPARENCY NOTICE")
                val disclaimerText = label("pdf.disclaimerText", "This newspaper edition is generated by Porchlight Press AI based on verified local news reports and public sources. Articles are synthesized into concise briefs and checked against factual reporting. Scan the QR code next to any article to read the full original coverage from the publisher. Not affiliated with referenced news outlets.")
                val appDownloadText = label("pdf.appDownloadText", "Download the official Porchlight Press app on Google Play for live updates, audio editions, and breaking local alerts.")
                page.editorialDisclaimer(
                    playStoreUrl = QrCodeDrawer.APP_PLAY_STORE_URL,
                    disclaimerHeader = disclaimerHeader,
                    disclaimerText = disclaimerText,
                    appDownloadText = appDownloadText
                )
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
        private var placeHeader = ""
        private var dateHeader = ""

        init { nextPage() }

        fun setHeaders(place: String, date: String) {
            placeHeader = place
            dateHeader = date
        }

        private fun nextPage() {
            drawColumnDivider()
            page?.let(pdf::finishPage)
            number++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, number).create())
            canvas = page!!.canvas.apply { drawColor(Color.WHITE) }
            column = 0; y = margin
            
            // Running top header on page 2+
            if (number > 1) {
                val headerPen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.DKGRAY
                    textSize = 8f
                    typeface = Typeface.create("serif", Typeface.ITALIC)
                }
                canvas!!.drawText("PORCHLIGHT PRESS", margin, y + 8f, headerPen)
                val rightText = if (placeHeader.isNotBlank()) "$placeHeader  •  $dateHeader  •  Page $number" else "Page $number"
                headerPen.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                canvas!!.drawText(rightText, width - margin - headerPen.measureText(rightText), y + 8f, headerPen)
                val linePen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; strokeWidth = 0.5f }
                canvas!!.drawLine(margin, y + 12f, width - margin, y + 12f, linePen)
                y += 20f
            }
            
            // Running bottom footer with AI notice
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 7f }
            val footerText = "Porchlight Press  •  The Daily Broadsheet  •  AI-generated briefs based on real news reports  •  Page $number"
            canvas!!.drawText(footerText, margin, height - 20f, pen)
        }

        private fun drawColumnDivider() {
            val top = if (number == 1) firstPageContentTop else margin + 14f
            val bottom = height - 34f
            val dividerX = margin + columnWidth + gap / 2f
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.LTGRAY
                strokeWidth = 0.5f
            }
            canvas?.drawLine(dividerX, top, dividerX, bottom, pen)
        }

        private fun advance(required: Float = 0f) {
            if (y + required <= height - 42f) return
            if (column == 0) {
                column = 1
                y = if (number == 1) firstPageContentTop else margin + (if (number > 1) 20f else 0f)
            } else {
                nextPage()
            }
        }

        fun masthead(place: String, date: String) {
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

            val earHeight = 32f
            val earPen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 6.5f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            val earBorderPen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.LTGRAY
                strokeWidth = 0.5f
                style = Paint.Style.STROKE
            }

            // Left ear box
            canvas!!.drawRect(margin, y, margin + 85f, y + earHeight, earBorderPen)
            canvas!!.drawText("PRINT BROADSHEET", margin + 6f, y + 10f, earPen)
            earPen.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            earPen.textSize = 5.5f
            canvas!!.drawText("THE DAILY EDITION", margin + 6f, y + 18f, earPen)
            canvas!!.drawText("PRICE: FREE", margin + 6f, y + 26f, earPen)

            // Right ear box: Download the App from Google Play
            val rightEarWidth = 98f
            val rightEarLeft = width - margin - rightEarWidth
            canvas!!.drawRect(rightEarLeft, y, width - margin, y + earHeight, earBorderPen)
            earPen.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            earPen.textSize = 6.5f
            canvas!!.drawText("GET THE APP", rightEarLeft + 6f, y + 10f, earPen)
            earPen.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            earPen.textSize = 5.5f
            canvas!!.drawText("Google Play", rightEarLeft + 6f, y + 18f, earPen)
            canvas!!.drawText("Daily Updates", rightEarLeft + 6f, y + 26f, earPen)
            val earQrSize = 26f
            QrCodeDrawer.draw(
                canvas = canvas!!,
                text = QrCodeDrawer.APP_PLAY_STORE_URL,
                x = width - margin - earQrSize - 3f,
                y = y + 3f,
                size = earQrSize,
                quietZone = 0.5f,
                color = Color.BLACK,
                backgroundColor = Color.WHITE,
            )

            // Main Masthead Title: PORCHLIGHT PRESS
            pen.typeface = Typeface.create("serif", Typeface.BOLD)
            pen.textSize = 28f
            val title = "PORCHLIGHT PRESS"
            val titleX = (width - pen.measureText(title)) / 2f
            canvas!!.drawText(title, titleX, y + 24f, pen)

            y += 38f

            // Sub-title motto
            pen.textSize = 7.5f
            pen.typeface = Typeface.create("serif", Typeface.ITALIC)
            val motto = "• The Voice of the Community  —  Personal Local Broadsheet •"
            canvas!!.drawText(motto, (width - pen.measureText(motto)) / 2f, y + 6f, pen)

            y += 12f

            // Traditional Newspaper Double Rule for Dateline Bar
            val thickPen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 1.5f }
            canvas!!.drawLine(margin, y, width - margin, y, thickPen)
            y += 12f

            // Dateline bar content
            val datelinePen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 8.5f
                typeface = Typeface.create("serif", Typeface.BOLD)
            }
            val placeUpper = place.uppercase(Locale.US)
            canvas!!.drawText(placeUpper, margin, y, datelinePen)

            datelinePen.typeface = Typeface.create("serif", Typeface.NORMAL)
            val dateX = (width - datelinePen.measureText(date)) / 2f
            canvas!!.drawText(date, dateX, y, datelinePen)

            val editionVol = "VOL. I  •  BROADSHEET"
            val volX = width - margin - datelinePen.measureText(editionVol)
            canvas!!.drawText(editionVol, volX, y, datelinePen)

            y += 5f
            val thinPen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 0.5f }
            canvas!!.drawLine(margin, y, width - margin, y, thinPen)

            y += 16f
            firstPageContentTop = y
        }

        fun headline(text: String) {
            write(text, size = 15f, bold = true, after = 5f, isSerif = true)
        }

        fun sectionHeader(title: String) {
            space(6f)
            val linePen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 0.75f }
            val colX = margin + column * (columnWidth + gap)
            canvas?.drawLine(colX, y, colX + columnWidth, y, linePen)
            y += 4f
            write(title, size = 11.5f, bold = true, after = 3f, isSerif = true)
            canvas?.drawLine(colX, y, colX + columnWidth, y, linePen)
            y += 6f
            advance()
        }

        fun storyDivider() {
            val colX = margin + column * (columnWidth + gap)
            val centerX = colX + columnWidth / 2f
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; strokeWidth = 0.5f }
            canvas?.drawLine(centerX - 30f, y + 3f, centerX + 30f, y + 3f, pen)
            space(8f)
        }

        fun write(text: String, size: Float, bold: Boolean, after: Float, isSerif: Boolean = false, isItalic: Boolean = false) {
            val typefaceStyle = when {
                bold && isItalic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                isItalic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            val pen = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = size
                typeface = Typeface.create(if (isSerif) "serif" else "sans-serif", typefaceStyle)
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

        fun storySourcesWithQr(
            sources: List<StorySource>,
            sourceLabel: String,
            scanPrompt: String = "Scan for original reporting",
            qrUrl: String? = sources.firstOrNull()?.url,
        ) {
            if (sources.isEmpty() && qrUrl.isNullOrBlank()) return

            val qrSize = 36f
            val padding = 5f
            val textWidth = (columnWidth - qrSize - padding * 3).toInt()

            val labelPen = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 7f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            val sourcePen = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 6.5f
                typeface = Typeface.create("serif", Typeface.NORMAL)
            }
            val promptPen = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GRAY
                textSize = 6f
                typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            }

            val labelLayout = StaticLayout.Builder.obtain(sourceLabel, 0, sourceLabel.length, labelPen, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()

            val sourceTexts = sources.take(2).map { "${it.publisher}: ${it.headline}" }
            val sourceLayouts = sourceTexts.map { text ->
                StaticLayout.Builder.obtain(text, 0, text.length, sourcePen, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            }

            val promptText = "$scanPrompt ➔"
            val promptLayout = StaticLayout.Builder.obtain(promptText, 0, promptText.length, promptPen, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()

            val textHeight = labelLayout.height + 2f + sourceLayouts.sumOf { it.height + 2 } + promptLayout.height
            val boxHeight = maxOf(textHeight, qrSize) + padding * 2

            advance(boxHeight + 4f)

            val currentX = margin + column * (columnWidth + gap)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF8F8F8.toInt()
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.LTGRAY
                strokeWidth = 0.5f
                style = Paint.Style.STROKE
            }

            canvas?.drawRect(currentX, y, currentX + columnWidth, y + boxHeight, bgPaint)
            canvas?.drawRect(currentX, y, currentX + columnWidth, y + boxHeight, borderPaint)

            var textY = y + padding
            canvas?.save()
            canvas?.translate(currentX + padding, textY)
            labelLayout.draw(canvas!!)
            canvas?.restore()
            textY += labelLayout.height + 2f

            sourceLayouts.forEach { layout ->
                canvas?.save()
                canvas?.translate(currentX + padding, textY)
                layout.draw(canvas!!)
                canvas?.restore()
                textY += layout.height + 2f
            }

            canvas?.save()
            canvas?.translate(currentX + padding, textY)
            promptLayout.draw(canvas!!)
            canvas?.restore()

            if (!qrUrl.isNullOrBlank()) {
                val qrX = currentX + columnWidth - qrSize - padding
                val qrY = y + (boxHeight - qrSize) / 2f
                QrCodeDrawer.draw(
                    canvas = canvas!!,
                    text = qrUrl,
                    x = qrX,
                    y = qrY,
                    size = qrSize,
                    quietZone = 1f,
                    color = Color.BLACK,
                    backgroundColor = Color.WHITE,
                )
            }

            y += boxHeight + 4f
            advance()
        }

        fun editorialDisclaimer(
            playStoreUrl: String = QrCodeDrawer.APP_PLAY_STORE_URL,
            disclaimerHeader: String = "AI NEWSROOM & TRANSPARENCY NOTICE",
            disclaimerText: String = "This publication is generated by Porchlight Press AI based on verified local news reports and public sources. Articles are synthesized into concise briefs and checked against factual reporting. Scan the QR code next to any article to read the full original coverage directly on the publisher's website. Porchlight Press is not affiliated with the publishers of the original reporting.",
            appDownloadText: String = "Download the official Porchlight Press app on Google Play for live updates, audio editions, and breaking local alerts.",
        ) {
            val qrSize = 48f
            val padding = 7f
            val textWidth = (columnWidth - qrSize - padding * 3).toInt()

            val headerPen = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 7.5f
                typeface = Typeface.create("serif", Typeface.BOLD)
            }
            val bodyPen = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 5.8f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val appPen = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 5.8f
                typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
            }
            val qrLabelPen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 5.5f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            val headerLayout = StaticLayout.Builder.obtain(disclaimerHeader, 0, disclaimerHeader.length, headerPen, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            val bodyLayout = StaticLayout.Builder.obtain(disclaimerText, 0, disclaimerText.length, bodyPen, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            val appLayout = StaticLayout.Builder.obtain(appDownloadText, 0, appDownloadText.length, appPen, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()

            val totalTextHeight = headerLayout.height + 3f + bodyLayout.height + 3f + appLayout.height
            val boxHeight = maxOf(totalTextHeight, qrSize + 14f) + padding * 2

            advance(boxHeight + 8f)

            val currentX = margin + column * (columnWidth + gap)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF7F7F7.toInt()
                style = Paint.Style.FILL
            }
            val thickPen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                strokeWidth = 1f
            }
            val thinPen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.LTGRAY
                strokeWidth = 0.5f
                style = Paint.Style.STROKE
            }

            canvas?.drawRect(currentX, y, currentX + columnWidth, y + boxHeight, bgPaint)
            canvas?.drawLine(currentX, y, currentX + columnWidth, y, thickPen)
            canvas?.drawLine(currentX, y + 2f, currentX + columnWidth, y + 2f, thinPen)

            var textY = y + padding
            canvas?.save()
            canvas?.translate(currentX + padding, textY)
            headerLayout.draw(canvas!!)
            canvas?.restore()
            textY += headerLayout.height + 3f

            canvas?.save()
            canvas?.translate(currentX + padding, textY)
            bodyLayout.draw(canvas!!)
            canvas?.restore()
            textY += bodyLayout.height + 3f

            canvas?.save()
            canvas?.translate(currentX + padding, textY)
            appLayout.draw(canvas!!)
            canvas?.restore()

            val qrX = currentX + columnWidth - qrSize - padding
            val qrY = y + padding + 2f
            QrCodeDrawer.draw(
                canvas = canvas!!,
                text = playStoreUrl,
                x = qrX,
                y = qrY,
                size = qrSize,
                quietZone = 1f,
                color = Color.BLACK,
                backgroundColor = Color.WHITE,
            )
            canvas?.drawText("GET THE APP", qrX + qrSize / 2f, qrY + qrSize + 7f, qrLabelPen)
            qrLabelPen.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            qrLabelPen.textSize = 5f
            canvas?.drawText("Google Play", qrX + qrSize / 2f, qrY + qrSize + 13f, qrLabelPen)

            canvas?.drawLine(currentX, y + boxHeight - 2f, currentX + columnWidth, y + boxHeight - 2f, thinPen)
            canvas?.drawLine(currentX, y + boxHeight, currentX + columnWidth, y + boxHeight, thickPen)

            y += boxHeight + 8f
            advance()
        }

        fun space(amount: Float) { y += amount; advance() }
        fun finish() {
            drawColumnDivider()
            page?.let(pdf::finishPage)
            page = null
            canvas = null
        }
    }
}
