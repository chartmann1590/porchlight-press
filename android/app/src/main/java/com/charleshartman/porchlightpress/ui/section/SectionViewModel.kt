package com.charleshartman.porchlightpress.ui.section

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.domain.Place
import com.charleshartman.porchlightpress.domain.FeedResult
import com.charleshartman.porchlightpress.ui.frontpage.FrontStoryUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class SectionUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    val stories: List<FrontStoryUi> = emptyList(),
    val error: String? = null,
)

class SectionViewModel(private val container: AppContainer, private val sectionId: String) : ViewModel() {
    private val _state = MutableStateFlow(SectionUiState())
    val state: StateFlow<SectionUiState> = _state
    init {
        viewModelScope.launch {
            container.prefs.prefs.collectLatest { prefs -> load(prefs.activeLocationId, prefs.appLanguage) }
        }
    }
    fun refresh() {
        viewModelScope.launch { val p = container.prefs.snapshot(); load(p.activeLocationId, p.appLanguage) }
    }
    private suspend fun load(locationId: String?, lang: String) {
        _state.value = SectionUiState(title = sectionId.uppercase())
        try {
            val saved = locationId?.let { container.db.savedLocationDao().byId(it) }
            if (saved == null) {
                _state.value = SectionUiState(false, sectionId.uppercase(), error = "Choose a location to read this section.")
                return
            }
            val place = Place(saved.id, saved.label, saved.country, saved.admin1, saved.admin2, saved.city, saved.metro, saved.lat, saved.lon, saved.tz)
            val geographic = sectionId in setOf("local", "regional", "state", "national", "world")
            val kind = if (geographic) "section-$sectionId" else "latest"
            var notice: String? = null
            if (geographic) {
                val scope = when (sectionId) {
                    "regional" -> place.copy(city = null, admin2 = null)
                    "state" -> place.copy(city = null, admin2 = null, metro = null)
                    "national" -> place.copy(city = null, admin1 = null, admin2 = null, metro = null)
                    "world" -> place.copy(country = "WORLD", city = null, admin1 = null, admin2 = null, metro = null)
                    else -> place
                }
                notice = when (val result = container.editionRepository.sync(place, api = container.feedApi, resolutionPlace = scope, storageKind = kind)) {
                    is FeedResult.Ok -> if (result.value.fellBack) "No dedicated $sectionId edition yet. Showing ${result.value.locationLabel}." else null
                    is FeedResult.Offline -> "Offline — showing downloaded stories."
                    is FeedResult.Error -> result.message
                    is FeedResult.UpdateRequired -> result.message
                }
            }
            val content = container.editionRepository.cachedContent(place.id, kind)
                ?: if (sectionId == "local") container.editionRepository.cachedContent(place.id) else null
            val interests = container.prefs.snapshot().interests
            val stories = content?.sections.orEmpty().flatMap { it.second }.distinctBy { it.id }
                .filter { geographic || it.category.equals(sectionId, true) }
                .sortedByDescending { it.category in interests }
            val ui = stories.map { story ->
                val tr = if (lang != "en") container.db.translationDao().storyTranslation(story.id, story.version, lang) else null
                FrontStoryUi(story, tr, lang != "en" && tr == null, container.db.storyDao().sourcesFor(story.id).firstOrNull()?.publisher)
            }
            _state.value = SectionUiState(false, sectionId.uppercase(), ui, notice)
            for (item in ui.filter { it.isTranslating }) {
                val s = item.story
                val translated = try { container.translationRepository.translateStoryOnce(s.id, s.version, s.headline, s.dek, s.body, lang) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                _state.value = _state.value.copy(stories = _state.value.stories.map {
                    if (it.story.id == s.id) it.copy(translation = translated, isTranslating = false) else it
                })
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.value = _state.value.copy(isLoading = false, error = "Couldn't load this section. Pull down to retry.") }
    }
}
