package com.charleshartman.porchlightpress

import android.graphics.Canvas
import android.graphics.Paint
import com.charleshartman.porchlightpress.ui.export.QrCodeDrawer
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeDrawerTest {
    @Test
    fun playStoreUrlMatchesPorchlightPackage() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.charleshartman.porchlightpress",
            QrCodeDrawer.APP_PLAY_STORE_URL,
        )
    }

    @Test
    fun drawReturnsFalseOnBlankText() {
        val canvas = mockk<Canvas>(relaxed = true)
        val paint = mockk<Paint>(relaxed = true)
        assertFalse(QrCodeDrawer.draw(canvas, "", 0f, 0f, 50f, paint = paint))
        assertFalse(QrCodeDrawer.draw(canvas, "   ", 0f, 0f, 50f, paint = paint))
    }

    @Test
    fun drawReturnsFalseOnInvalidSize() {
        val canvas = mockk<Canvas>(relaxed = true)
        val paint = mockk<Paint>(relaxed = true)
        assertFalse(QrCodeDrawer.draw(canvas, "https://example.com", 0f, 0f, 0f, paint = paint))
        assertFalse(QrCodeDrawer.draw(canvas, "https://example.com", 0f, 0f, -10f, paint = paint))
    }

    @Test
    fun drawRendersModulesOnValidUrl() {
        val canvas = mockk<Canvas>(relaxed = true)
        val paint = mockk<Paint>(relaxed = true)
        val drawn = QrCodeDrawer.draw(
            canvas = canvas,
            text = "https://example.com/story/123",
            x = 10f,
            y = 20f,
            size = 40f,
            paint = paint,
        )
        assertTrue(drawn)
        // Verify that canvas.drawRect was invoked multiple times for QR modules
        verify(atLeast = 10) { canvas.drawRect(any(), any(), any(), any(), any()) }
    }
}
