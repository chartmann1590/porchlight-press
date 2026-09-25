package com.charleshartman.porchlightpress

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.charleshartman.porchlightpress.data.ads.AdMobGate
import com.charleshartman.porchlightpress.data.local.SavedLocation
import com.charleshartman.porchlightpress.data.remote.SectionDto
import com.charleshartman.porchlightpress.data.repo.FakeTranslatorEngine
import com.charleshartman.porchlightpress.data.repo.TranslationRepository
import com.charleshartman.porchlightpress.domain.Place
import com.charleshartman.porchlightpress.ui.frontpage.FrontPageScreen
import com.charleshartman.porchlightpress.ui.frontpage.FrontPageViewModel
import com.charleshartman.porchlightpress.ui.theme.PorchlightTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real native-ad rendering proof (owner request B): a 3-section edition puts
 * a native slot in the feed; with ads enabled + initialized, the slot loads
 * Google's native TEST ad and renders it as a labelled "Sponsored" card.
 * Saves a screenshot to the app-private files dir for `adb pull`
 * (state/device/, never committed). Never taps the ad.
 */
@RunWith(AndroidJUnit4::class)
class AdRenderingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: AppContainer
    private lateinit var gate: AdMobGate

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context).apply {
            overrideForTests(
                edition = com.charleshartman.porchlightpress.data.repo.EditionRepository(db),
                translation = TranslationRepository(db, FakeTranslatorEngine()),
                weather = unavailableWeatherRepo(),
            )
        }
        gate = AdMobGate(context).apply { config = config.copy(enabled = true) }
        // Consent already resolved in this flow (test-only direct init with
        // test IDs; production path goes through UMP first). MobileAds must
        // initialize on the main thread, like the production caller.
        rule.runOnUiThread { gate.initializeIfConsented(true) }
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { container.db.close() } }
    }

    @Test
    fun nativeTestAdRendersAsSponsoredCard() {
        val place = Place(
            id = "place:ads:native", label = "Schenectady, NY", country = "US",
            admin1 = "US-NY", admin2 = "Schenectady County", city = "Schenectady",
            metro = "us-ny-capital-region", tz = "America/New_York",
        )
        runBlocking {
            container.prefs.setActiveLocationId(place.id)
            container.prefs.setAppLanguage("en")
            container.prefs.setOnboardingDone(true)
            container.prefs.setReadingStarted(true)
            container.db.savedLocationDao().upsert(
                SavedLocation(
                    id = place.id, label = place.label, country = place.country,
                    admin1 = place.admin1, admin2 = place.admin2, city = place.city,
                    metro = place.metro, tz = place.tz, isHome = true, sortOrder = 0,
                ),
            )
            val stories = (1..3).map { i ->
                testStoryDto().copy(
                    id = "story-ads-$i",
                    headline = "Test story number $i for the ad slot check",
                )
            }
            val edition = testEditionDto().copy(
                stories = stories,
                sections = listOf(
                    SectionDto(id = "s1", title = "Section One", storyIds = listOf("story-ads-1")),
                    SectionDto(id = "s2", title = "Section Two", storyIds = listOf("story-ads-2")),
                    SectionDto(id = "s3", title = "Section Three", storyIds = listOf("story-ads-3")),
                ),
            )
            container.editionRepository.persist(place, "latest", "feeds/test/latest.json", edition)
        }
        val vm = FrontPageViewModel(container)
        rule.setContent {
            PorchlightTheme {
                FrontPageScreen(
                    container = container,
                    viewModel = vm,
                    gate = gate,
                    onStoryClick = {},
                    onSectionClick = {},
                    onSwitchLocation = {},
                    onOpenInfo = {},
                )
            }
        }
        rule.waitUntil(15000) { vm.state.value.sections.isNotEmpty() }
        // The slot sits after the 3rd section: scroll the feed so it
        // composes (lazy items don't exist until scrolled into view).
        repeat(4) {
            rule.onNodeWithTag("front-list").performTouchInput { swipeUp() }
        }
        // The real test ad can take a few seconds on first load.
        rule.waitUntil(45000) {
            rule.onAllNodesWithTag("ad-native-label").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("ad-native-box").assertIsDisplayed()
        rule.onNodeWithTag("ad-native-label").assertIsDisplayed()
        // Let async media fill before the screenshot (real clock: the ad
        // WebView loads out-of-band from the Compose test clock).
        Thread.sleep(8000L)
        // Screenshot survives the post-run uninstall (app-private files do
        // not): MediaStore Downloads needs no permission for own entries.
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "p7-native-test.png")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS,
            )
        }
        val uri = resolver.insert(
            android.provider.MediaStore.Downloads.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY,
            ),
            values,
        ) ?: throw AssertionError("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { out ->
            val shot = File.createTempFile("p7-native", ".png")
            try {
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(shot)
                shot.inputStream().copyTo(out)
            } finally {
                shot.delete()
            }
        } ?: throw AssertionError("MediaStore write failed")
    }
}
