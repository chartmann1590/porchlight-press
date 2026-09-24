package com.charleshartman.porchlightpress

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.charleshartman.porchlightpress.data.remote.NetworkModule
import com.charleshartman.porchlightpress.data.remote.TaxonomyDto
import com.charleshartman.porchlightpress.ui.home.HomeScreen
import com.charleshartman.porchlightpress.ui.onboarding.OnboardingRoute
import com.charleshartman.porchlightpress.ui.onboarding.OnboardingViewModel
import com.charleshartman.porchlightpress.ui.theme.PorchlightTheme

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as PorchlightApp).container

    private val onboardingVm: OnboardingViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val c = container
                return OnboardingViewModel(
                    savedStateHandle = SavedStateHandle(),
                    prefs = c.prefs,
                    editionRepo = c.editionRepository,
                    locationRepo = c.locationRepository,
                    translationRepo = c.translationRepository,
                    consentRepo = c.consentRepository,
                    geo = c.geoLookup,
                    feedApi = c.feedApi,
                    taxonomy = loadTaxonomy(),
                    locationDao = c.db.savedLocationDao(),
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            PorchlightTheme {
                val prefs by container.prefs.prefs.collectAsState(initial = null)
                val p = prefs
                if (p != null && p.onboardingDone && p.readingStarted) {
                    HomeScreen(container)
                } else {
                    // viewModels delegate is lazy; first touch must be after
                    // onCreate — remember{} keeps one instance per composition.
                    val vm = remember { onboardingVm }
                    OnboardingRoute(vm = vm, activity = this)
                }
            }
        }
    }

    private fun loadTaxonomy(): List<Pair<String, String>> {
        return try {
            val raw = assets.open("taxonomy.json").bufferedReader().readText()
            NetworkModule.feedJson.decodeFromString<TaxonomyDto>(raw)
                .categories.map { it.id to it.label }
        } catch (e: Exception) {
            listOf("local" to "Local")
        }
    }
}
