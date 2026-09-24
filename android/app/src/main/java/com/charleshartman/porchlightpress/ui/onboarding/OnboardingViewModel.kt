package com.charleshartman.porchlightpress.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.charleshartman.porchlightpress.data.local.PreferencesStore
import com.charleshartman.porchlightpress.data.local.SavedLocation
import com.charleshartman.porchlightpress.data.remote.FeedApi
import com.charleshartman.porchlightpress.data.remote.PlaceDto
import com.charleshartman.porchlightpress.data.repo.ConsentRepository
import com.charleshartman.porchlightpress.data.repo.ConsentState
import com.charleshartman.porchlightpress.data.repo.EditionRepository
import com.charleshartman.porchlightpress.data.repo.GeoLookup
import com.charleshartman.porchlightpress.data.repo.LocationRepository
import com.charleshartman.porchlightpress.data.repo.SyncSummary
import com.charleshartman.porchlightpress.data.repo.TranslationRepository
import com.charleshartman.porchlightpress.data.repo.ZipResolver
import com.charleshartman.porchlightpress.domain.EditionResolution
import com.charleshartman.porchlightpress.domain.FeedResult
import com.charleshartman.porchlightpress.domain.Place
import com.charleshartman.porchlightpress.domain.PlaceSerializable
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep {
    WELCOME, LANGUAGE, LOCATION, CONFIRM, INTERESTS, NOTIFICATIONS, PRIVACY, DONE,
}

enum class LocationOption { GPS, ZIP, MANUAL }

sealed interface SyncUiState {
    data object Idle : SyncUiState
    data object Loading : SyncUiState
    data class Loaded(val summary: SyncSummary) : SyncUiState
    data class Offline(val message: String) : SyncUiState
    data class Error(val message: String) : SyncUiState
    data object UpdateRequired : SyncUiState
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val language: String = "en",
    val supportedLanguages: List<String> = listOf("en"),
    val modelDownloading: Boolean = false,
    val modelNote: String? = null,
    val wifiOnly: Boolean = true,
    val locationOption: LocationOption? = null,
    val gpsFallback: Boolean = false,
    val gpsResolving: Boolean = false,
    val zipCountry: String = "US",
    val zipCode: String = "",
    val zipError: String? = null,
    val zipOptions: List<Place> = emptyList(),
    val manualCountry: String = "US",
    val manualAdmin1: String? = null,
    val manualAdmin2: String? = null,
    val manualCity: String? = null,
    val manualQuery: String = "",
    val countries: List<PlaceDto> = emptyList(),
    val admin1s: List<PlaceDto> = emptyList(),
    val counties: List<PlaceDto> = emptyList(),
    val cities: List<PlaceDto> = emptyList(),
    val place: Place? = null,
    val confirmSections: List<String> = emptyList(),
    /** null = unknown/offline, true = city feed exists, false = fallback coverage. */
    val localFeedAvailable: Boolean? = null,
    val interests: Set<String> = emptySet(),
    val taxonomy: List<Pair<String, String>> = emptyList(),
    val notifySevere: Boolean = false,
    val notifyBreaking: Boolean = false,
    val notifyEditions: Boolean = false,
    val analyticsConsent: Boolean = false,
    val crashConsent: Boolean = false,
    val consent: ConsentState = ConsentState.Unknown,
    val sync: SyncUiState = SyncUiState.Idle,
)

/**
 * One ViewModel for the full 8-step onboarding flow. Back works on every
 * step; every step after Language is skippable except Location; state
 * survives rotation/process death via SavedStateHandle.
 */
class OnboardingViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val prefs: PreferencesStore,
    private val editionRepo: EditionRepository,
    private val locationRepo: LocationRepository,
    private val translationRepo: TranslationRepository,
    private val consentRepo: ConsentRepository,
    private val geo: GeoLookup,
    private val feedApi: FeedApi,
    private val taxonomy: List<Pair<String, String>>,
    private val locationDao: com.charleshartman.porchlightpress.data.local.SavedLocationDao? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(taxonomy = taxonomy, supportedLanguages = supported()) }
        viewModelScope.launch {
            val p = prefs.snapshot()
            if (!savedStateHandle.contains("language")) {
                _state.update { it.copy(language = p.appLanguage.ifBlank { "en" }) }
            }
            if (!savedStateHandle.contains("step") && p.onboardingDone) {
                resumeAtDone(p.activeLocationId)
            }
        }
    }

    /** Fresh launch after reaching DONE (e.g. app was closed before Start
     * reading): jump back to DONE with the saved place instead of restarting. */
    private suspend fun resumeAtDone(activeLocationId: String?) {
        val saved = activeLocationId?.let { locationDao?.byId(it) } ?: return
        val place = Place(
            id = saved.id, label = saved.label, country = saved.country,
            admin1 = saved.admin1, admin2 = saved.admin2, city = saved.city,
            metro = saved.metro, lat = saved.lat, lon = saved.lon, tz = saved.tz,
        )
        _state.update {
            it.copy(
                place = place,
                step = OnboardingStep.DONE,
                confirmSections = sectionsFor(place),
            )
        }
        save()
        startFirstSync()
    }

    private fun supported(): List<String> =
        try {
            translationRepo.supportedLanguages().ifEmpty { listOf("en") }
        } catch (e: Exception) {
            listOf("en")
        }

    // -- Navigation --------------------------------------------------------

    fun onContinue() {
        val s = _state.value
        when (s.step) {
            OnboardingStep.WELCOME -> go(OnboardingStep.LANGUAGE)
            OnboardingStep.LANGUAGE -> go(OnboardingStep.LOCATION)
            OnboardingStep.LOCATION -> {
                // Location is required: only advance with a chosen place.
                if (s.place != null) enterConfirm() else _state.update { it.copy(gpsFallback = true) }
            }
            OnboardingStep.CONFIRM -> go(OnboardingStep.INTERESTS)
            OnboardingStep.INTERESTS -> {
                _state.update {
                    it.copy(notifySevere = it.place?.country == "US")
                }
                go(OnboardingStep.NOTIFICATIONS)
            }
            OnboardingStep.NOTIFICATIONS -> go(OnboardingStep.PRIVACY)
            OnboardingStep.PRIVACY -> { /* Consent button drives this transition. */ }
            OnboardingStep.DONE -> { /* Finish button drives this. */ }
        }
    }

    fun onBack(): Boolean {
        val order = OnboardingStep.entries
        val i = order.indexOf(_state.value.step)
        if (i <= 0) return false
        // Leaving CONFIRM returns to LOCATION; keep the chosen place.
        go(order[i - 1])
        return true
    }

    /** Skip where allowed: every step after Language except Location. */
    fun onSkip(): Boolean {
        return when (_state.value.step) {
            OnboardingStep.INTERESTS -> {
                _state.update { it.copy(interests = emptySet(), notifySevere = it.place?.country == "US") }
                go(OnboardingStep.NOTIFICATIONS)
                true
            }
            OnboardingStep.NOTIFICATIONS -> {
                _state.update { it.copy(notifySevere = false, notifyBreaking = false, notifyEditions = false) }
                go(OnboardingStep.PRIVACY)
                true
            }
            OnboardingStep.PRIVACY -> {
                persistConsentsAndFinish(consented = false)
                true
            }
            else -> false
        }
    }

    private fun go(step: OnboardingStep) {
        _state.update { it.copy(step = step) }
        save()
        if (step == OnboardingStep.LOCATION) loadPickerData()
        if (step == OnboardingStep.DONE) startFirstSync()
    }

    // -- Language ----------------------------------------------------------

    fun selectLanguage(tag: String) {
        _state.update { it.copy(language = tag, modelNote = null) }
        save()
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        _state.update { it.copy(wifiOnly = wifiOnly) }
        save()
    }

    /** Download the ML Kit model with visible progress; failure continues in English. */
    fun downloadModel() {
        val lang = _state.value.language
        if (lang == "en") return
        _state.update { it.copy(modelDownloading = true, modelNote = null) }
        viewModelScope.launch {
            val ok = translationRepo.ensureModel(lang, _state.value.wifiOnly)
            _state.update {
                it.copy(
                    modelDownloading = false,
                    modelNote = if (ok) "Downloaded ✓" else "Download failed — continuing in English; retrying in the background.",
                )
            }
        }
    }

    // -- Location ----------------------------------------------------------

    fun chooseOption(option: LocationOption) {
        _state.update { it.copy(locationOption = option, zipError = null, zipOptions = emptyList()) }
        save()
        if (option == LocationOption.MANUAL) loadPickerData()
    }

    fun onGpsResult(granted: Boolean, lat: Double?, lon: Double?) {
        if (!granted || lat == null || lon == null) {
            // Denied or timed out: fall through to ZIP/manual, no nagging.
            _state.update { it.copy(gpsFallback = true, gpsResolving = false) }
            save()
            return
        }
        _state.update { it.copy(gpsResolving = true) }
        viewModelScope.launch {
            val raw = geo.reverseGeocode(lat, lon)
            val enriched = raw?.let { enrich(it) }
            _state.update { it.copy(gpsResolving = false) }
            if (enriched != null) {
                _state.update { it.copy(place = enriched) }
                enterConfirm()
            } else {
                _state.update { it.copy(gpsFallback = true) }
            }
            save()
        }
    }

    fun onZipCountry(country: String) {
        _state.update { it.copy(zipCountry = country, zipError = null, zipOptions = emptyList()) }
        save()
    }

    fun onZipCode(code: String) {
        _state.update { it.copy(zipCode = code, zipError = null, zipOptions = emptyList()) }
        save()
    }

    fun submitZip() {
        val s = _state.value
        viewModelScope.launch {
            if (s.zipCountry == "US") {
                val code = ZipResolver.normalizeUsZip(s.zipCode)
                if (code == null) {
                    _state.update { it.copy(zipError = "Enter a 5-digit ZIP (ZIP+4 is fine).") }
                    return@launch
                }
                val entries = locationRepo.postalEntries("US")
                if (entries.isEmpty()) {
                    geocoderPostalFallback(s.zipCountry, s.zipCode.trim())
                    return@launch
                }
                when (val out = ZipResolver.resolveUs(code, entries)) {
                    is ZipResolver.Outcome.Resolved -> {
                        _state.update { it.copy(place = out.place) }
                        enterConfirm()
                    }
                    is ZipResolver.Outcome.Ambiguous -> {
                        _state.update { it.copy(zipOptions = out.options) }
                    }
                    is ZipResolver.Outcome.Invalid -> {
                        _state.update { it.copy(zipError = out.reason) }
                    }
                }
            } else {
                val code = ZipResolver.normalizePostal(s.zipCode)
                if (code == null) {
                    _state.update { it.copy(zipError = "Enter a postal code.") }
                    return@launch
                }
                val entries = locationRepo.postalEntries(s.zipCountry)
                if (entries.isEmpty()) {
                    geocoderPostalFallback(s.zipCountry, code)
                    return@launch
                }
                when (val out = ZipResolver.resolveGeneric(code, entries)) {
                    is ZipResolver.Outcome.Resolved -> {
                        _state.update { it.copy(place = out.place) }
                        enterConfirm()
                    }
                    is ZipResolver.Outcome.Ambiguous -> {
                        _state.update { it.copy(zipOptions = out.options) }
                    }
                    is ZipResolver.Outcome.Invalid -> {
                        _state.update { it.copy(zipError = out.reason) }
                    }
                }
            }
            save()
        }
    }

    private suspend fun geocoderPostalFallback(country: String, code: String) {
        val hits = geo.geocodePostal(country, code)
        when {
            hits.size == 1 -> {
                _state.update { it.copy(place = enrich(hits.first())) }
                enterConfirm()
            }
            hits.size > 1 -> _state.update { it.copy(zipOptions = hits) }
            else -> _state.update {
                it.copy(zipError = "We couldn't look that up offline. Check it, or pick manually instead.")
            }
        }
    }

    fun chooseZipOption(place: Place) {
        viewModelScope.launch {
            _state.update { it.copy(place = enrich(place), zipOptions = emptyList()) }
            enterConfirm()
            save()
        }
    }

    // -- Manual pickers ----------------------------------------------------

    private var allPlaces: List<PlaceDto> = emptyList()

    private fun loadPickerData() {
        viewModelScope.launch {
            val country = _state.value.manualCountry
            allPlaces = locationRepo.places(country)
            refreshPickerLists()
        }
    }

    private fun refreshPickerLists() {
        val s = _state.value
        _state.update {
            it.copy(
                countries = LocationRepository.countries(allPlaces).ifEmpty { defaultCountries() },
                admin1s = LocationRepository.admin1s(allPlaces, s.manualCountry),
                counties = LocationRepository.counties(allPlaces, s.manualAdmin1),
                cities = LocationRepository.cities(allPlaces, s.manualAdmin2, s.manualQuery),
            )
        }
    }

    private fun defaultCountries(): List<PlaceDto> =
        listOf(PlaceDto(name = "United States", type = "country", country = "US"))

    fun setManualCountry(country: String) {
        _state.update {
            it.copy(
                manualCountry = country, manualAdmin1 = null, manualAdmin2 = null,
                manualCity = null, manualQuery = "",
            )
        }
        save()
        loadPickerData()
    }

    fun setManualAdmin1(admin1: String?) {
        _state.update { it.copy(manualAdmin1 = admin1, manualAdmin2 = null, manualCity = null) }
        save()
        refreshPickerLists()
    }

    fun setManualAdmin2(admin2: String?) {
        _state.update { it.copy(manualAdmin2 = admin2, manualCity = null) }
        save()
        refreshPickerLists()
    }

    fun setManualQuery(query: String) {
        _state.update { it.copy(manualQuery = query) }
        refreshPickerLists()
    }

    fun setManualCity(city: PlaceDto) {
        viewModelScope.launch {
            _state.update { it.copy(place = enrich(LocationRepository.placeToFollowed(city))) }
            enterConfirm()
            save()
        }
    }

    // -- Confirm -----------------------------------------------------------

    private fun enterConfirm() {
        val place = _state.value.place ?: return
        _state.update {
            it.copy(
                step = OnboardingStep.CONFIRM,
                confirmSections = sectionsFor(place),
                localFeedAvailable = null,
            )
        }
        save()
        viewModelScope.launch {
            val cityPath = EditionResolution.candidateFeedPaths(place).firstOrNull()
            val available = cityPath?.let {
                try {
                    editionRepo.probeFeed(it, feedApi)
                } catch (e: Exception) {
                    null
                }
            }
            _state.update { it.copy(localFeedAvailable = available) }
            save()
        }
    }

    private fun sectionsFor(place: Place): List<String> {
        val regional = place.metro?.let { "Regional" }
            ?: place.admin2?.let { "Regional ($it)" }
            ?: "Regional"
        val state = place.admin1?.substringAfter("-") ?: place.country
        return listOf(
            "Local — ${place.city ?: place.label}",
            regional,
            state,
            if (place.country == "US") "United States" else "World",
        )
    }

    // -- Interests / notifications / privacy --------------------------------

    fun toggleInterest(id: String) {
        _state.update {
            val next = it.interests.toMutableSet().apply {
                if (!add(id)) remove(id)
            }
            it.copy(interests = next)
        }
        save()
    }

    fun setNotifySevere(on: Boolean) {
        _state.update { it.copy(notifySevere = on) }
        save()
    }

    fun setNotifyBreaking(on: Boolean) {
        _state.update { it.copy(notifyBreaking = on) }
        save()
    }

    fun setNotifyEditions(on: Boolean) {
        _state.update { it.copy(notifyEditions = on) }
        save()
    }

    fun setAnalyticsConsent(on: Boolean) {
        _state.update { it.copy(analyticsConsent = on) }
        save()
    }

    fun setCrashConsent(on: Boolean) {
        _state.update { it.copy(crashConsent = on) }
        save()
    }

    /** Privacy Continue: UMP consent form if Google requires it, then finish. */
    fun acceptPrivacyAndContinue(activity: android.app.Activity) {
        viewModelScope.launch {
            val result = consentRepo.requestConsent(activity)
            _state.update { it.copy(consent = result) }
            persistConsentsAndFinish(consented = true)
        }
    }

    private fun persistConsentsAndFinish(consented: Boolean) {
        val s = _state.value
        viewModelScope.launch {
            prefs.setAnalyticsConsent(if (consented) s.analyticsConsent else false)
            prefs.setCrashConsent(if (consented) s.crashConsent else false)
            prefs.setAppLanguage(s.language)
            prefs.setInterests(s.interests)
            prefs.setNotifySevere(s.notifySevere)
            prefs.setNotifyBreaking(s.notifyBreaking)
            prefs.setNotifyEditions(s.notifyEditions)
            persistPlace(s.place)
            prefs.setOnboardingDone(true)
            prefs.setReadingStarted(false)
            _state.update { it.copy(step = OnboardingStep.DONE) }
            save()
            startFirstSync()
        }
    }

    private suspend fun persistPlace(place: Place?) {
        if (place == null) return
        val id = place.id.ifBlank { "place-${UUID.randomUUID()}" }
        val fresh = place.copy(id = id)
        locationDao?.upsert(
            SavedLocation(
                id = fresh.id,
                label = fresh.label,
                country = fresh.country,
                admin1 = fresh.admin1,
                admin2 = fresh.admin2,
                city = fresh.city,
                metro = fresh.metro,
                lat = fresh.lat,
                lon = fresh.lon,
                tz = fresh.tz,
                isHome = true,
                sortOrder = 0,
            ),
        )
        prefs.setActiveLocationId(id)
        _state.update { it.copy(place = fresh) }
    }

    /** Re-run from Settings → About without losing saved stories. */
    fun rerunSetup() {
        viewModelScope.launch {
            prefs.setOnboardingDone(false)
            prefs.setReadingStarted(false)
        }
        _state.update { it.copy(step = OnboardingStep.WELCOME, sync = SyncUiState.Idle) }
        save()
    }

    /** DONE → "Start reading": route home (MainActivity reads this flag). */
    fun markReading() {
        viewModelScope.launch { prefs.setReadingStarted(true) }
    }

    // -- Done: first edition fetch ------------------------------------------

    fun startFirstSync() {
        val place = _state.value.place ?: run {
            _state.update { it.copy(sync = SyncUiState.Error("Pick a location first.")) }
            return
        }
        _state.update { it.copy(sync = SyncUiState.Loading) }
        viewModelScope.launch {
            when (val r = editionRepo.sync(place, "latest", feedApi)) {
                is FeedResult.Ok -> _state.update { it.copy(sync = SyncUiState.Loaded(r.value)) }
                is FeedResult.Offline -> _state.update {
                    it.copy(
                        sync = SyncUiState.Offline(
                            r.cached?.let { "Offline · showing saved edition." }
                                ?: "You're offline. We'll fetch your paper when you're back online.",
                        ),
                    )
                }
                is FeedResult.UpdateRequired -> _state.update { it.copy(sync = SyncUiState.UpdateRequired) }
                is FeedResult.Error -> _state.update {
                    it.copy(sync = SyncUiState.Error(r.message))
                }
            }
            save()
        }
    }

    fun retrySync() = startFirstSync()

    // -- Enrichment ----------------------------------------------------------

    /** Fill admin1/metro/tz from the downloaded gazetteer by city match. */
    suspend fun enrich(place: Place): Place {
        if (place.admin1 != null && place.tz != null) return place
        val places = try {
            locationRepo.places(place.country)
        } catch (e: Exception) {
            emptyList()
        }
        val match = places.firstOrNull { p ->
            p.city?.equals(place.city, true) == true ||
                p.aliases.any { it.equals(place.city, true) }
        } ?: return place
        return place.copy(
            admin1 = place.admin1 ?: match.admin1,
            admin2 = place.admin2 ?: match.admin2,
            metro = place.metro ?: match.metro,
            tz = place.tz ?: match.timezone,
        )
    }

    // -- SavedState ----------------------------------------------------------

    private fun save() {
        val s = _state.value
        savedStateHandle["step"] = s.step.name
        savedStateHandle["language"] = s.language
        savedStateHandle["wifiOnly"] = s.wifiOnly
        savedStateHandle["locationOption"] = s.locationOption?.name
        savedStateHandle["zipCountry"] = s.zipCountry
        savedStateHandle["zipCode"] = s.zipCode
        savedStateHandle["manualCountry"] = s.manualCountry
        savedStateHandle["manualAdmin1"] = s.manualAdmin1
        savedStateHandle["manualAdmin2"] = s.manualAdmin2
        savedStateHandle["manualCity"] = s.manualCity
        savedStateHandle["manualQuery"] = s.manualQuery
        savedStateHandle["interests"] = ArrayList(s.interests)
        savedStateHandle["notifySevere"] = s.notifySevere
        savedStateHandle["notifyBreaking"] = s.notifyBreaking
        savedStateHandle["notifyEditions"] = s.notifyEditions
        savedStateHandle["analyticsConsent"] = s.analyticsConsent
        savedStateHandle["crashConsent"] = s.crashConsent
        savedStateHandle["place"] = s.place?.let { PlaceSerializable.from(it) }
    }

    private fun restore(): OnboardingUiState {
        fun <T> get(key: String): T? = savedStateHandle[key]
        val step = runCatching { OnboardingStep.valueOf(get<String>("step") ?: "WELCOME") }
            .getOrDefault(OnboardingStep.WELCOME)
        return OnboardingUiState(
            step = step,
            language = get<String>("language") ?: "en",
            wifiOnly = get<Boolean>("wifiOnly") ?: true,
            locationOption = get<String>("locationOption")?.let { runCatching { LocationOption.valueOf(it) }.getOrNull() },
            zipCountry = get<String>("zipCountry") ?: "US",
            zipCode = get<String>("zipCode") ?: "",
            manualCountry = get<String>("manualCountry") ?: "US",
            manualAdmin1 = get<String>("manualAdmin1"),
            manualAdmin2 = get<String>("manualAdmin2"),
            manualCity = get<String>("manualCity"),
            manualQuery = get<String>("manualQuery") ?: "",
            interests = get<ArrayList<String>>("interests")?.toSet() ?: emptySet(),
            notifySevere = get<Boolean>("notifySevere") ?: false,
            notifyBreaking = get<Boolean>("notifyBreaking") ?: false,
            notifyEditions = get<Boolean>("notifyEditions") ?: false,
            analyticsConsent = get<Boolean>("analyticsConsent") ?: false,
            crashConsent = get<Boolean>("crashConsent") ?: false,
            place = get<PlaceSerializable>("place")?.toPlace(),
        )
    }
}
