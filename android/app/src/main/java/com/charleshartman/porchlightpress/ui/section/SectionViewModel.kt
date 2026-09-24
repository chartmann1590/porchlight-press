package com.charleshartman.porchlightpress.ui.section

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.data.local.Story
import com.charleshartman.porchlightpress.data.repo.TranslationRepository
import com.charleshartman.porchlightpress.domain.Place
import com.charleshartman.porchlightpress.ui.frontpage.FrontStoryUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SectionUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    val stories: List<FrontStoryUi> = emptyList(),
    val error: String? = null,
)

class SectionViewModel(
    private val container: AppContainer,
    private val sectionId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(SectionUiState())
    val state: StateFlow<SectionUiState> = _state

    init {
        viewModelScope.launch {
            container.prefs.prefs.collect { prefs ->
                load(sectionId, prefs.activeLocationId, prefs.appLanguage)
            }
        }
    }

    private suspend fun load(sectionId: String, locationId: String?, lang: String) {
        if (locationId == null) {
            _state.value = SectionUiState(isLoading = false, title = displayTitle(sectionId), error = "No location")
            return
        }
        _state.value = SectionUiState(isLoading = true, title = displayTitle(sectionId))
        val content = container.db.editionDao().editionFor(locationId, "latest")
            ?.let { container.editionRepository.cachedContent(locationId, "latest") }
        if (content == null) {
            _state.value = SectionUiState(isLoading = false, title = displayTitle(sectionId), error = "No stories")
            return
        }
        val allRaw = content.sections.flatMap { it.second }.distinctBy { it.id }
        val filtered = when (sectionId.lowercase()) {
            "local" -> allRaw.filter { it.category == "local" || it.locationsJson.contains("\"city\"") }
            "regional" -> allRaw.filter { it.category == "local" } // Until regional split is published, reuse local
            "state" -> allRaw.filter { it.category != "world" && it.category != "national" } // fallback
            "national" -> allRaw.filter { it.category == "national" || it.locationsJson.contains("\"US\"") }
            "world" -> allRaw.filter { it.category == "world" || it.locationsJson.contains("GB") || it.locationsJson.contains("world") }
            else -> allRaw.filter { it.category.equals(sectionId, true) }
        }
        // If filter yields empty, fall back to all (so section never blank in demo).
        val effective = if (filtered.isEmpty() && allRaw.isNotEmpty() && sectionId.lowercase() in setOf("local","regional","state","national","world")) {
            // For demo with single Top section feed, show all stories in each ladder section
            // rather than empty. Spec ladder is city→metro→state→national, publisher currently
            // writes a single Top section per feed; client-side ladder is the fallback.
            allRaw
        } else filtered

        val ui = effective.map { story ->
            val sources = container.db.storyDao().sourcesFor(story.id)
            val label = sources.firstOrNull()?.publisher
            val tr = if (lang != "en") container.db.translationDao().storyTranslation(story.id, story.version, lang) else null
            val isTranslating = lang != "en" && tr == null
            if (isTranslating) viewModelScope.launch {
                container.translationRepository.translateStory(story.id, story.version, story.headline, story.dek, story.body, lang)
            }
            FrontStoryUi(story, tr, isTranslating, label)
        }
        _state.value = SectionUiState(isLoading = false, title = displayTitle(sectionId), stories = ui)
    }

    private fun displayTitle(sectionId: String): String {
        return when (sectionId.lowercase()) {
            "local" -> "LOCAL"
            "regional" -> "REGIONAL"
            "state" -> "STATE"
            "national" -> "NATIONAL"
            "world" -> "WORLD"
            else -> sectionId.uppercase()
        }
    }
}
