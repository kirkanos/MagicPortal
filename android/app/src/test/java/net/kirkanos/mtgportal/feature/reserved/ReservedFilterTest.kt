package net.kirkanos.mtgportal.feature.reserved

import net.kirkanos.mtgportal.core.network.dto.ReservedCardDto
import org.junit.Assert.assertEquals
import org.junit.Test

// Same behaviour as renderGrid() in reserved.js.
class ReservedFilterTest {
    private val cards = listOf(
        ReservedCardDto("Black Lotus", owned = false, cheapestPrice = 20000.0),
        ReservedCardDto("Ancestral Recall", owned = true, quantity = 1, cheapestPrice = 8000.0),
        ReservedCardDto("Bayou", owned = false, cheapestPrice = null),
        ReservedCardDto("Badlands", owned = true, cheapestPrice = 8000.0),
    )

    private fun names(owned: String = "all", sort: String = "price-desc", q: String = "") =
        filterReserved(cards, q, owned, sort).map { it.name }

    @Test
    fun priceDescTiesByName_unpricedLast() =
        assertEquals(listOf("Black Lotus", "Ancestral Recall", "Badlands", "Bayou"), names())

    @Test
    fun priceAscCountsUnpricedAsZero() =
        assertEquals(listOf("Bayou", "Ancestral Recall", "Badlands", "Black Lotus"), names(sort = "price-asc"))

    @Test
    fun ownedAndMissing() {
        assertEquals(listOf("Ancestral Recall", "Badlands"), names(owned = "owned", sort = "name"))
        assertEquals(listOf("Bayou", "Black Lotus"), names(owned = "missing", sort = "name"))
    }

    @Test
    fun searchIsCaseInsensitiveSubstring() = assertEquals(listOf("Badlands", "Bayou"), names(q = "BA", sort = "name"))
}
