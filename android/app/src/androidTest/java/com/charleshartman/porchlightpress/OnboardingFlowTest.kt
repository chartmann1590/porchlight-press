package com.charleshartman.porchlightpress

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.charleshartman.porchlightpress.data.local.AppDatabase
import com.charleshartman.porchlightpress.data.local.PreferencesStore
import com.charleshartman.porchlightpress.data.repo.ConsentRepository
import com.charleshartman.porchlightpress.data.repo.ConsentState
import com.charleshartman.porchlightpress.data.repo.EditionRepository
import com.charleshartman.porchlightpress.data.repo.FakeConsentGateway
import com.charleshartman.porchlightpress.data.repo.FakeTranslatorEngine
import com.charleshartman.porchlightpress.data.repo.GeoLookup
import com.charleshartman.porchlightpress.data.repo.LocationRepository
import com.charleshartman.porchlightpress.data.repo.TranslationRepository
import com.charleshartman.porchlightpress.domain.Place
import com.charleshartman.porchlightpress.ui.onboarding.OnboardingRoute
import com.charleshartman.porchlightpress.ui.onboarding.OnboardingViewModel
import com.charleshartman.porchlightpress.ui.theme.PorchlightTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Complete onboarding via manual picker, via ZIP code, and with location
 * permission denied (Compose UI tests, run on-device in Phase 5).
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: AppDatabase
    private lateinit var prefs: PreferencesStore
    private lateinit var api: FakeFeedApi
    private lateinit var vm: OnboardingViewModel

    private val schenectady = Place(
        id = "place:us:schenectady", label = "Schenectady, NY", country = "US",
        admin1 = "US-NY", admin2 = "Schenectady County", city = "Schenectady",
        metro = "us-ny-capital-region", tz = "America/New_York",
    )
    private val geo = object : GeoLookup {
        override suspend fun reverseGeocode(lat: Double, lon: Double): Place = schenectady
        override suspend fun geocodePostal(countryIso2: String, code: String): List<Place> = emptyList()
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = PreferencesStore(context)
        api = FakeFeedApi()
        vm = OnboardingViewModel(
            SavedStateHandle(), prefs,
            EditionRepository(db),
            LocationRepository(context, api),
            TranslationRepository(db, FakeTranslatorEngine()),
            ConsentRepository(FakeConsentGateway(ConsentState.Obtained)),
            geo, api,
            listOf("local" to "Local", "sports" to "Sports"),
            db.savedLocationDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo()
        rule.onNodeWithTag(tag).performClick()
    }

    private fun walkToLocation(
        gpsClickHandler: (() -> Unit)? = null,
        // Never pop the real system notification dialog in tests; denial is fine.
        notificationHandler: (() -> Unit)? = {},
    ) {
        rule.setContent {
            PorchlightTheme {
                OnboardingRoute(
                    vm = vm,
                    activity = rule.activity,
                    gpsClickHandler = gpsClickHandler,
                    notificationHandler = notificationHandler,
                )
            }
        }
        rule.onNodeWithTag("step-welcome").assertIsDisplayed()
        tap("ob-continue")
        rule.onNodeWithTag("step-language").assertIsDisplayed()
        tap("ob-continue")
        rule.onNodeWithTag("step-location").assertIsDisplayed()
    }

    private fun finishFromConfirm() {
        rule.waitUntil(5000) { vm.state.value.confirmSections.isNotEmpty() }
        rule.onNodeWithTag("confirm-label").assertIsDisplayed()
        rule.onNodeWithText("Schenectady, NY", substring = true).assertIsDisplayed()
        tap("ob-confirm")
        rule.onNodeWithTag("step-interests").assertIsDisplayed()
        tap("interest-sports")
        tap("ob-continue")
        rule.onNodeWithTag("step-notifications").assertIsDisplayed()
        tap("notif-breaking")
        tap("ob-continue")
        rule.onNodeWithTag("step-privacy").assertIsDisplayed()
        tap("consent-analytics")
        tap("ob-privacy-continue")
        rule.waitUntil(10000) { vm.state.value.sync is com.charleshartman.porchlightpress.ui.onboarding.SyncUiState.Loaded }
        rule.onNodeWithTag("done-loaded").assertIsDisplayed()
        tap("done-finish")
        val snapshot = runBlocking { prefs.snapshot() }
        assertTrue(snapshot.onboardingDone)
        assertTrue(snapshot.readingStarted)
        assertEquals(setOf("sports"), snapshot.interests)
        assertTrue(snapshot.notifyBreaking)
        assertTrue(snapshot.analyticsConsent)
    }

    @Test
    fun completeOnboardingViaManualPicker() {
        walkToLocation()
        tap("loc-manual")
        rule.waitUntil(5000) { vm.state.value.admin1s.isNotEmpty() }
        tap("manual-admin1")
        tap("pick-admin1-US-NY")
        tap("manual-admin2")
        tap("pick-county-Schenectady County")
        rule.onNodeWithTag("manual-search").performScrollTo()
        rule.onNodeWithTag("manual-search").performTextInput("schen")
        rule.waitUntil(5000) { vm.state.value.cities.any { it.name == "Schenectady" } }
        tap("pick-city-Schenectady")
        rule.onNodeWithTag("step-confirm").assertIsDisplayed()
        finishFromConfirm()
    }

    @Test
    fun completeOnboardingViaZipCode() {
        walkToLocation()
        tap("loc-zip")
        rule.onNodeWithTag("zip-code").performScrollTo()
        rule.onNodeWithTag("zip-code").performTextInput("12308")
        tap("zip-submit")
        rule.onNodeWithTag("step-confirm").assertIsDisplayed()
        finishFromConfirm()
    }

    @Test
    fun locationPermissionDeniedFallsThroughToZip() {
        var denied = false
        walkToLocation(gpsClickHandler = {
            denied = true
            vm.onGpsResult(false, null, null)
        })
        tap("loc-gps")
        assertTrue(denied)
        // No nagging: ZIP/manual entry is offered instead.
        rule.onNodeWithTag("zip-entry").assertIsDisplayed()
        rule.onNodeWithTag("zip-code").performTextInput("99999")
        tap("zip-submit")
        rule.onNodeWithTag("zip-error").assertIsDisplayed()
    }

    @Test
    fun locationOptionIsRequired() {
        walkToLocation()
        // Tapping Continue with no place reveals the entry options, not CONFIRM.
        tap("ob-continue")
        rule.onNodeWithTag("step-location").assertIsDisplayed()
        assertTrue(vm.state.value.gpsFallback)
        rule.onNodeWithTag("zip-entry").assertIsDisplayed()
    }
}
