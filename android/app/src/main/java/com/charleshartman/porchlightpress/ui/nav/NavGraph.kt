package com.charleshartman.porchlightpress.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.charleshartman.porchlightpress.ui.motion.PorchlightMotion
import com.charleshartman.porchlightpress.ui.motion.rememberReduceMotion
import com.charleshartman.porchlightpress.ui.section.SectionScreen
import com.charleshartman.porchlightpress.ui.section.SectionViewModel

object Routes {
    const val FRONT = "front"
    const val SECTION = "section/{sectionId}"
    const val ARTICLE = "article/{storyId}"
    const val SOURCE_INFO = "sourceInfo"
    const val ABOUT = "about"

    fun section(id: String) = "section/$id"
    fun article(id: String) = "article/$id"
}

@Composable
fun PorchlightNavGraph(
    container: AppContainer,
    gate: AdMobGate,
    interstitial: InterstitialController,
    onSwitchLocation: () -> Unit,
    onRequestLocationSwitchUi: () -> Unit = onSwitchLocation,
    navController: NavHostController = rememberNavController(),
) {
    val reduce = rememberReduceMotion()
    NavHost(
        navController = navController,
        startDestination = Routes.FRONT,
        enterTransition = {
            slideInHorizontally(tween(PorchlightMotion.slideMs(reduce))) { it / 5 } +
                fadeIn(tween(PorchlightMotion.fadeMs(reduce)))
        },
        exitTransition = {
            slideOutHorizontally(tween(PorchlightMotion.slideMs(reduce))) { -it / 8 } +
                fadeOut(tween(PorchlightMotion.fadeMs(reduce)))
        },
        popEnterTransition = {
            slideInHorizontally(tween(PorchlightMotion.slideMs(reduce))) { -it / 5 } +
                fadeIn(tween(PorchlightMotion.fadeMs(reduce)))
        },
        popExitTransition = {
            slideOutHorizontally(tween(PorchlightMotion.slideMs(reduce))) { it / 6 } +
                fadeOut(tween(PorchlightMotion.fadeMs(reduce)))
        },
    ) {
        composable(Routes.FRONT) {
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
    }
}
