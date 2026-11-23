package net.kirkanos.mtgportal.scanner.match

import net.kirkanos.mtgportal.scanner.MatchSource
import net.kirkanos.mtgportal.scanner.catalog.CatalogIndex
import net.kirkanos.mtgportal.scanner.ocr.CardReading
import net.kirkanos.mtgportal.scanner.ocr.NumberCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** German cards: their titles lead to the English catalog names. */
class ForeignNamesTest {
    private val index = CatalogIndex.parse(
        """
        {"sets":[["snc","Streets of New Capenna","2022-04-29",281,"expansion"],["m10","Magic 2010","2009-07-17",249,"core"]],
         "cards":[["snc","47","Ledger Shredder","id-1",5],["m10","146","Lightning Bolt","id-2",5]],
         "names":[["Kontobuchschredder","Ledger Shredder","de"],["Blitzschlag","Lightning Bolt","de"]]}
        """.trimIndent(),
    )
    private val matcher = CardMatcher(index)

    private fun reading(name: String?, set: String? = null, number: String? = null) =
        CardReading(name, set, null, listOfNotNull(number?.let { NumberCandidate(it) }), null, null)

    @Test
    fun aGermanTitleNoLongerVetoesSetAndNumber() {
        val m = matcher.match(reading("Kontobuchschredder", "snc", "47"))!!
        assertEquals("Ledger Shredder", m.printing.name)
        assertEquals(MatchSource.OCR_SET_NUMBER, m.source)
        // A clearly different title still does: the title wins.
        val other = matcher.match(reading("Blitzschlag", "snc", "47"))!!
        assertEquals("Lightning Bolt" to MatchSource.OCR_NAME, other.printing.name to other.source)
    }

    @Test
    fun aGermanTitleFindsTheCardAndTellsTheLanguage() {
        val m = matcher.match(reading("Blitzschlaq", number = "146"))!!
        assertEquals("Lightning Bolt", m.printing.name)
        assertEquals("de", m.language)
        // An English title says nothing about the language.
        assertNull(matcher.match(reading("Lightning Bolt", number = "146"))!!.language)
    }

    @Test
    fun anOldIndexWithoutNamesStillParses() {
        val old = CatalogIndex.parse("""{"sets":[],"cards":[["snc","47","Ledger Shredder","id-1",5]]}""")
        assertEquals(1, old.size)
        assertEquals(emptyList<String>(), old.foreignNames("Ledger Shredder"))
    }
}
