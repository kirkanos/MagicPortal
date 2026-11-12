package net.kirkanos.mtgportal.scanner

import net.kirkanos.mtgportal.scanner.catalog.Printing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PricesTest {
    @Test
    fun foilFallsBackToNormalPrice() {
        val p = CardPrice(eur = 1.5, eurFoil = 4.0)
        assertEquals(1.5, p.forFinish("normal")!!, 0.0)
        assertEquals(4.0, p.forFinish("foil")!!, 0.0)
        assertEquals(4.0, p.forFinish("etched")!!, 0.0)
        assertEquals(1.5, CardPrice(1.5, null).forFinish("foil")!!, 0.0)
        assertNull(CardPrice(null, null).forFinish("normal"))
    }

    @Test
    fun totalCountsQuantitiesAndSkipsUnknownPrices() {
        fun item(id: Long, scryfallId: String, foil: String, qty: Int) =
            SessionItem(id, Printing(id.toInt(), "snc", "$id", "Card $id", scryfallId, true), emptyList(), MatchSource.OCR_SET_NUMBER, 1f, foil = foil, quantity = qty)
        val state = ScannerUiState(
            items = listOf(item(1, "a", "normal", 3), item(2, "b", "foil", 1), item(3, "c", "normal", 2)),
            prices = mapOf("a" to CardPrice(0.5, 2.0), "b" to CardPrice(1.0, 7.25)),
        )
        assertEquals(3 * 0.5 + 7.25, state.total, 1e-9)
    }
}
