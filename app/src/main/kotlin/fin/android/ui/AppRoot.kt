package fin.android.ui

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * Compact custom bottom bar (~56dp content + the system gesture inset). Built by hand rather than
 * Material 3's NavigationBar, whose fixed 80dp height clips the icons when forced shorter.
 */
@Composable
private fun CompactBottomBar(isSelected: (String) -> Boolean, onSelect: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(56.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevel.entries.forEach { item ->
                val tint = if (isSelected(item.route)) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(item.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(item.label, color = tint, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
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
                CompactBottomBar(
                    isSelected = { route -> backStack?.destination?.hierarchy?.any { it.route == route } == true },
                    onSelect = { route ->
                        nav.navigate(route) {
                            // Single top-level back stack: switching tabs doesn't grow it.
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
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
