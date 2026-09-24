package com.charleshartman.porchlightpress

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.charleshartman.porchlightpress.data.ads.AdMobGate
import com.charleshartman.porchlightpress.data.local.SavedLocation
import com.charleshartman.porchlightpress.data.local.StoryTranslation
import com.charleshartman.porchlightpress.data.repo.FakeTranslatorEngine
import com.charleshartman.porchlightpress.data.repo.TranslationRepository
import com.charleshartman.porchlightpress.domain.Place
import com.charleshartman.porchlightpress.ui.article.ArticleScreen
import com.charleshartman.porchlightpress.ui.article.ArticleViewModel
import com.charleshartman.porchlightpress.ui.frontpage.FrontPageViewModel
import com.charleshartman.porchlightpress.ui.frontpage.FrontPageScreen
import com.charleshartman.porchlightpress.ui.theme.PorchlightTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Newspaper UI tests. Each test seeds its own hermetic edition (unique
 * place/story ids) into the shared on-device database so no test can
 * observe another test's rows. One setContent per test.
 */
@RunWith(AndroidJUnit4::class)
class NewspaperUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // Stubs external launches so taps that fire open-URL intents stay
    // in-process and the fired intent becomes verifiable.
    @get:Rule val intentsRule = androidx.test.espresso.intent.rule.IntentsRule()

    private lateinit var container: AppContainer
    private lateinit var gate: AdMobGate

    private fun placeFor(test: String) = Place(
        id = "place:test:$test", label = "Schenectady, NY", country = "US",
        admin1 = "US-NY", admin2 = "Schenectady County", city = "Schenectady",
        metro = "us-ny-capital-region", tz = "America/New_York",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context).apply {
            overrideForTests(
                edition = com.charleshartman.porchlightpress.data.repo.EditionRepository(db),
                translation = TranslationRepository(db, FakeTranslatorEngine()),
            )
        }
        gate = AdMobGate(context).apply { config = config.copy(enabled = false) }
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { container.db.close() } }
    }

    /** Seed one AI story edition under a unique place; return (place, storyId). */
    private fun seedAiStory(test: String, lang: String = "en"): Pair<Place, String> {
        val place = placeFor(test)
        val storyId = "story-$test-ai"
        runBlocking {
            container.prefs.setActiveLocationId(place.id)
            container.prefs.setAppLanguage(lang)
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
        return place to storyId
    }

    @Test
    fun frontPageRendersFixtureEdition() {
        val (_, storyId) = seedAiStory("front")
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
        rule.onNodeWithTag("masthead").assertIsDisplayed()
        rule.onNodeWithTag("masthead-title").assertIsDisplayed()
        rule.onNodeWithTag("edition-label").assertIsDisplayed()
        rule.onNodeWithTag("front-list").assertIsDisplayed()
        rule.onNodeWithTag("hero-story-$storyId").assertIsDisplayed()
        rule.onNodeWithText("City council approves downtown revitalization project", substring = true).assertIsDisplayed()
    }

    @Test
    fun translatedFrontPageShowsLabel() {
        val (_, storyId) = seedAiStory("trfront", lang = "es")
        runBlocking {
            container.db.translationDao().upsertStoryTranslations(
                listOf(
                    StoryTranslation(
                        storyId = storyId, version = 1, lang = "es",
                        headline = "El concejo aprueba proyecto", dek = "Voto 5-2", body = "Texto traducido",
                    ),
                ),
            )
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
        rule.onNodeWithTag("translation-label-section").assertIsDisplayed()
        rule.onNodeWithText("El concejo aprueba proyecto", substring = true).assertIsDisplayed()
    }

    @Test
    fun articleTranslationToggleShowsOriginal() {
        val (_, storyId) = seedAiStory("trtoggle", lang = "es")
        runBlocking {
            container.db.translationDao().upsertStoryTranslations(
                listOf(
                    StoryTranslation(
                        storyId = storyId, version = 1, lang = "es",
                        headline = "El concejo aprueba proyecto", dek = "Voto 5-2", body = "Texto traducido",
                    ),
                ),
            )
        }
        val articleVm = ArticleViewModel(container, storyId)
        rule.setContent {
            PorchlightTheme { ArticleScreen(viewModel = articleVm, onBack = {}) }
        }
        rule.waitUntil(15000) { articleVm.state.value.story != null }
        rule.onNodeWithTag("translation-label").assertIsDisplayed()
        rule.onNodeWithTag("toggle-original").assertIsDisplayed()
        rule.onNodeWithTag("toggle-original").performClick()
        rule.onNodeWithText("City council approves downtown revitalization project", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("toggle-original").performClick()
        rule.onNodeWithText("El concejo aprueba proyecto", substring = true).assertIsDisplayed()
    }

    @Test
    fun rtlRendersWithoutClipping() {
        val (_, storyId) = seedAiStory("rtl")
        val vm = FrontPageViewModel(container)
        rule.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
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
        }
        rule.waitUntil(15000) { vm.state.value.sections.isNotEmpty() }
        rule.onNodeWithTag("masthead").assertIsDisplayed()
        rule.onNodeWithTag("front-list").assertIsDisplayed()
        rule.onNodeWithTag("hero-story-$storyId").assertIsDisplayed()
    }

    @Test
    fun aiDisclosurePresentOnAiStory() {
        val (_, storyId) = seedAiStory("aidisc")
        val aiVm = ArticleViewModel(container, storyId)
        rule.setContent { PorchlightTheme { ArticleScreen(viewModel = aiVm, onBack = {}) } }
        rule.waitUntil(15000) { aiVm.state.value.story != null }
        rule.onNodeWithTag("ai-disclosure").assertIsDisplayed()
        rule.onNodeWithTag("ai-badge").assertIsDisplayed()
    }

    @Test
    fun aiDisclosureAbsentOnSourceCard() {
        val place = placeFor("card")
        val cardId = "story-card-nogen"
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
            val edition = testEditionDto().copy(
                stories = listOf(testStoryDto().copy(id = cardId, headline = "Source card headline", aiGenerated = false, aiModel = null, body = null)),
                sections = listOf(
                    com.charleshartman.porchlightpress.data.remote.SectionDto(
                        id = "top", title = "Top Stories", storyIds = listOf(cardId),
                    ),
                ),
            )
            container.editionRepository.persist(place, "latest", "feeds/us/ny/schenectady/latest.json", edition)
        }
        val cardVm = ArticleViewModel(container, cardId)
        rule.setContent { PorchlightTheme { ArticleScreen(viewModel = cardVm, onBack = {}) } }
        rule.waitUntil(15000) { cardVm.state.value.story != null }
        rule.onNodeWithTag("source-card").assertIsDisplayed()
        rule.onAllNodesWithTag("ai-disclosure").assertCountEquals(0)
    }

    @Test
    fun tappingSourceOpensIt() {
        val (_, storyId) = seedAiStory("tap")
        val vm = ArticleViewModel(container, storyId)
        rule.setContent { PorchlightTheme { ArticleScreen(viewModel = vm, onBack = {}) } }
        rule.waitUntil(15000) { vm.state.value.story != null }
        val st = vm.state.value
        org.junit.Assert.assertTrue(
            "story=" + st.story?.id + " sources=" + st.sources.size,
            st.sources.isNotEmpty(),
        )
        // Sources render below the fold; scroll the section into view first.
        // Note: SourceRow is clickable, and clickable merges descendants, so
        // the inner link tag only exists in the unmerged tree.
        rule.onNodeWithTag("sources-header").performScrollTo()
        rule.onNodeWithTag("sources-header").assertIsDisplayed()
        rule.onNodeWithTag("source-link", useUnmergedTree = true).performScrollTo()
        rule.onNodeWithTag("source-link", useUnmergedTree = true).assertIsDisplayed()
        // Tapping fires an open-URL intent. Stub the external launch so the
        // article stays put and the fired intent becomes verifiable.
        androidx.test.espresso.intent.Intents.intending(
            androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(android.content.Intent.ACTION_VIEW),
        ).respondWith(android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, null))
        rule.onNodeWithTag("source-link", useUnmergedTree = true).performClick()
        androidx.test.espresso.intent.Intents.intended(
            org.hamcrest.CoreMatchers.allOf(
                androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(android.content.Intent.ACTION_VIEW),
                androidx.test.espresso.intent.matcher.IntentMatchers.hasData("https://example.com/council-downtown"),
            ),
        )
        rule.onNodeWithTag("article-screen").assertIsDisplayed()
    }
}
