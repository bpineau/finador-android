package fin.android.ui

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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import fin.android.data.AppState

object Routes {
    const val OVERVIEW = "overview"
    const val TX_ENTRY = "txentry"
    const val SETTINGS = "settings"
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
    NavHost(navController = nav, startDestination = Routes.OVERVIEW) {
        composable(Routes.OVERVIEW) {
            OverviewScreen(
                vm = vm,
                ready = ready,
                onAddTx = { nav.navigate(Routes.TX_ENTRY) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
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
