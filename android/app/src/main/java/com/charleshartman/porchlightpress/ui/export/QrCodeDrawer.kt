package com.charleshartman.porchlightpress.ui.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Utility for rendering vector QR codes onto a [Canvas], specifically optimized
 * for Android [android.graphics.pdf.PdfDocument] vector output.
 */
object QrCodeDrawer {
    const val APP_PLAY_STORE_URL =
        "https://play.google.com/store/apps/details?id=com.charleshartman.porchlightpress"

    /**
     * Draws a vector QR code onto [canvas] at ([x], [y]) with size [size] x [size].
     * Renders each module as a vector rectangle, ensuring crystal-sharp resolution
     * at any zoom level or physical print DPI.
     *
     * @return `true` if the QR code was successfully encoded and drawn; `false` otherwise.
     */
    fun draw(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        quietZone: Float = 0f,
        color: Int = Color.BLACK,
        backgroundColor: Int? = Color.WHITE,
        paint: Paint? = null,
    ): Boolean {
        if (text.isBlank() || size <= 0f) return false
        return runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 0,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            val modules = matrix.width
            if (modules <= 0) return false

            val contentSize = size - 2 * quietZone
            val cellSize = contentSize / modules.toFloat()

            val p = paint ?: Paint(Paint.ANTI_ALIAS_FLAG)

            if (backgroundColor != null) {
                p.color = backgroundColor
                p.style = Paint.Style.FILL
                canvas.drawRect(x, y, x + size, y + size, p)
            }

            p.color = color
            p.style = Paint.Style.FILL

            val startX = x + quietZone
            val startY = y + quietZone

            for (row in 0 until modules) {
                for (col in 0 until modules) {
                    if (matrix.get(col, row)) {
                        val left = startX + col * cellSize
                        val top = startY + row * cellSize
                        // Tiny 0.05f sub-pixel overlap prevents hairline renderer artifacts in PDF viewers
                        canvas.drawRect(left, top, left + cellSize + 0.05f, top + cellSize + 0.05f, p)
                    }
                }
            }
            true
        }.getOrDefault(false)
    }
}
