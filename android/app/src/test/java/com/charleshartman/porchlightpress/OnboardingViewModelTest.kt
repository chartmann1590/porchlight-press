package com.charleshartman.porchlightpress

import androidx.lifecycle.SavedStateHandle
import com.charleshartman.porchlightpress.data.local.AppPrefs
import com.charleshartman.porchlightpress.data.local.PreferencesStore
import com.charleshartman.porchlightpress.data.remote.FeedApi
import com.charleshartman.porchlightpress.data.remote.PlaceDto
import com.charleshartman.porchlightpress.data.remote.PostalEntryDto
import com.charleshartman.porchlightpress.data.repo.ConsentRepository
import com.charleshartman.porchlightpress.data.repo.ConsentState
import com.charleshartman.porchlightpress.data.repo.EditionRepository
import com.charleshartman.porchlightpress.data.repo.FakeConsentGateway
import com.charleshartman.porchlightpress.data.repo.GeoLookup
import com.charleshartman.porchlightpress.data.repo.LocationRepository
import com.charleshartman.porchlightpress.data.repo.SyncSummary
import com.charleshartman.porchlightpress.data.repo.TranslationRepository
import com.charleshartman.porchlightpress.domain.Place
import com.charleshartman.porchlightpress.domain.FeedResult
import com.charleshartman.porchlightpress.ui.onboarding.OnboardingStep
import com.charleshartman.porchlightpress.ui.onboarding.OnboardingViewModel
import com.charleshartman.porchlightpress.ui.onboarding.SyncUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var prefs: PreferencesStore
    private lateinit var editionRepo: EditionRepository
    private lateinit var locationRepo: LocationRepository
    private lateinit var translationRepo: TranslationRepository
    private lateinit var consentRepo: ConsentRepository
    private lateinit var geo: GeoLookup
    private lateinit var feedApi: FeedApi
    private lateinit var locationDao: com.charleshartman.porchlightpress.data.local.SavedLocationDao

    private val schenectady = Place(
        id = "place:us:schenectady", label = "Schenectady, NY", country = "US",
        admin1 = "US-NY", admin2 = "Schenectady County", city = "Schenectady",
        metro = "us-ny-capital-region", tz = "America/New_York",
    )
    private val summary = SyncSummary("e1", "latest", 3, "feeds/us/ny/schenectady/latest.json", false, "Schenectady, NY", "2026-09-24T00:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        prefs = mockk(relaxed = true)
        coEvery { prefs.snapshot() } returns AppPrefs()
        editionRepo = mockk(relaxed = true)
        coEvery { editionRepo.sync(any(), any(), any()) } returns FeedResult.Ok(summary)
        coEvery { editionRepo.probeFeed(any(), any()) } returns true
        locationRepo = mockk(relaxed = true)
        coEvery { locationRepo.places(any()) } returns listOf(
            PlaceDto(name = "United States", type = "country", country = "US"),
            PlaceDto(name = "New York", type = "admin1", country = "US", admin1 = "US-NY", admin1Name = "New York", timezone = "America/New_York"),
            PlaceDto(name = "Schenectady County", type = "admin2", country = "US", admin1 = "US-NY", admin2 = "Schenectady County", metro = "us-ny-capital-region", timezone = "America/New_York"),
            PlaceDto(name = "Schenectady", type = "city", country = "US", admin1 = "US-NY", admin2 = "Schenectady County", city = "Schenectady", metro = "us-ny-capital-region", timezone = "America/New_York"),
        )
        coEvery { locationRepo.postalEntries(any()) } returns listOf(
            PostalEntryDto(postal = "12308", country = "US", admin1 = "US-NY", admin2 = "Schenectady County", city = "Schenectady", metro = "us-ny-capital-region"),
        )
        translationRepo = mockk(relaxed = true)
        every { translationRepo.supportedLanguages() } returns listOf("en", "es")
        coEvery { translationRepo.ensureModel(any(), any()) } returns true
        consentRepo = ConsentRepository(FakeConsentGateway(ConsentState.Obtained))
        geo = object : GeoLookup {
            override suspend fun reverseGeocode(lat: Double, lon: Double): Place = schenectady
            override suspend fun geocodePostal(countryIso2: String, code: String): List<Place> = emptyList()
        }
        feedApi = mockk(relaxed = true)
        locationDao = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        handle: SavedStateHandle = SavedStateHandle(),
        dao: com.charleshartman.porchlightpress.data.local.SavedLocationDao = locationDao,
    ) = OnboardingViewModel(
        handle, prefs, editionRepo, locationRepo, translationRepo,
        consentRepo, geo, feedApi, listOf("local" to "Local", "sports" to "Sports"), dao,
    )

    @Test
    fun walksEveryStepForward() = runTest {
        val v = vm()
        advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, v.state.value.step)
        v.onContinue() // -> LANGUAGE
        v.onContinue() // -> LOCATION
        assertEquals(OnboardingStep.LOCATION, v.state.value.step)
        v.onZipCode("12308")
        v.submitZip()
        advanceUntilIdle()
        assertEquals(OnboardingStep.CONFIRM, v.state.value.step)
        assertEquals("Schenectady, NY", v.state.value.place?.label)
        v.onContinue() // -> INTERESTS
        v.toggleInterest("sports")
        v.onContinue() // -> NOTIFICATIONS (severe defaults on for US)
        assertTrue(v.state.value.notifySevere)
        v.onContinue() // -> PRIVACY
        assertEquals(OnboardingStep.PRIVACY, v.state.value.step)
    }

    @Test
    fun backWalksBackwards() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.onContinue()
        v.onContinue()
        assertEquals(OnboardingStep.LOCATION, v.state.value.step)
        assertTrue(v.onBack())
        assertEquals(OnboardingStep.LANGUAGE, v.state.value.step)
        assertTrue(v.onBack())
        assertEquals(OnboardingStep.WELCOME, v.state.value.step)
        assertFalse(v.onBack())
    }

    @Test
    fun skipsClearAndAdvance() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.onContinue(); v.onContinue()
        v.onZipCode("12308"); v.submitZip(); advanceUntilIdle()
        v.onContinue() // INTERESTS
        v.toggleInterest("sports")
        assertTrue(v.onSkip()) // -> NOTIFICATIONS, interests cleared
        assertEquals(OnboardingStep.NOTIFICATIONS, v.state.value.step)
        assertTrue(v.state.value.interests.isEmpty())
        assertTrue(v.onSkip()) // -> PRIVACY, toggles cleared
        assertEquals(OnboardingStep.PRIVACY, v.state.value.step)
        assertFalse(v.state.value.notifySevere)
        assertTrue(v.onSkip()) // privacy skip finishes without consents
        advanceUntilIdle()
        assertEquals(OnboardingStep.DONE, v.state.value.step)
        coVerify { prefs.setAnalyticsConsent(false) }
        coVerify { prefs.setOnboardingDone(true) }
    }

    @Test
    fun locationStepCannotBeSkipped() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.onContinue(); v.onContinue()
        assertFalse(v.onSkip())
        assertEquals(OnboardingStep.LOCATION, v.state.value.step)
    }

    @Test
    fun permissionDeniedFallsThroughWithoutNagging() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.onContinue(); v.onContinue()
        v.chooseOption(com.charleshartman.porchlightpress.ui.onboarding.LocationOption.GPS)
        v.onGpsResult(false, null, null)
        advanceUntilIdle()
        assertTrue(v.state.value.gpsFallback)
        assertEquals(OnboardingStep.LOCATION, v.state.value.step)
        assertNull(v.state.value.place)
    }

    @Test
    fun gpsGrantedResolvesToConfirm() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.onContinue(); v.onContinue()
        v.onGpsResult(true, 42.81, -73.94)
        advanceUntilIdle()
        assertEquals(OnboardingStep.CONFIRM, v.state.value.step)
        assertEquals("Schenectady, NY", v.state.value.place?.label)
        assertTrue(v.state.value.confirmSections.isNotEmpty())
    }

    @Test
    fun unknownZipShowsInlineError() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.onContinue(); v.onContinue()
        v.onZipCode("99999")
        v.submitZip()
        advanceUntilIdle()
        assertEquals(OnboardingStep.LOCATION, v.state.value.step)
        assertTrue(v.state.value.zipError?.contains("manually") == true)
    }

    @Test
    fun badZipFormatShowsError() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.onContinue(); v.onContinue()
        v.onZipCode("abc")
        v.submitZip()
        advanceUntilIdle()
        assertTrue(v.state.value.zipError?.contains("5-digit") == true)
    }

    @Test
    fun manualCityCompletesToConfirm() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.onContinue(); v.onContinue()
        v.chooseOption(com.charleshartman.porchlightpress.ui.onboarding.LocationOption.MANUAL)
        advanceUntilIdle()
        v.setManualAdmin1("US-NY")
        v.setManualAdmin2("Schenectady County")
        v.setManualQuery("schen")
        val city = v.state.value.cities.first()
        assertEquals("Schenectady", city.name)
        v.setManualCity(city)
        advanceUntilIdle()
        assertEquals(OnboardingStep.CONFIRM, v.state.value.step)
    }

    @Test
    fun privacyAcceptPersistsAndStartsSync() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.onContinue(); v.onContinue()
        v.onZipCode("12308"); v.submitZip(); advanceUntilIdle()
        v.onContinue(); v.onContinue(); v.onContinue()
        v.setAnalyticsConsent(true)
        v.acceptPrivacyAndContinue(mockk(relaxed = true))
        advanceUntilIdle()
        assertEquals(OnboardingStep.DONE, v.state.value.step)
        coVerify { prefs.setAnalyticsConsent(true) }
        coVerify { prefs.setCrashConsent(false) }
        coVerify { prefs.setOnboardingDone(true) }
        coVerify { prefs.setActiveLocationId(any()) }
        val sync = v.state.value.sync
        assertTrue(sync is SyncUiState.Loaded)
        assertEquals(3, (sync as SyncUiState.Loaded).summary.storyCount)
    }

    @Test
    fun privacySkipStillResolvesConsent() = runTest {
        // Skip must not leave consent Unknown (ads would never initialize):
        // it resolves UMP silently and persists opt-out.
        val v = vm()
        advanceUntilIdle()
        v.onContinue(); v.onContinue()
        v.onZipCode("12308"); v.submitZip(); advanceUntilIdle()
        v.onContinue(); v.onContinue(); v.onContinue()
        v.setNotifyBreaking(true)
        v.acceptPrivacyAndSkip(mockk(relaxed = true))
        advanceUntilIdle()
        assertEquals(OnboardingStep.DONE, v.state.value.step)
        assertEquals(ConsentState.Obtained, v.state.value.consent)
        coVerify { prefs.setAnalyticsConsent(false) }
        coVerify { prefs.setCrashConsent(false) }
        coVerify { prefs.setNotifyBreaking(true) }
    }

    @Test
    fun offlineSyncShowsOfflineState() = runTest {
        coEvery { editionRepo.sync(any(), any(), any()) } returns FeedResult.Offline(null)
        val v = vm()
        advanceUntilIdle()
        v.onContinue(); v.onContinue()
        v.onZipCode("12308"); v.submitZip(); advanceUntilIdle()
        v.onContinue(); v.onContinue(); v.onContinue()
        v.acceptPrivacyAndContinue(mockk(relaxed = true))
        advanceUntilIdle()
        assertTrue(v.state.value.sync is SyncUiState.Offline)
    }

    @Test
    fun languageSelectAndModelDownload() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.onContinue()
        v.selectLanguage("es")
        assertEquals("es", v.state.value.language)
        v.downloadModel()
        advanceUntilIdle()
        assertEquals(false, v.state.value.modelDownloading)
        assertTrue(v.state.value.modelNote?.contains("Downloaded") == true)
    }

    @Test
    fun stateSurvivesInSavedStateHandle() = runTest {
        val handle = SavedStateHandle()
        val first = OnboardingViewModel(
            handle, prefs, editionRepo, locationRepo, translationRepo,
            consentRepo, geo, feedApi, emptyList(), locationDao,
        )
        advanceUntilIdle()
        first.onContinue()
        first.selectLanguage("es")
        first.onZipCode("12308")
        val second = OnboardingViewModel(
            handle, prefs, editionRepo, locationRepo, translationRepo,
            consentRepo, geo, feedApi, emptyList(), locationDao,
        )
        advanceUntilIdle()
        assertEquals(OnboardingStep.LANGUAGE, second.state.value.step)
        assertEquals("es", second.state.value.language)
        assertEquals("12308", second.state.value.zipCode)
    }

    @Test
    fun freshLaunchAfterDoneResumesAtDone() = runTest {
        coEvery { prefs.snapshot() } returns AppPrefs(onboardingDone = true, activeLocationId = "place:us:schenectady")
        val dao = mockk<com.charleshartman.porchlightpress.data.local.SavedLocationDao>(relaxed = true)
        coEvery { dao.byId("place:us:schenectady") } returns com.charleshartman.porchlightpress.data.local.SavedLocation(
            id = "place:us:schenectady", label = "Schenectady, NY", country = "US",
            admin1 = "US-NY", city = "Schenectady", tz = "America/New_York", isHome = true,
        )
        val v = OnboardingViewModel(
            SavedStateHandle(), prefs, editionRepo, locationRepo, translationRepo,
            consentRepo, geo, feedApi, emptyList(), dao,
        )
        advanceUntilIdle()
        assertEquals(OnboardingStep.DONE, v.state.value.step)
        assertEquals("Schenectady, NY", v.state.value.place?.label)
        assertTrue(v.state.value.sync is SyncUiState.Loaded)
    }

    @Test
    fun freshLaunchWithMissingSavedLocationStaysAtWelcome() = runTest {
        coEvery { prefs.snapshot() } returns AppPrefs(onboardingDone = true, activeLocationId = "missing-id")
        val dao = mockk<com.charleshartman.porchlightpress.data.local.SavedLocationDao>(relaxed = true)
        coEvery { dao.byId("missing-id") } returns null
        val v = OnboardingViewModel(
            SavedStateHandle(), prefs, editionRepo, locationRepo, translationRepo,
            consentRepo, geo, feedApi, emptyList(), dao,
        )
        advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, v.state.value.step)
        assertNull(v.state.value.place)
    }

    @Test
    fun freshLaunchWithNullActiveLocationStaysAtWelcome() = runTest {
        coEvery { prefs.snapshot() } returns AppPrefs(onboardingDone = true, activeLocationId = null)
        val dao = mockk<com.charleshartman.porchlightpress.data.local.SavedLocationDao>(relaxed = true)
        val v = OnboardingViewModel(
            SavedStateHandle(), prefs, editionRepo, locationRepo, translationRepo,
            consentRepo, geo, feedApi, emptyList(), dao,
        )
        advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, v.state.value.step)
        assertNull(v.state.value.place)
    }

    @Test
    fun locationDaoIsRequiredNonOptional() = runTest {
        // Verify the production wiring (AppContainer passes a real DAO) still works:
        // a VM constructed with a real (mocked) dao resumes correctly, without a nullable fallback.
        coEvery { prefs.snapshot() } returns AppPrefs(onboardingDone = true, activeLocationId = "place:us:schenectady")
        val dao = mockk<com.charleshartman.porchlightpress.data.local.SavedLocationDao>(relaxed = true)
        coEvery { dao.byId(any()) } returns com.charleshartman.porchlightpress.data.local.SavedLocation(
            id = "place:us:schenectady", label = "Schenectady, NY", country = "US",
            admin1 = "US-NY", city = "Schenectady", tz = "America/New_York", isHome = true,
        )
        val v = OnboardingViewModel(
            SavedStateHandle(), prefs, editionRepo, locationRepo, translationRepo,
            consentRepo, geo, feedApi, emptyList(), dao,
        )
        advanceUntilIdle()
        assertEquals(OnboardingStep.DONE, v.state.value.step)
        coVerify { dao.byId("place:us:schenectady") }
    }
}
