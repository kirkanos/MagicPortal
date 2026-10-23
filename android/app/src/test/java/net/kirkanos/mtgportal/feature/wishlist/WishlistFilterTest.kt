package net.kirkanos.mtgportal.feature.wishlist

import net.kirkanos.mtgportal.core.network.dto.WishlistItemDto
import org.junit.Assert.assertEquals
import org.junit.Test

// Same behaviour as renderList() in wishlist.js.
class WishlistFilterTest {
    private val items = listOf(
        WishlistItemDto("Sol Ring", price = 2.0, targetMet = true, ownedQuantity = 0, changePct = -10.0, added = "2026-10-01", priority = 2),
        WishlistItemDto("Black Lotus", price = 20000.0, ownedQuantity = 1, needsUpgrade = true, changePct = null, added = "2026-10-03", note = "für den Cube"),
        WishlistItemDto("Bayou", price = null, ownedQuantity = 0, changePct = 5.0, added = "2026-10-02"),
    )

    private fun names(state: String = "all", sort: String = "default", q: String = "") =
        filterWishlist(items, q, state, sort).map { it.name }

    @Test
    fun defaultKeepsTheServerOrder() = assertEquals(listOf("Sol Ring", "Black Lotus", "Bayou"), names())

    @Test
    fun sorts() {
        assertEquals(listOf("Black Lotus", "Sol Ring", "Bayou"), names(sort = "price-desc"))
        assertEquals(listOf("Bayou", "Sol Ring", "Black Lotus"), names(sort = "price-asc"))
        // No history sorts last on "biggest drop".
        assertEquals(listOf("Sol Ring", "Bayou", "Black Lotus"), names(sort = "change-asc"))
        assertEquals(listOf("Black Lotus", "Bayou", "Sol Ring"), names(sort = "added"))
    }

    @Test
    fun states() {
        assertEquals(listOf("Sol Ring"), names(state = "target"))
        assertEquals(listOf("Black Lotus"), names(state = "upgrade"))
        assertEquals(listOf("Sol Ring", "Bayou"), names(state = "missing"))
    }

    @Test
    fun searchLooksIntoTheNoteToo() = assertEquals(listOf("Black Lotus"), names(q = "CUBE"))

    @Test
    fun saveSendsTheWholeRow() {
        val i = WishlistItemDto("Sol Ring", setCode = "c21", collectorNumber = "263", targetEur = 1.5, minCondition = "near_mint", note = "x", priority = 1)
        val input = i.toInput()
        assertEquals("c21", input.setCode)
        assertEquals("263", input.collectorNumber)
        assertEquals(1.5, input.targetEur!!, 0.0)
        assertEquals(1, input.priority)
    }
}
