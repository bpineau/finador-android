package fin.android.ui

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fin.android.data.AppState

object Routes {
    const val PORTFOLIO = "portfolio"
    const val GAINS = "gains"
    const val SETTINGS = "settings"
    const val TX_ENTRY = "txentry"
    const val ASSET = "asset/{assetId}"
    fun asset(assetId: String) = "asset/$assetId"
}

/** Top-level sections reachable from the bottom NavigationBar. */
private enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    Portfolio(Routes.PORTFOLIO, "Portfolio", Icons.Filled.PieChart),
    Gains(Routes.GAINS, "Gains", Icons.AutoMirrored.Filled.TrendingUp),
    Settings(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
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

    when (val s = state) {
        is AppState.Loading -> Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is AppState.Onboarding -> Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Box(Modifier.padding(padding)) { OnboardingScreen(vm) }
        }
        is AppState.Locked -> Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Box(Modifier.padding(padding)) { UnlockScreen(vm, activity) }
        }
        is AppState.Ready -> ReadyNav(vm, s, snackbar)
    }
}

@Composable
private fun ReadyNav(vm: AppViewModel, ready: AppState.Ready, snackbar: SnackbarHostState) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // The bottom bar + FAB belong only to the three top-level sections; detail/entry push over them.
    val onTopLevel = TopLevel.entries.any { it.route == currentRoute }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (onTopLevel) {
                NavigationBar(modifier = Modifier.height(64.dp)) {
                    TopLevel.entries.forEach { item ->
                        val selected = backStack?.destination?.hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(item.route) {
                                    // Single top-level back stack: switching tabs doesn't grow it.
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = Color.Transparent,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (onTopLevel) {
                FloatingActionButton(
                    onClick = { nav.navigate(Routes.TX_ENTRY) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add transaction")
                }
            }
        },
    ) { scaffoldPadding ->
        NavHost(
            navController = nav,
            startDestination = Routes.PORTFOLIO,
            modifier = Modifier.padding(scaffoldPadding).fillMaxSize(),
            // Short, crisp directional slides (push forward / pop back).
            enterTransition = { slideIntoContainer(SlideDirection.Start, tween(170)) + fadeIn(tween(110)) },
            exitTransition = { slideOutOfContainer(SlideDirection.Start, tween(170)) + fadeOut(tween(110)) },
            popEnterTransition = { slideIntoContainer(SlideDirection.End, tween(170)) + fadeIn(tween(110)) },
            popExitTransition = { slideOutOfContainer(SlideDirection.End, tween(170)) + fadeOut(tween(110)) },
        ) {
            composable(Routes.PORTFOLIO) {
                PortfolioScreen(
                    vm = vm,
                    ready = ready,
                    onAssetClick = { id -> nav.navigate(Routes.asset(id)) },
                )
            }
            composable(Routes.GAINS) {
                GainsScreen(
                    vm = vm,
                    ready = ready,
                    onAssetClick = { id -> nav.navigate(Routes.asset(id)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(vm = vm, ready = ready)
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
        }
    }
}
