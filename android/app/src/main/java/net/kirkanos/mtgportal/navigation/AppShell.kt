package net.kirkanos.mtgportal.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.data.AuthState
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import androidx.navigation.toRoute
import net.kirkanos.mtgportal.feature.binders.BindersScreen
import net.kirkanos.mtgportal.feature.cards.CardsScreen
import net.kirkanos.mtgportal.feature.decks.DecksScreen
import net.kirkanos.mtgportal.feature.reserved.ReservedScreen
import net.kirkanos.mtgportal.feature.settings.SettingsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell() {
    val container = appContainer()
    val nav = rememberNavController()
    val config by container.config.config.collectAsStateWithLifecycle()
    val auth by container.auth.state.collectAsStateWithLifecycle()
    var moreOpen by remember { mutableStateOf(false) }

    val visible = NavItem.entries.filter { it.navKey !in config.hiddenNav }
    val unlocked = auth == AuthState.Open || auth == AuthState.Unlocked

    Scaffold(
        bottomBar = {
            NavigationBar {
                val entry by nav.currentBackStackEntryAsState()
                val dest = entry?.destination
                visible.filter { it.primary }.forEach { item ->
                    NavigationBarItem(
                        selected = dest?.hasRoute(item.route::class) == true,
                        onClick = { nav.navigateTop(item.route) },
                        icon = { Text(item.icon) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
                NavigationBarItem(
                    selected = false,
                    onClick = { moreOpen = true },
                    icon = { Text("☰") },
                    label = { Text(stringResource(R.string.nav_more)) },
                )
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = CardsRoute(), modifier = Modifier.padding(padding)) {
            composable<CardsRoute> { entry ->
                val r = entry.toRoute<CardsRoute>()
                CardsScreen(binder = r.binder, set = r.set)
            }
            composable<EditionsRoute> { NotYet(R.string.nav_editions) }
            composable<ReservedRoute> { ReservedScreen() }
            composable<WishlistRoute> { NotYet(R.string.nav_wishlist) }
            composable<BindersRoute> { entry ->
                // Like index.html?binder=: the grid, filtered to that folder.
                BindersScreen(entry.toRoute<BindersRoute>().type, onOpen = { nav.navigate(CardsRoute(binder = it)) })
            }
            composable<DecksRoute> { DecksScreen() }
            composable<DashboardRoute> { NotYet(R.string.nav_dashboard) }
            composable<DeckCheckerRoute> { NotYet(R.string.nav_deck_checker) }
            composable<ActivityRoute> { NotYet(R.string.nav_activity) }
            composable<SettingsRoute> { SettingsScreen() }
        }
    }

    if (moreOpen) {
        ModalBottomSheet(onDismissRequest = { moreOpen = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                val go = { route: Any -> moreOpen = false; nav.navigateTop(route) }
                visible.filter { !it.primary }.forEach { item ->
                    MoreEntry(item.icon, stringResource(item.label)) { go(item.route) }
                }
                // Like #nav-activity: only shown when write access is there.
                if (unlocked) MoreEntry("📜", stringResource(R.string.nav_activity)) { go(ActivityRoute) }
                HorizontalDivider()
                MoreEntry("⚙", stringResource(R.string.nav_settings)) { go(SettingsRoute) }
            }
        }
    }
}

@Composable
private fun MoreEntry(icon: String, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Text(icon) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/** Top-level navigation: one entry per section, state kept when switching back. */
fun NavHostController.navigateTop(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun NotYet(title: Int) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(title))
            Text(stringResource(R.string.common_not_yet), color = PortalColors.TextDim)
        }
    }
}
