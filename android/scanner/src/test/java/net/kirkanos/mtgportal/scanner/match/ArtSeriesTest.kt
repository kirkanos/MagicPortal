package net.kirkanos.mtgportal.scanner.match

import net.kirkanos.mtgportal.scanner.catalog.CatalogIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtSeriesTest {
    private val index = CatalogIndex.parse(
        """{"sets":[["snc","Streets of New Capenna","2022-04-29",467,"expansion"],["asnc","New Capenna Art Series","2022-04-29",81,"memorabilia"]],
           "cards":[["snc","47","Ledger Shredder","id-1",13],["asnc","12","Ledger Shredder // Ledger Shredder","id-2",35]]}""",
    )

    @Test
    fun artCardsAreMarkedAndNamedOnce() {
        val art = index.find("asnc", "12")!!
        assertTrue(art.artSeries)
        assertEquals("Ledger Shredder", art.displayName)
        assertFalse(index.find("snc", "47")!!.artSeries)
    }

    @Test
    fun theArtCardAndThePlayableCardOfferEachOther() {
        val m = CardMatcher(index)
        assertEquals(setOf("snc", "asnc"), m.printingsOf("Ledger Shredder // Ledger Shredder").map { it.setCode }.toSet())
        assertEquals(setOf("snc", "asnc"), m.printingsOf("Ledger Shredder").map { it.setCode }.toSet())
        // The playable card comes first: the art set is a side set.
        assertEquals("snc", m.printingsOf("Ledger Shredder").first().setCode)
    }
}
