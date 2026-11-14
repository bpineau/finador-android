package fin.android.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationStateTest {
    @Test fun switchingTabsDoesNotGrowStacksAndBackReturnsToPortfolio() {
        val state = NavigationState().navigate(Route.Gains).navigate(Route.Settings).navigate(Route.Settings)
        assertEquals(Section.SETTINGS, state.selected)
        assertTrue(state.onTopLevel)
        assertTrue(state.stacks.values.all { it.size == 1 })
        assertEquals(Route.Portfolio, state.back().current)
        assertEquals(NavigationState(), NavigationState().back())
    }

    @Test fun detailsPopToTheirOriginAndRepeatedTapDoesNotDuplicateDestination() {
        val state = NavigationState().navigate(Route.Gains)
            .navigate(Route.Asset("A1")).navigate(Route.Asset("A1"))
        assertFalse(state.onTopLevel)
        assertEquals(2, state.stacks.getValue(Section.GAINS).size)
        assertEquals(Route.Gains, state.back().current)
        assertEquals(Route.Portfolio, state.back().back().current)
    }

    @Test fun tabSwitchingRetainsInactiveNavigationState() {
        val state = NavigationState().navigate(Route.Settings).navigate(Route.Accounts)
            .navigate(Route.EditAccount("C1")).navigate(Route.Portfolio).navigate(Route.Settings)
        assertEquals(Route.EditAccount("C1"), state.current)
        assertEquals(Route.Accounts, state.back().current)
    }

    @Test fun serializationRestoresTabAndEveryDestinationType() {
        val routes = listOf(
            Route.Transaction, Route.Asset("A1"), Route.Accounts, Route.NewAccount,
            Route.EditAccount("C1"), Route.Assets, Route.NewAsset, Route.EditAsset("A2"),
        )
        val state = routes.fold(NavigationState().navigate(Route.Settings)) { nav, route -> nav.navigate(route) }
            .navigate(Route.Gains)
        val restored = Json.decodeFromString<NavigationState>(Json.encodeToString(state))
        assertEquals(state, restored)
        assertEquals(Route.Gains, restored.current)
        assertEquals(routes, restored.stacks.getValue(Section.SETTINGS).drop(1))
    }
}
