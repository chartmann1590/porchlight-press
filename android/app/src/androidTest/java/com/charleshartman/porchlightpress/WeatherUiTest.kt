package com.charleshartman.porchlightpress

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.charleshartman.porchlightpress.data.ads.AdMobGate
import com.charleshartman.porchlightpress.data.ads.InterstitialController
import com.charleshartman.porchlightpress.data.local.SavedLocation
import com.charleshartman.porchlightpress.data.repo.FakeTranslatorEngine
import com.charleshartman.porchlightpress.data.repo.TranslationRepository
import com.charleshartman.porchlightpress.domain.Place
import com.charleshartman.porchlightpress.ui.frontpage.FrontPageScreen
import com.charleshartman.porchlightpress.ui.frontpage.FrontPageViewModel
import com.charleshartman.porchlightpress.ui.nav.PorchlightNavGraph
import com.charleshartman.porchlightpress.ui.nav.Routes
import com.charleshartman.porchlightpress.ui.theme.PorchlightTheme
import com.charleshartman.porchlightpress.ui.weather.AlertDetailScreen
import com.charleshartman.porchlightpress.ui.weather.WeatherScreen
import com.charleshartman.porchlightpress.ui.weather.WeatherViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 7 weather UI tests: front-page slot + alert banner, Weather screen
 * (Screen 10), alert detail, and MET Norway attribution for non-US places.
 * Every test seeds its own hermetic place/edition and a canned weather repo
 * (no network).
 */
@RunWith(AndroidJUnit4::class)
class WeatherUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: AppContainer
    private lateinit var gate: AdMobGate

    private fun usPlace(test: String) = Place(
        id = "place:wx:$test", label = "Schenectady, NY", country = "US",
        admin1 = "US-NY", admin2 = "Schenectady County", city = "Schenectady",
        metro = "us-ny-capital-region", tz = "America/New_York",
    )

    private fun seedEdition(place: Place, storyId: String) {
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
            val story = testStoryDto().copy(id = storyId)
            val edition = testEditionDto().copy(
                stories = listOf(story),
                sections = listOf(
                    com.charleshartman.porchlightpress.data.remote.SectionDto(
                        id = "top", title = "Top Stories", storyIds = listOf(storyId),
                    ),
                ),
            )
            container.editionRepository.persist(place, "latest", "feeds/us/ny/schenectady/latest.json", edition)
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context).apply {
            overrideForTests(
                edition = com.charleshartman.porchlightpress.data.repo.EditionRepository(db),
                translation = TranslationRepository(db, FakeTranslatorEngine()),
                weather = cannedNwsWeatherRepo(),
            )
        }
        gate = AdMobGate(context).apply { config = config.copy(enabled = false) }
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { container.db.close() } }
    }

    @Test
    fun frontPageShowsWeatherSlotAndAlertBanner() {
        val place = usPlace("slot")
        seedEdition(place, "story-wx-slot")
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
                    onOpenWeather = {},
                    onAlertClick = {},
                )
            }
        }
        rule.waitUntil(15000) { vm.state.value.weather != null }
        rule.waitUntil(15000) { vm.state.value.sections.isNotEmpty() }
        // VM contract first (splits repo failures from composition issues).
        org.junit.Assert.assertEquals(
            "urn:test:flood-warning-1",
            (vm.state.value.weather as? com.charleshartman.porchlightpress.data.weather.WeatherRepository.Snapshot.Ready)
                ?.data?.alerts?.singleOrNull()?.id,
        )
        // Teaser: 21.1°C → 70°F for the US place (assert on the tagged slot,
        // since the same temp text also appears in the card/strip).
        rule.onNodeWithTag("weather-teaser").assertIsDisplayed()
        rule.onNodeWithTag("weather-temp", useUnmergedTree = true).assertTextContains("70°F", substring = true)
        // Severe banner sits at the top (visible before scrolling).
        rule.onNodeWithTag("alert-banner").assertIsDisplayed()
        rule.onNodeWithText("FLOOD WARNING", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Tap for details", substring = true).assertIsDisplayed()
        // Card + strip may sit below the fold: scroll them into view first.
        // (performScrollTo scrolls every scrollable parent, including lazy
        // lists whose items compose on demand.)
        rule.onNodeWithTag("weather-card").performScrollTo()
        rule.onNodeWithTag("weather-card").assertIsDisplayed()
        // The strip lives inside the clickable card, so its tag merges up:
        // find it in the unmerged tree (established pattern).
        rule.onNodeWithTag("weather-hour-strip", useUnmergedTree = true).performScrollTo()
        rule.onNodeWithTag("weather-hour-strip", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun weatherScreenRendersForecastAlertsAndAttribution() {
        val place = usPlace("screen")
        seedEdition(place, "story-wx-screen")
        val vm = WeatherViewModel(container)
        rule.setContent {
            PorchlightTheme {
                WeatherScreen(container = container, viewModel = vm, onBack = {}, onAlertClick = {})
            }
        }
        rule.waitUntil(15000) { vm.state.value.data != null }
        org.junit.Assert.assertEquals(
            "urn:test:flood-warning-1",
            vm.state.value.data?.alerts?.singleOrNull()?.id,
        )
        rule.onNodeWithTag("weather-screen").assertIsDisplayed()
        rule.onNodeWithTag("weather-current-temp").assertIsDisplayed()
        rule.onNodeWithTag("weather-current-temp").assertTextContains("70°F", substring = true)
        rule.onNodeWithTag("weather-feels-like").assertIsDisplayed()
        rule.onNodeWithTag("weather-hourly").assertIsDisplayed()
        rule.onNodeWithTag("weather-daily-header").assertIsDisplayed()
        rule.onNodeWithTag("weather-alerts-header").assertIsDisplayed()
        // Alerts + attribution sit below the fold in a LazyColumn (items
        // compose on demand while scrolling into view).
        rule.onNodeWithTag("alert-banner").performScrollTo()
        rule.onNodeWithTag("alert-banner").assertIsDisplayed()
        rule.onNodeWithText("National Weather Service", substring = true).performScrollTo()
        rule.onNodeWithText("National Weather Service", substring = true).assertIsDisplayed()
    }

    @Test
    fun alertDetailShowsFullTextAndWeatherGovLink() {
        val place = usPlace("detail")
        seedEdition(place, "story-wx-detail")
        rule.setContent {
            PorchlightTheme {
                AlertDetailScreen(container = container, alertId = "urn:test:flood-warning-1", onBack = {})
            }
        }
        rule.waitUntil(15000) {
            rule.onAllNodesWithTag("alert-detail-description").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("alert-detail-screen").assertIsDisplayed()
        rule.onNodeWithText("Mohawk River", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("alert-detail-instruction").assertIsDisplayed()
        rule.onNodeWithText("Turn around", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("alert-detail-link").assertIsDisplayed()
        rule.onNodeWithText("weather.gov", substring = true).assertIsDisplayed()
    }

    @Test
    fun nonUsPlaceShowsMetAttribution() {
        val oslo = Place(
            id = "place:wx:oslo", label = "Oslo", country = "NO",
            city = "Oslo", tz = "Europe/Oslo",
        )
        container.overrideForTests(weather = cannedMetWeatherRepo())
        seedEdition(oslo, "story-wx-oslo")
        val vm = WeatherViewModel(container)
        rule.setContent {
            PorchlightTheme {
                WeatherScreen(container = container, viewModel = vm, onBack = {}, onAlertClick = {})
            }
        }
        rule.waitUntil(15000) { vm.state.value.data != null }
        // 14.2°C stays Celsius outside the US (assert on the tagged current
        // row; the same value also appears in the hourly strip).
        rule.onNodeWithTag("weather-current-temp").assertTextContains("14°C", substring = true)
        rule.onNodeWithText("MET Norway", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("weather-alerts-empty").assertIsDisplayed()
    }

    @Test
    fun backFromDeepLinkedDetailStaysOnWeather() {
        // Regression: the notification deep link must be consumed once —
        // popping back from the alert detail to Weather must not re-push
        // the detail (an infinite Back trap).
        val place = usPlace("deeplink")
        seedEdition(place, "story-wx-deep")
        lateinit var nav: NavHostController
        rule.setContent {
            PorchlightTheme {
                nav = rememberNavController()
                PorchlightNavGraph(
                    container = container,
                    gate = gate,
                    interstitial = InterstitialController(),
                    onSwitchLocation = {},
                    navController = nav,
                    startDestination = Routes.WEATHER,
                    startAlertId = "urn:test:flood-warning-1",
                )
            }
        }
        rule.waitUntil(15000) {
            rule.onAllNodesWithTag("alert-detail-screen").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("alert-detail-screen").assertIsDisplayed()
        rule.runOnUiThread { nav.popBackStack() }
        rule.waitUntil(15000) {
            rule.onAllNodesWithTag("weather-screen").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("weather-screen").assertIsDisplayed()
        // Settle: the detail must not come back.
        rule.mainClock.advanceTimeBy(2000L)
        rule.onAllNodesWithTag("alert-detail-screen").assertCountEquals(0)
    }
}
