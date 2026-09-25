package com.charleshartman.porchlightpress.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.data.ads.AdMobGate
import com.charleshartman.porchlightpress.data.ads.InterstitialController
import com.charleshartman.porchlightpress.ui.article.ArticleScreen
import com.charleshartman.porchlightpress.ui.article.ArticleViewModel
import com.charleshartman.porchlightpress.ui.frontpage.FrontPageScreen
import com.charleshartman.porchlightpress.ui.frontpage.FrontPageViewModel
import com.charleshartman.porchlightpress.ui.info.AboutScreen
import com.charleshartman.porchlightpress.ui.info.SourceInfoScreen
import com.charleshartman.porchlightpress.ui.section.SectionScreen
import com.charleshartman.porchlightpress.ui.section.SectionViewModel
import com.charleshartman.porchlightpress.ui.weather.AlertDetailScreen
import com.charleshartman.porchlightpress.ui.weather.WeatherScreen
import com.charleshartman.porchlightpress.ui.weather.WeatherViewModel

object Routes {
    const val FRONT = "front"
    const val SECTION = "section/{sectionId}"
    const val ARTICLE = "article/{storyId}"
    const val SOURCE_INFO = "sourceInfo"
    const val ABOUT = "about"
    const val WEATHER = "weather"
    const val ALERT_DETAIL = "alert/{alertId}"
    const val SAVED = "saved"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val PDF = "pdf/{fileName}"

    fun section(id: String) = "section/${android.net.Uri.encode(id)}"
    fun article(id: String) = "article/${android.net.Uri.encode(id)}"
    fun alertDetail(id: String) = "alert/${android.net.Uri.encode(id)}"
    fun pdf(name: String) = "pdf/${android.net.Uri.encode(name)}"
}

@Composable
fun PorchlightNavGraph(
    container: AppContainer,
    gate: AdMobGate,
    interstitial: InterstitialController,
    onSwitchLocation: () -> Unit,
    onRequestLocationSwitchUi: () -> Unit = onSwitchLocation,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.FRONT,
    /** Notification deep link: open this alert after entering Weather. */
    startAlertId: String? = null,
) {
    /** Notification deep link, consumed once (graph-level so popping back
     * from the detail to Weather can't re-fire it into a Back trap). */
    var pendingAlert by remember { mutableStateOf(startAlertId) }
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { container.optionalServices.screen(it.destination.route.orEmpty()) }
    }
    NavHost(navController = navController, startDestination = startDestination) {        composable(Routes.FRONT) {
            val factory = remember(container) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        FrontPageViewModel(container) as T
                }
            }
            val vm: FrontPageViewModel = viewModel(factory = factory)
            FrontPageScreen(
                container = container,
                viewModel = vm,
                gate = gate,
                onStoryClick = { id -> navController.navigate(Routes.article(id)) },
                onSectionClick = { sid -> navController.navigate(Routes.section(sid)) },
                onSwitchLocation = onRequestLocationSwitchUi,
                onOpenInfo = { navController.navigate(Routes.ABOUT) },
                onOpenWeather = { navController.navigate(Routes.WEATHER) },
                onAlertClick = { aid -> navController.navigate(Routes.alertDetail(aid)) },
                onOpenSaved = { navController.navigate(Routes.SAVED) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onViewPdf = { navController.navigate(Routes.pdf(it)) },
            )
        }
        composable(Routes.WEATHER) {
            val factory = remember(container) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        WeatherViewModel(container) as T
                }
            }
            val vm: WeatherViewModel = viewModel(factory = factory)
            // Notification deep link lands here first, then into the alert.
            // Consumed once at graph level (see above).
            LaunchedEffect(pendingAlert) {
                val target = pendingAlert
                if (target != null) {
                    pendingAlert = null
                    navController.navigate(Routes.alertDetail(target))
                }
            }
            WeatherScreen(
                container = container,
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onAlertClick = { aid -> navController.navigate(Routes.alertDetail(aid)) },
            )
        }
        composable(Routes.ALERT_DETAIL) { backStack ->
            val alertId = backStack.arguments?.getString("alertId") ?: ""
            AlertDetailScreen(
                container = container,
                alertId = alertId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SECTION) { backStack ->
            val sectionId = backStack.arguments?.getString("sectionId") ?: "local"
            val factory = remember(sectionId, container) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        SectionViewModel(container, sectionId) as T
                }
            }
            val vm: SectionViewModel = viewModel(factory = factory)
            SectionScreen(
                sectionId = sectionId,
                viewModel = vm,
                gate = gate,
                onStoryClick = { id -> navController.navigate(Routes.article(id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ARTICLE) { backStack ->
            val storyId = backStack.arguments?.getString("storyId") ?: ""
            val factory = remember(storyId, container) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        ArticleViewModel(container, storyId) as T
                }
            }
            val vm: ArticleViewModel = viewModel(factory = factory)
            val context = LocalContext.current
            val activity = context as? android.app.Activity
            ArticleScreen(
                viewModel = vm,
                onBack = {
                    val shouldShow = interstitial.onArticleReturn()
                    if (shouldShow && activity != null) {
                        val ad = gate.popInterstitial()
                        if (ad != null) {
                            try { ad.show(activity) } catch (e: Exception) { android.util.Log.w("Interstitial", "Failed to show ad", e) }
                        }
                    }
                    navController.popBackStack()
                },
            )
        }
        composable(Routes.SOURCE_INFO) {
            SourceInfoScreen(container = container, onBack = { navController.popBackStack() })
        }
        composable(Routes.ABOUT) {
            AboutScreen(container = container, onBack = { navController.popBackStack() })
        }
        composable(Routes.SAVED) {
            com.charleshartman.porchlightpress.ui.daily.SavedScreen(container,
                onBack = { navController.popBackStack() },
                onStoryClick = { navController.navigate(Routes.article(it)) },
                downloadedPapers = {
                    com.charleshartman.porchlightpress.ui.export.DownloadedPapersScreen(
                        onViewPdf = { navController.navigate(Routes.pdf(it)) })
                })
        }
        composable(Routes.SEARCH) {
            com.charleshartman.porchlightpress.ui.daily.SearchScreen(container,
                onBack = { navController.popBackStack() },
                onStoryClick = { navController.navigate(Routes.article(it)) })
        }
        composable(Routes.SETTINGS) {
            val activity = LocalContext.current as? android.app.Activity
            com.charleshartman.porchlightpress.ui.daily.SettingsScreen(container,
                onBack = { navController.popBackStack() },
                onAddLocation = onRequestLocationSwitchUi,
                onSources = { navController.navigate(Routes.SOURCE_INFO) },
                onAbout = { navController.navigate(Routes.ABOUT) },
                onPrivacyOptions = { activity?.let { container.consentRepository.showPrivacyOptions(it) } })
        }
        composable(Routes.PDF) { entry ->
            com.charleshartman.porchlightpress.ui.export.PdfReaderScreen(
                fileName = entry.arguments?.getString("fileName").orEmpty(),
                onBack = { navController.popBackStack() })
        }
    }
}
