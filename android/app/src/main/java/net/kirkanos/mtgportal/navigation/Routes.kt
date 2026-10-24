package net.kirkanos.mtgportal.navigation

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.ui.icons.AppIcon
import net.kirkanos.mtgportal.core.ui.icons.Icons

// Type-safe routes. Cards takes optional filters so the folder and edition
// pages can link into a filtered grid, like ?binder= and ?set= on the web.
@Serializable data class CardsRoute(val binder: String? = null, val set: String? = null)
@Serializable data object EditionsRoute
@Serializable data class SetCardsRoute(val code: String)
@Serializable data object ReservedRoute
@Serializable data object WishlistRoute
@Serializable data class BindersRoute(val type: String)
@Serializable data object DecksRoute
@Serializable data object DashboardRoute
@Serializable data object DeckCheckerRoute
@Serializable data object ActivityRoute
@Serializable data object SettingsRoute
@Serializable data object AdminRoute
@Serializable data object ScannerRoute
@Serializable data object ScanResultRoute
@Serializable data object HandoverRoute

/**
 * The navigation entries of the web UI (NAV_ITEMS in shared.js). [navKey] is
 * the page name the admin's hiddenNav setting uses.
 */
enum class NavItem(
    val navKey: String,
    @StringRes val label: Int,
    val icon: AppIcon,
    val route: Any,
    /**
     * In the bottom bar, laid out like ManaBox's: two sections, the scanner
     * in the middle, two more (the last being "Mehr"). Everything else is
     * under "Mehr".
     */
    val primary: Boolean,
) {
    CARDS("index", R.string.nav_cards, Icons.Collection, CardsRoute(), true),
    EDITIONS("editions", R.string.nav_editions, Icons.Editions, EditionsRoute, true),
    WISHLIST("wishlist", R.string.nav_wishlist, Icons.Star, WishlistRoute, true),
    DASHBOARD("dashboard", R.string.nav_dashboard, Icons.Stats, DashboardRoute, false),
    RESERVED("reserved", R.string.nav_reserved, Icons.Shield, ReservedRoute, false),
    BINDERS("binders", R.string.nav_binders, Icons.Folder, BindersRoute("binder"), false),
    LISTS("lists", R.string.nav_lists, Icons.List, BindersRoute("list"), false),
    DECKS("decks", R.string.nav_decks, Icons.Planeswalker, DecksRoute, false),
    DECK_CHECKER("deck-checker", R.string.nav_deck_checker, Icons.Checklist, DeckCheckerRoute, false),
}
