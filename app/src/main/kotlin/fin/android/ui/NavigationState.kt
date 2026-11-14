package fin.android.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Only destination identity is saved here; ledger data stays in the repository. */
@Serializable
internal sealed interface Route : NavKey {
    @Serializable data object Portfolio : Route
    @Serializable data object Gains : Route
    @Serializable data object Settings : Route
    @Serializable data object Transaction : Route
    @Serializable data class Asset(val assetId: String) : Route
    @Serializable data object Accounts : Route
    @Serializable data object NewAccount : Route
    @Serializable data class EditAccount(val accountId: String) : Route
    @Serializable data object Assets : Route
    @Serializable data object NewAsset : Route
    @Serializable data class EditAsset(val assetId: String) : Route
}

@Serializable
internal enum class Section {
    PORTFOLIO, GAINS, SETTINGS;

    val route: Route
        get() = when (this) {
            PORTFOLIO -> Route.Portfolio
            GAINS -> Route.Gains
            SETTINGS -> Route.Settings
        }
}

/** Each tab retains its stack; system back leaves the app only from the Portfolio root. */
@Serializable
internal data class NavigationState(
    val selected: Section = Section.PORTFOLIO,
    val stacks: Map<Section, List<Route>> = Section.entries.associateWith { listOf(it.route) },
) {
    val current: Route get() = stacks.getValue(selected).last()
    val onTopLevel: Boolean get() = current == selected.route

    fun navigate(route: Route): NavigationState {
        val section = Section.entries.find { it.route == route }
        if (section != null) return copy(selected = section)
        if (route == current) return this
        return copy(stacks = stacks + (selected to (stacks.getValue(selected) + route)))
    }

    fun back(): NavigationState {
        val stack = stacks.getValue(selected)
        return if (stack.size > 1) copy(stacks = stacks + (selected to stack.dropLast(1)))
            else copy(selected = Section.PORTFOLIO)
    }
}
