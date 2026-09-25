package com.charleshartman.porchlightpress.ui.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.data.weather.WeatherData
import com.charleshartman.porchlightpress.data.weather.WeatherRepository
import com.charleshartman.porchlightpress.domain.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class WeatherUiState(
    val isLoading: Boolean = true,
    val data: WeatherData? = null,
    /** Provider down: showing cached data with an "as of" time. */
    val stale: Boolean = false,
    val unavailable: Boolean = false,
    val place: Place? = null,
    val lang: String = "en",
)

class WeatherViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(WeatherUiState())
    val state: StateFlow<WeatherUiState> = _state

    init {
        viewModelScope.launch {
            container.prefs.prefs.collect { prefs ->
                load(prefs.activeLocationId, prefs.appLanguage)
            }
        }
    }

    fun refresh() {
        val placeId = _state.value.place?.id ?: return
        viewModelScope.launch { load(placeId, _state.value.lang) }
    }

    private suspend fun load(locationId: String?, lang: String) {
        if (locationId == null) {
            _state.value = WeatherUiState(isLoading = false, unavailable = true, lang = lang)
            return
        }
        _state.value = _state.value.copy(isLoading = true, lang = lang)
        try {
            val loc = container.db.savedLocationDao().byId(locationId)
            val place = loc?.let {
                Place(it.id, it.label, it.country, it.admin1, it.admin2, it.city, it.metro, it.lat, it.lon, it.tz)
            }
            if (place == null) {
                _state.value = WeatherUiState(isLoading = false, unavailable = true, lang = lang)
                return
            }
            when (val snap = container.weatherRepository.snapshot(place)) {
                is WeatherRepository.Snapshot.Ready ->
                    _state.value = WeatherUiState(
                        isLoading = false, data = snap.data, stale = snap.stale,
                        place = place, lang = lang,
                    )
                is WeatherRepository.Snapshot.Unavailable ->
                    _state.value = WeatherUiState(
                        isLoading = false, unavailable = true, place = place, lang = lang,
                    )
            }
        } catch (e: Exception) {
            android.util.Log.w("Porchlight", "Weather load failed", e)
            _state.value = _state.value.copy(isLoading = false, unavailable = true, lang = lang)
        }
    }
}
