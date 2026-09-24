package com.charleshartman.porchlightpress

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.room.Room
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

@RunWith(AndroidJUnit4::class)
class NewspaperUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: AppContainer
    private lateinit var gate: AdMobGate

    private val schenectady = Place(
        id = "place:us:schenectady", label = "Schenectady, NY", country = "US",
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
        runBlocking {
            container.prefs.setActiveLocationId(schenectady.id)
            container.prefs.setAppLanguage("en")
            container.prefs.setOnboardingDone(true)
            container.prefs.setReadingStarted(true)
            container.db.savedLocationDao().upsert(
                SavedLocation(
                    id = schenectady.id, label = schenectady.label, country = schenectady.country,
                    admin1 = schenectady.admin1, admin2 = schenectady.admin2, city = schenectady.city,
                    metro = schenectady.metro, tz = schenectady.tz, isHome = true, sortOrder = 0,
                ),
            )
            val edition = testEditionDto()
            container.editionRepository.persist(schenectady, "latest", "feeds/us/ny/schenectady/latest.json", edition)
        }
        gate = AdMobGate(context).apply { config = config.copy(enabled = false) }
    }

    @After
    fun tearDown() {
        runBlocking { container.db.close() }
    }

    @Test
    fun frontPageRendersFixtureEdition() {
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
        rule.waitUntil(5000) { vm.state.value.sections.isNotEmpty() }
        rule.onNodeWithTag("masthead").assertIsDisplayed()
        rule.onNodeWithTag("masthead-title").assertIsDisplayed()
        rule.onNodeWithTag("edition-label").assertIsDisplayed()
        rule.onNodeWithTag("front-list").assertIsDisplayed()
        rule.onNodeWithTag("hero-story-${TEST_STORY_ID}").assertIsDisplayed()
        rule.onNodeWithText("City council approves downtown revitalization project", substring = true).assertIsDisplayed()
    }

    @Test
    fun fakeTranslatedFixtureShowsLabelAndToggle() {
        runBlocking {
            container.prefs.setAppLanguage("es")
            container.db.translationDao().upsertStoryTranslations(
                listOf(
                    StoryTranslation(
                        storyId = TEST_STORY_ID, version = 1, lang = "es",
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
        rule.waitUntil(5000) { vm.state.value.sections.isNotEmpty() }
        rule.onNodeWithTag("translation-label-section").assertIsDisplayed()
        rule.onNodeWithText("El concejo aprueba proyecto", substring = true).assertIsDisplayed()

        val articleVm = ArticleViewModel(container, TEST_STORY_ID)
        rule.setContent {
            PorchlightTheme { ArticleScreen(viewModel = articleVm, onBack = {}) }
        }
        rule.waitUntil(5000) { articleVm.state.value.story != null }
        rule.onNodeWithTag("translation-label").assertIsDisplayed()
        rule.onNodeWithTag("toggle-original").assertIsDisplayed()
        rule.onNodeWithTag("toggle-original").performClick()
        rule.onNodeWithText("City council approves downtown revitalization project", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("toggle-original").performClick()
        rule.onNodeWithText("El concejo aprueba proyecto", substring = true).assertIsDisplayed()
    }

    @Test
    fun rtlRendersWithoutClipping() {
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
        rule.waitUntil(5000) { vm.state.value.sections.isNotEmpty() }
        rule.onNodeWithTag("masthead").assertIsDisplayed()
        rule.onNodeWithTag("front-list").assertIsDisplayed()
        rule.onNodeWithTag("hero-story-${TEST_STORY_ID}").assertIsDisplayed()
    }

    @Test
    fun aiDisclosurePresentOnAiStoriesAndAbsentOnSourceCards() {
        val aiVm = ArticleViewModel(container, TEST_STORY_ID)
        rule.setContent { PorchlightTheme { ArticleScreen(viewModel = aiVm, onBack = {}) } }
        rule.waitUntil(5000) { aiVm.state.value.story != null }
        rule.onNodeWithTag("ai-disclosure").assertIsDisplayed()
        rule.onNodeWithTag("ai-badge").assertIsDisplayed()

        val cardId = "source-card-id-001"
        runBlocking {
            val edition = testEditionDto().copy(
                stories = listOf(testStoryDto().copy(id = cardId, headline = "Source card headline", aiGenerated = false, aiModel = null, body = null)),
                sections = listOf(com.charleshartman.porchlightpress.data.remote.SectionDto(id = "top", title = "Top Stories", storyIds = listOf(cardId))),
            )
            container.editionRepository.persist(schenectady, "latest", "feeds/us/ny/schenectady/latest.json", edition)
        }
        val cardVm = ArticleViewModel(container, cardId)
        rule.setContent { PorchlightTheme { ArticleScreen(viewModel = cardVm, onBack = {}) } }
        rule.waitUntil(5000) { cardVm.state.value.story != null }
        rule.onNodeWithTag("source-card").assertIsDisplayed()
        rule.onNodeWithTag("ai-disclosure").assertDoesNotExist()
    }

    @Test
    fun tappingSourceFiresOpenUrlIntent() {
        val vm = ArticleViewModel(container, TEST_STORY_ID)
        rule.setContent { PorchlightTheme { ArticleScreen(viewModel = vm, onBack = {}) } }
        rule.waitUntil(5000) { vm.state.value.story != null }
        rule.onNodeWithTag("sources-header").assertIsDisplayed()
        rule.onNodeWithTag("source-link").assertIsDisplayed()
        rule.onNodeWithTag("source-link").performClick()
        rule.onNodeWithTag("article-screen").assertIsDisplayed()
    }
}
