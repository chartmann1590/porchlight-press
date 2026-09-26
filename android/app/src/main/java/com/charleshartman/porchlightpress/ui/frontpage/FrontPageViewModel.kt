package com.charleshartman.porchlightpress.ui.frontpage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.data.local.Story
import com.charleshartman.porchlightpress.data.local.StoryTranslation
import com.charleshartman.porchlightpress.data.repo.EditionRepository
import com.charleshartman.porchlightpress.data.repo.TranslationRepository
import com.charleshartman.porchlightpress.data.weather.WeatherRepository
import com.charleshartman.porchlightpress.domain.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest

data class FrontStoryUi(
    val story: Story,
    val translation: StoryTranslation? = null,
    val isTranslating: Boolean = false,
    val sourceLabel: String? = null,
)

data class SectionUi(
    val id: String,
    val title: String,
    val stories: List<FrontStoryUi>,
)

data class FrontPageUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val place: Place? = null,
    val editionKind: String? = null,
    val generatedAt: String? = null,
    val sections: List<SectionUi> = emptyList(),
    val allStories: List<FrontStoryUi> = emptyList(),
    val offline: Boolean = false,
    /** Null until the first weather load finishes; never blocks the news. */
    val weather: WeatherRepository.Snapshot? = null,
)

class FrontPageViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val _state = MutableStateFlow(FrontPageUiState())
    val state: StateFlow<FrontPageUiState> = _state

    init {
        viewModelScope.launch {
            container.prefs.prefs.collectLatest { prefs ->
                val locationId = prefs.activeLocationId
                if (locationId == null) {
                    _state.value = FrontPageUiState(isLoading = false, error = "Pick a location to see your paper.")
                    return@collectLatest
                }
                container.db.editionDao().observeEditionFor(locationId, "latest").collectLatest {
                    load(locationId, prefs.appLanguage)
                }
            }
        }
    }

    fun refresh() {
        val id = _state.value.place?.id ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true, error = null)
            val place = container.db.savedLocationDao().byId(id)?.let {
                Place(it.id, it.label, it.country, it.admin1, it.admin2, it.city, it.metro, it.lat, it.lon, it.tz)
            } ?: _state.value.place ?: return@launch
            val result = container.editionRepository.sync(place, "latest", container.feedApi)
            when (result) {
                is com.charleshartman.porchlightpress.domain.FeedResult.Ok -> {
                    _state.value = _state.value.copy(offline = false)
                    load(id, container.prefs.snapshot().appLanguage)
                }
                is com.charleshartman.porchlightpress.domain.FeedResult.Offline -> {
                    _state.value = _state.value.copy(isRefreshing = false, offline = true)
                    load(id, container.prefs.snapshot().appLanguage)
                }
                is com.charleshartman.porchlightpress.domain.FeedResult.Error -> {
                    _state.value = _state.value.copy(isRefreshing = false, error = result.message)
                }
                else -> _state.value = _state.value.copy(isRefreshing = false)
            }
        }
    }

    private suspend fun load(locationId: String, lang: String) {
        try {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val locRow = container.db.savedLocationDao().byId(locationId)
            val place = locRow?.let {
                Place(it.id, it.label, it.country, it.admin1, it.admin2, it.city, it.metro, it.lat, it.lon, it.tz)
            }
            val content = container.editionRepository.cachedContent(locationId, "latest")
            if (content == null) {
                _state.value = _state.value.copy(isLoading = false, place = place, error = "Fetching your paper…")
                // Try sync once if empty
                if (place != null) {
                    val r = container.editionRepository.sync(place, "latest", container.feedApi)
                    if (r is com.charleshartman.porchlightpress.domain.FeedResult.Ok) {
                        load(locationId, lang)
                    } else {
                        _state.value = _state.value.copy(isLoading = false, place = place)
                    }
                }
                return
            }
            val edition = content.edition
            // Build lookup for sources per story
            val allStoriesRaw = content.sections.flatMap { it.second }.distinctBy { it.id }
            val uiStories = allStoriesRaw.map { story ->
                val sources = container.db.storyDao().sourcesFor(story.id)
                val label = sources.firstOrNull()?.publisher
                val translation = if (lang != "en") {
                    container.db.translationDao().storyTranslation(story.id, story.version, lang)
                } else null
                val isTranslating = lang != "en" && translation == null
                FrontStoryUi(story, translation, isTranslating, label)
            }
            // Build sections: respect feed sections if >1, else synthesize from categories/places.
            val interests = container.prefs.snapshot().interests
            val sections = buildSections(content, uiStories, place).map { section ->
                section.copy(stories = section.stories.sortedByDescending { it.story.category in interests })
            }
            _state.value = FrontPageUiState(
                isLoading = false,
                isRefreshing = false,
                place = place,
                editionKind = edition.kind,
                generatedAt = edition.generatedAt,
                sections = sections,
                allStories = uiStories,
                offline = !hasConnection(),
                weather = _state.value.weather.takeIf { _state.value.place?.id == locationId },
            )
            // Weather loads alongside (never blocking the news); cached "as
            // of" data shows when the provider is down.
            place?.let { p ->
                viewModelScope.launch {
                    val snap = runCatching { container.weatherRepository.snapshot(p) }.getOrNull()
                    if (snap != null && _state.value.place?.id == p.id) _state.value = _state.value.copy(weather = snap)
                }
            }
            // Kick off background translation for stories that need it, after
            // state is set. Success swaps the translation in place; failure
            // clears the flag so no permanent "translating…" chip lingers.
            if (lang != "en") {
                for (story in allStoriesRaw) {
                    if (uiStories.find { it.story.id == story.id }?.isTranslating != true) continue
                    viewModelScope.launch {
                        try {
                            val result = container.translationRepository.translateStoryOnce(
                                story.id, story.version, story.headline, story.dek, story.body, lang,
                            )
                            if (container.prefs.snapshot().appLanguage == lang && _state.value.place?.id == locationId)
                                patchTranslation(story.id, result, false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.w("Porchlight", "Story translation failed", e)
                            patchTranslation(story.id, null, false)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Couldn't load your paper.")
        }
    }

    private fun hasConnection(): Boolean {
        val manager = container.context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** Patch one story's translation into both lists the UI renders from. */
    private fun patchTranslation(
        storyId: String,
        translation: com.charleshartman.porchlightpress.data.local.StoryTranslation?,
        isTranslating: Boolean,
    ) {
        _state.value = _state.value.copy(
            allStories = _state.value.allStories.map { s ->
                if (s.story.id == storyId) s.copy(translation = translation, isTranslating = isTranslating) else s
            },
            sections = _state.value.sections.map { sec ->
                sec.copy(stories = sec.stories.map { s ->
                    if (s.story.id == storyId) s.copy(translation = translation, isTranslating = isTranslating) else s
                })
            },
        )
    }

    private fun buildSections(
        content: com.charleshartman.porchlightpress.data.repo.EditionContent,
        uiStories: List<FrontStoryUi>,
        place: Place?,
    ): List<SectionUi> {
        // If publisher emitted multiple sections, honour them.
        if (content.sections.size > 1) {
            return content.sections.map { (sec, stories) ->
                val storiesUi = stories.mapNotNull { s -> uiStories.find { it.story.id == s.id } }
                SectionUi(sec.sectionId, sec.title, storiesUi)
            }.filter { it.stories.isNotEmpty() }
        }
        // Single "Top Stories" section: synthesize newspaper ordering
        // Top hero (first story) then Local/Regional/State/National/World grouping.
        // Simplest: group by story.category and by location presence.
        val rawSections = content.sections.firstOrNull()
        val sectionTitle = rawSections?.first?.title ?: "Top Stories"
        if (uiStories.isEmpty()) return emptyList()

        // Hero is first story; remaining grouped by category.
        val top = SectionUi("top", sectionTitle, uiStories)
        // Additionally expose category sections for those the user follows (spec:
        // front page lists Local → Regional → State → National → World → Weather
        // then optional categories the user picked). We keep Top Stories as the
        // primary section and expose category slices as sub-sections when present.
        // Simplest: return just Top for now; the UI will render hero + list.
        // Category sub-sections will be available via SectionScreen.
        return listOf(top)
    }
}
