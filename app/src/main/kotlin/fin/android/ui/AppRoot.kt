package fin.android.ui

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import fin.android.data.AppState
import kotlinx.serialization.json.Json

/** Top-level sections reachable from the bottom NavigationBar. */
private enum class TopLevel(val section: Section, val label: String, val icon: ImageVector) {
    Portfolio(Section.PORTFOLIO, "Portfolio", FinIcons.PieChart),
    Gains(Section.GAINS, "Gains", FinIcons.TrendingUp),
    Settings(Section.SETTINGS, "Settings", FinIcons.Settings),
}

/**
 * Compact custom bottom bar (~56dp content + the system gesture inset). Built by hand rather than
 * Material 3's NavigationBar, whose fixed 80dp height clips the icons when forced shorter.
 */
@Composable
private fun CompactBottomBar(isSelected: (Section) -> Boolean, onSelect: (Section) -> Unit) {
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
                val tint = if (isSelected(item.section)) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(item.section) },
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
    var nav by rememberSaveable(stateSaver = navigationSaver) { mutableStateOf(NavigationState()) }
    val navigate: (Route) -> Unit = { nav = nav.navigate(it) }
    val back = { nav = nav.back() }
    val provider = entryProvider<Route> {
        entry<Route.Portfolio> {
            PortfolioScreen(vm, ready, onAssetClick = { navigate(Route.Asset(it)) })
        }
        entry<Route.Gains> {
            GainsScreen(vm, ready, onAssetClick = { navigate(Route.Asset(it)) })
        }
        entry<Route.Settings> {
            SettingsScreen(vm, ready,
                onManageAccounts = { navigate(Route.Accounts) },
                onManageAssets = { navigate(Route.Assets) })
        }
        entry<Route.Accounts> {
            AccountListScreen(vm, ready, onAdd = { navigate(Route.NewAccount) },
                onEdit = { navigate(Route.EditAccount(it)) }, onBack = back)
        }
        entry<Route.NewAccount> { AccountEditorScreen(vm, ready, accountId = null, onDone = back) }
        entry<Route.EditAccount> { AccountEditorScreen(vm, ready, accountId = it.accountId, onDone = back) }
        entry<Route.Assets> {
            AssetListScreen(vm, ready, onAdd = { navigate(Route.NewAsset) },
                onEdit = { navigate(Route.EditAsset(it)) }, onBack = back)
        }
        entry<Route.NewAsset> { AssetEditorScreen(vm, ready, assetId = null, onDone = back) }
        entry<Route.EditAsset> { AssetEditorScreen(vm, ready, assetId = it.assetId, onDone = back) }
        entry<Route.Asset> { AssetDetailScreen(vm, assetId = it.assetId, onBack = back) }
        entry<Route.Transaction> { TxEntryScreen(vm, ready, onDone = back) }
    }
    // Decorate inactive stacks too: their saved screen state must outlive a tab switch.
    val entries = Section.entries.associateWith { section ->
        key(section) {
            rememberDecoratedNavEntries(
                backStack = nav.stacks.getValue(section),
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator<Route>()),
                entryProvider = provider,
            )
        }
    }
    val displayed = if (nav.selected == Section.PORTFOLIO) entries.getValue(Section.PORTFOLIO)
        else entries.getValue(Section.PORTFOLIO) + entries.getValue(nav.selected)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (nav.onTopLevel) {
                CompactBottomBar(
                    isSelected = { it == nav.selected },
                    onSelect = { navigate(it.route) },
                )
            }
        },
        floatingActionButton = {
            if (nav.onTopLevel) {
                FloatingActionButton(
                    onClick = { navigate(Route.Transaction) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(FinIcons.Add, contentDescription = "Add transaction")
                }
            }
        },
    ) { scaffoldPadding ->
        NavDisplay(
            entries = displayed,
            onBack = back,
            modifier = Modifier.padding(scaffoldPadding).fillMaxSize(),
            // Short, crisp directional slides (push forward / pop back).
            transitionSpec = {
                (slideIntoContainer(SlideDirection.Start, tween(170)) + fadeIn(tween(110))) togetherWith
                    (slideOutOfContainer(SlideDirection.Start, tween(170)) + fadeOut(tween(110)))
            },
            popTransitionSpec = {
                (slideIntoContainer(SlideDirection.End, tween(170)) + fadeIn(tween(110))) togetherWith
                    (slideOutOfContainer(SlideDirection.End, tween(170)) + fadeOut(tween(110)))
            },
            predictivePopTransitionSpec = {
                (slideIntoContainer(SlideDirection.End, tween(170)) + fadeIn(tween(110))) togetherWith
                    (slideOutOfContainer(SlideDirection.End, tween(170)) + fadeOut(tween(110)))
            },
        )
    }
}

// A concrete sealed serializer avoids reflective discovery of open NavKey implementations.
private val navigationSaver = Saver<NavigationState, String>(
    save = { Json.encodeToString(it) },
    restore = { runCatching { Json.decodeFromString<NavigationState>(it) }.getOrNull() },
)
