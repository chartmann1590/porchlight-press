package com.charleshartman.porchlightpress

import android.content.Context
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.charleshartman.porchlightpress.data.local.AppDatabase
import com.charleshartman.porchlightpress.data.local.SavedStory
import com.charleshartman.porchlightpress.data.local.Story
import com.charleshartman.porchlightpress.data.local.StoryFts
import com.charleshartman.porchlightpress.data.local.StorySource
import com.charleshartman.porchlightpress.data.repo.StorySearchRepository
import com.charleshartman.porchlightpress.domain.Place
import com.charleshartman.porchlightpress.ui.export.EditionPdf
import com.charleshartman.porchlightpress.ui.frontpage.FrontPageUiState
import com.charleshartman.porchlightpress.ui.frontpage.FrontStoryUi
import com.charleshartman.porchlightpress.ui.frontpage.SectionUi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device-level proof that local reading works after the process loses its DB handle. */
@RunWith(AndroidJUnit4::class)
class Phase8PersistencePdfTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "phase8-persistence-test.db"
    private var db: AppDatabase? = null
    private var exportedFile: File? = null

    private fun openDatabase(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
            .also { db = it }

    @After
    fun cleanUp() {
        db?.close()
        context.deleteDatabase(databaseName)
        exportedFile?.let { file ->
            if (Build.VERSION.SDK_INT >= 29) {
                // EditionPdf also publishes to Downloads. Only remove this test's
                // uniquely named copy, leaving the owner's real papers alone.
                runCatching {
                    context.contentResolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(file.name), null,
                    )?.use { rows ->
                        val idColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        while (rows.moveToNext()) {
                            context.contentResolver.delete(
                                ContentUris.withAppendedId(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    rows.getLong(idColumn),
                                ), null, null,
                            )
                        }
                    }
                }
            }
            file.delete()
        }
    }

    @Test
    fun savedStoryAndOfflineSearchSurviveDatabaseReopen() = runTest {
        val story = Story(
            id = "phase8-saved-story", headline = "Downtown library reopens",
            body = "The library reopened after repairs.", category = "local",
            generatedAt = "2026-09-25T10:00:00Z",
        )
        val first = openDatabase()
        first.storyDao().upsertStories(listOf(story))
        first.storyDao().upsertFts(listOf(StoryFts(
            storyId = story.id, headline = story.headline, dek = "",
            body = story.body.orEmpty(), publisher = "City Desk",
            locations = "Schenectady", lang = "en",
        )))
        first.storyDao().saveStory(SavedStory(story.id, savedAt = 1L))
        first.close()
        db = null

        // A fresh Room instance reads the same disk state with no feed request.
        val reopened = openDatabase()
        assertEquals(story.id, reopened.storyDao().savedStory(story.id)?.storyId)
        assertEquals(listOf(story.id), StorySearchRepository(reopened).search("library").map { it.id })
        assertEquals(listOf(story.id), StorySearchRepository(reopened).search("downt").map { it.id })
        assertTrue(StorySearchRepository(reopened).search("weather").isEmpty())
    }

    @Test
    fun generatedPaperOpensAndRendersInAndroidPdfRenderer() = runTest {
        val story = Story(
            id = "phase8-pdf-story", headline = "Downtown library reopens",
            dek = "Doors opened on Friday.",
            body = "The city library reopened after repairs to its roof.",
            generatedAt = "2026-09-25T10:00:00Z",
        )
        val edition = FrontPageUiState(
            isLoading = false,
            place = Place(
                id = "phase8-test",
                label = "Phase8 Test ${System.nanoTime()}",
                country = "US",
            ),
            sections = listOf(SectionUi("local", "Local", listOf(FrontStoryUi(story)))),
        )
        val file = EditionPdf.export(context, AppContainer(context), edition, "en")
        exportedFile = file
        assertTrue(file.isFile)
        assertTrue(file.length() > 100)
        assertTrue(EditionPdf.papers(context).any { it.absolutePath == file.absolutePath })

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertTrue(renderer.pageCount >= 1)
                renderer.openPage(0).use { page ->
                    assertEquals(612, page.width)
                    assertEquals(792, page.height)
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val allWhite = (0 until bitmap.height step 4).all { y ->
                            (0 until bitmap.width step 4).all { x -> bitmap.getPixel(x, y) == Color.WHITE }
                        }
                        assertFalse("The rendered first page should contain ink", allWhite)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun generatedPaperRendersQrCodesAndAiDisclaimer() = runTest {
        val db = openDatabase()
        val story = Story(
            id = "qr-test-story",
            headline = "Historic Downtown Theater Renovations Complete",
            dek = "The ribbon cutting is scheduled for Saturday.",
            body = "The downtown theater has finished comprehensive restoration work funded by local arts grants.",
            generatedAt = "2026-09-26T10:00:00Z",
            aiGenerated = true,
        )
        val source = StorySource(
            storyId = story.id,
            publisher = "Daily Gazette",
            headline = "Downtown Theater Reopening",
            url = "https://dailygazette.com/article/historic-theater-2026",
        )
        db.storyDao().insertStories(listOf(story))
        db.storyDao().insertSources(listOf(source))

        val edition = FrontPageUiState(
            isLoading = false,
            place = Place(
                id = "qr-test-place",
                label = "Schenectady, NY",
                country = "US",
            ),
            sections = listOf(SectionUi("local", "Local News", listOf(FrontStoryUi(story)))),
        )
        val container = AppContainer(context)
        val file = EditionPdf.export(context, container, edition, "en")
        exportedFile = file
        assertTrue(file.isFile)
        assertTrue(file.length() > 500)

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertTrue(renderer.pageCount >= 1)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val allWhite = (0 until bitmap.height step 4).all { y ->
                            (0 until bitmap.width step 4).all { x -> bitmap.getPixel(x, y) == Color.WHITE }
                        }
                        assertFalse("The rendered paper with QR codes should contain ink", allWhite)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }
}
