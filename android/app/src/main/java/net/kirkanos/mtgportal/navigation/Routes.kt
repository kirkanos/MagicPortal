package net.kirkanos.mtgportal.navigation

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import net.kirkanos.mtgportal.R

// Type-safe routes. Cards takes optional filters so the folder and edition
// pages can link into a filtered grid, like ?binder= and ?set= on the web.
@Serializable data class CardsRoute(val binder: String? = null, val set: String? = null)
@Serializable data object EditionsRoute
@Serializable data object ReservedRoute
@Serializable data object WishlistRoute
@Serializable data class BindersRoute(val type: String)
@Serializable data object DecksRoute
@Serializable data object DashboardRoute
@Serializable data object DeckCheckerRoute
@Serializable data object ActivityRoute
@Serializable data object SettingsRoute

/**
 * The navigation entries of the web UI (NAV_ITEMS in shared.js). [navKey] is
 * the page name the admin's hiddenNav setting uses.
 */
enum class NavItem(
    val navKey: String,
    @StringRes val label: Int,
    val icon: String,
    val route: Any,
    /** In the bottom bar; everything else is under "Mehr". */
    val primary: Boolean,
) {
    CARDS("index", R.string.nav_cards, "🃏", CardsRoute(), true),
    EDITIONS("editions", R.string.nav_editions, "📚", EditionsRoute, true),
    WISHLIST("wishlist", R.string.nav_wishlist, "☆", WishlistRoute, true),
    DASHBOARD("dashboard", R.string.nav_dashboard, "📊", DashboardRoute, true),
    RESERVED("reserved", R.string.nav_reserved, "🛡", ReservedRoute, false),
    BINDERS("binders", R.string.nav_binders, "📁", BindersRoute("binder"), false),
    LISTS("lists", R.string.nav_lists, "📋", BindersRoute("list"), false),
    DECKS("decks", R.string.nav_decks, "🎴", DecksRoute, false),
    DECK_CHECKER("deck-checker", R.string.nav_deck_checker, "✔", DeckCheckerRoute, false),
}
