package com.charleshartman.porchlightpress.ui.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.charleshartman.porchlightpress.ui.frontpage.FrontPageUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A text-only share card avoids republishing images whose license requires attribution. */
object FrontPageShare {
    suspend fun share(context: Context, edition: FrontPageUiState) = withContext(Dispatchers.Default) {
        require(edition.allStories.isNotEmpty()) { "No edition available" }
        val image = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.rgb(247, 244, 237))
        val ink = Color.rgb(29, 38, 48)
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; strokeWidth = 4f }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 84f; typeface = Typeface.create("serif", Typeface.BOLD) }
        canvas.drawText("PORCHLIGHT", 64f, 122f, title)
        canvas.drawText("PRESS", 64f, 215f, title)
        canvas.drawLine(64f, 250f, 1016f, 250f, rule)
        val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 35f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val date = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
        canvas.drawText(edition.place?.label ?: "Your local edition", 64f, 310f, detail)
        canvas.drawText(date, 64f, 360f, detail)
        var y = 430f
        edition.allStories.take(4).forEachIndexed { index, item ->
            val headline = item.translation?.headline ?: item.story.headline
            val pen = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink; textSize = if (index == 0) 55f else 42f
                typeface = Typeface.create("serif", Typeface.BOLD)
            }
            val layout = StaticLayout.Builder.obtain(headline, 0, headline.length, pen, 940)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(if (index == 0) 4 else 2).build()
            canvas.save(); canvas.translate(64f, y); layout.draw(canvas); canvas.restore()
            y += layout.height + if (index == 0) 54f else 42f
            if (y > 1200f) return@forEachIndexed
            canvas.drawLine(64f, y - 20f, 1016f, y - 20f, rule)
        }
        canvas.drawText("porchlight-press • local news with sources", 64f, 1300f, detail)
        val out = withContext(Dispatchers.IO) {
            val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, "front-page.png")
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file
        }
        image.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        withContext(Dispatchers.Main) {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share front page"))
        }
    }
}
