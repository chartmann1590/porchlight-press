package com.charleshartman.porchlightpress.ui.article

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.data.local.Story
import com.charleshartman.porchlightpress.data.local.StorySource
import com.charleshartman.porchlightpress.data.local.StoryTranslation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ArticleUiState(
    val isLoading: Boolean = true,
    val story: Story? = null,
    val sources: List<StorySource> = emptyList(),
    val translation: StoryTranslation? = null,
    val showOriginal: Boolean = false,
    val isTranslating: Boolean = false,
    val error: String? = null,
)

class ArticleViewModel(
    private val container: AppContainer,
    private val storyId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ArticleUiState())
    val state: StateFlow<ArticleUiState> = _state

    init {
        viewModelScope.launch {
            container.prefs.prefs.collect { prefs ->
                load(storyId, prefs.appLanguage)
            }
        }
    }

    private suspend fun load(storyId: String, lang: String) {
        _state.value = ArticleUiState(isLoading = true)
        val story = container.db.storyDao().storyById(storyId)
        if (story == null) {
            _state.value = ArticleUiState(isLoading = false, error = "Story not found")
            return
        }
        val sources = container.db.storyDao().sourcesFor(storyId)
        val translation = if (lang != "en") container.db.translationDao().storyTranslation(storyId, story.version, lang) else null
        val isTranslating = lang != "en" && translation == null
        _state.value = ArticleUiState(isLoading = false, story = story, sources = sources, translation = translation, isTranslating = isTranslating)
        if (isTranslating) {
            viewModelScope.launch {
                try {
                    val result = container.translationRepository.translateStoryOnce(
                        storyId, story.version, story.headline, story.dek, story.body, lang,
                    )
                    _state.value = _state.value.copy(translation = result, isTranslating = false)
                } catch (e: Exception) {
                    android.util.Log.w("Porchlight", "Story translation failed", e)
                    _state.value = _state.value.copy(isTranslating = false)
                }
            }
        }
    }

    fun toggleOriginal() {
        val cur = _state.value
        _state.value = cur.copy(showOriginal = !cur.showOriginal)
    }
}
