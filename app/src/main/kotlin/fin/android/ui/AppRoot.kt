package fin.android.ui

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fin.android.data.AppState

object Routes {
    const val OVERVIEW = "overview"
    const val TX_ENTRY = "txentry"
    const val SETTINGS = "settings"
    const val ASSET = "asset/{assetId}"
    fun asset(assetId: String) = "asset/$assetId"
}

@Composable
fun AppRoot(vm: AppViewModel, activity: FragmentActivity) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val m = message
        if (m != null) {
            snackbar.showSnackbar(m)
            vm.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        when (val s = state) {
            is AppState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            is AppState.Onboarding -> OnboardingScreen(vm)
            is AppState.Locked -> UnlockScreen(vm, activity)
            is AppState.Ready -> ReadyNav(vm, s)
        }
        // padding from the Scaffold is consumed inside each screen's own Scaffold; the snackbar
        // host already accounts for insets.
        @Suppress("UNUSED_EXPRESSION") padding
    }
}

@Composable
private fun ReadyNav(vm: AppViewModel, ready: AppState.Ready) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = Routes.OVERVIEW,
        // Short, crisp directional slides (push forward / pop back).
        enterTransition = { slideIntoContainer(SlideDirection.Start, tween(170)) + fadeIn(tween(110)) },
        exitTransition = { slideOutOfContainer(SlideDirection.Start, tween(170)) + fadeOut(tween(110)) },
        popEnterTransition = { slideIntoContainer(SlideDirection.End, tween(170)) + fadeIn(tween(110)) },
        popExitTransition = { slideOutOfContainer(SlideDirection.End, tween(170)) + fadeOut(tween(110)) },
    ) {
        composable(Routes.OVERVIEW) {
            OverviewScreen(
                vm = vm,
                ready = ready,
                onAddTx = { nav.navigate(Routes.TX_ENTRY) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onAssetClick = { id -> nav.navigate(Routes.asset(id)) },
            )
        }
        composable(
            Routes.ASSET,
            arguments = listOf(navArgument("assetId") { type = NavType.StringType }),
        ) { entry ->
            val assetId = entry.arguments?.getString("assetId").orEmpty()
            AssetDetailScreen(
                vm = vm,
                assetId = assetId,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.TX_ENTRY) {
            TxEntryScreen(
                vm = vm,
                ready = ready,
                onDone = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                vm = vm,
                ready = ready,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
