package com.charleshartman.porchlightpress

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.charleshartman.porchlightpress.data.remote.NetworkModule
import com.charleshartman.porchlightpress.data.remote.TaxonomyDto
import com.charleshartman.porchlightpress.ui.nav.PorchlightNavGraph
import com.charleshartman.porchlightpress.ui.nav.Routes
import com.charleshartman.porchlightpress.ui.onboarding.OnboardingRoute
import com.charleshartman.porchlightpress.ui.onboarding.OnboardingViewModel
import com.charleshartman.porchlightpress.ui.theme.ClassicNewspaper
import com.charleshartman.porchlightpress.ui.theme.PorchlightTheme
import com.charleshartman.porchlightpress.ui.theme.currentLayout
import com.charleshartman.porchlightpress.work.AlertScheduler
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.safeDrawingPadding

class MainActivity : ComponentActivity() {

    companion object {
        /** Notification deep link: open the Weather screen on launch. */
        const val EXTRA_OPEN_WEATHER = "porchlight.open_weather"
        /** Notification deep link: then open this alert's detail. */
        const val EXTRA_ALERT_ID = "porchlight.alert_id"
    }

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
        container.optionalServices.refreshConfig()
        com.charleshartman.porchlightpress.work.EditionSyncScheduler.ensure(this)
        lifecycleScope.launch {
            container.optionalServices.ads.collect { config ->
                container.adGate.config = config
                container.interstitialController.config = config
                val consent = container.consentRepository.state.value
                container.adGate.initializeIfConsented(
                    consent is com.charleshartman.porchlightpress.data.repo.ConsentState.Obtained ||
                        consent is com.charleshartman.porchlightpress.data.repo.ConsentState.NotRequired,
                )
            }
        }
        // Severe-weather worker follows the notifySevere toggle (Phase 7).
        // Driven from the lifecycle (not composition) so it can't be skipped:
        // every emission reconciles the schedule with the persisted toggle.
        // POST_NOTIFICATIONS was requested at the moment the toggle was enabled.
        lifecycleScope.launch {
            container.prefs.prefs.collect { p ->
                container.optionalServices.applyConsent(p)
                android.util.Log.i(
                    "Porchlight",
                    "prefs severe=${p.notifySevere} breaking=${p.notifyBreaking} " +
                        "editions=${p.notifyEditions} lang=${p.appLanguage}",
                )
                AlertScheduler.ensure(this@MainActivity, p.notifySevere)
            }
        }
        setContent {
            val prefs by container.prefs.prefs.collectAsState(initial = null)
            val p = prefs
            val themePref = p?.theme ?: "classic"
            val layout = currentLayout(themePref, p?.textScale ?: 1.0)
            PorchlightTheme(themePref = themePref, layout = layout) {
                when {
                    p == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.testTag("main-loading"))
                    }
                    p.onboardingDone && p.readingStarted -> {
                        // Consent gating: UMP must resolve before MobileAds.initialize (Phase 9 ads).
                        // Initialize once when prefs show consent resolved or not required.
                        // The onboarding ViewModel already called requestConsent; we check again
                        // here to ensure ads initialize even on warm start.
                        val scope = rememberCoroutineScope()
                        val consent = remember { container.consentRepository.state }
                        val consentValue by consent.collectAsState(initial = com.charleshartman.porchlightpress.data.repo.ConsentState.Unknown)
                        // Consent state is in-memory only, so a fresh process
                        // starts Unknown even for long-onboarded users (which
                        // silently disabled all ads). Re-resolve on every
                        // launch: instant when already obtained, and the form
                        // appears only where the law requires it.
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(500)
                            runCatching { container.consentRepository.requestConsent(this@MainActivity) }
                        }
                        androidx.compose.runtime.LaunchedEffect(consentValue, p.analyticsConsent, p.crashConsent) {
                            if (consentValue is com.charleshartman.porchlightpress.data.repo.ConsentState.Obtained ||
                                consentValue is com.charleshartman.porchlightpress.data.repo.ConsentState.NotRequired
                            ) {
                                container.adGate.initializeIfConsented(true)
                            }
                        }
                        // Severe-weather scheduling is lifecycle-driven in
                        // onCreate (see above); no Compose effect needed here.
                        var showLocationSwitch by remember { mutableStateOf(false) }
                        val openWeather = intent.getBooleanExtra(EXTRA_OPEN_WEATHER, false)
                        val startAlertId = intent.getStringExtra(EXTRA_ALERT_ID)
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            PorchlightNavGraph(
                                container = container,
                                gate = container.adGate,
                                interstitial = container.interstitialController,
                                onSwitchLocation = { showLocationSwitch = true },
                                startDestination = if (openWeather) Routes.WEATHER else Routes.FRONT,
                                startAlertId = startAlertId,
                            )
                            if (showLocationSwitch) {
                                LocationSwitchDialog(
                                    container = container,
                                    onDismiss = { showLocationSwitch = false },
                                )
                            }
                        }
                    }
                    else -> {
                        val vm = remember { onboardingVm }
                        OnboardingRoute(vm = vm, activity = this)
                    }
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

@androidx.compose.runtime.Composable
private fun LocationSwitchDialog(container: AppContainer, onDismiss: () -> Unit) {
    val locations by androidx.compose.runtime.produceState(initialValue = emptyList<com.charleshartman.porchlightpress.data.local.SavedLocation>(), container) {
        value = container.db.savedLocationDao().all()
    }
    val scope = rememberCoroutineScope()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Switch location") },
        text = {
            androidx.compose.foundation.layout.Column {
                if (locations.isEmpty()) {
                    androidx.compose.material3.Text("No saved locations yet.")
                }
                locations.forEach { loc ->
                    androidx.compose.material3.TextButton(
                        onClick = {
                            scope.launch {
                                container.prefs.setActiveLocationId(loc.id)
                                onDismiss()
                            }
                        },
                        modifier = Modifier.testTag("switch-${loc.id}"),
                    ) { androidx.compose.material3.Text(loc.label) }
                }
                androidx.compose.material3.TextButton(
                    onClick = {
                        scope.launch {
                            container.prefs.setOnboardingDone(false)
                            container.prefs.setReadingStarted(false)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.testTag("switch-rerun-onboarding"),
                ) { androidx.compose.material3.Text("Add or change place…") }
                androidx.compose.material3.TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("switch-cancel"),
                ) { androidx.compose.material3.Text("Cancel") }
            }
        },
        confirmButton = {},
        modifier = Modifier.testTag("location-switch-dialog"),
    )
}
