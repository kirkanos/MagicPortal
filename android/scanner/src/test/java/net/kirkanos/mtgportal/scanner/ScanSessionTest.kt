package net.kirkanos.mtgportal.scanner

import net.kirkanos.mtgportal.scanner.catalog.Printing
import net.kirkanos.mtgportal.scanner.match.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScanSessionTest {
    private fun m(i: Int) = Match(Printing(i, "snc", "$i", "Card $i", "id$i", true), 0.98f, MatchSource.OCR_SET_NUMBER)

    @Test
    fun oneFrameIsNotEnough() {
        val s = ScanSession()
        assertNull(s.offer(m(1), "en"))
        assertNotNull(s.offer(m(1), "en"))
        assertEquals(1, s.items.single().quantity)
    }

    @Test
    fun aCardLyingStillCountsOnce() {
        val s = ScanSession()
        repeat(10) { s.offer(m(1), "en") }
        assertEquals(1, s.items.single().quantity)
    }

    @Test
    fun theNextCopyCountsAfterTheCardWasTakenAway() {
        val s = ScanSession()
        repeat(3) { s.offer(m(1), "en") }
        repeat(3) { s.offer(null, null) }
        repeat(2) { s.offer(m(1), "en") }
        assertEquals(2, s.items.single().quantity)
    }

    @Test
    fun aGlitchDoesNotEndTheCooldown() {
        val s = ScanSession()
        repeat(3) { s.offer(m(1), "en") }
        s.offer(null, null) // one unreadable frame while the card still lies there
        repeat(3) { s.offer(m(1), "en") }
        assertEquals(1, s.items.single().quantity)
    }

    @Test
    fun aSingleMisreadInBetweenIsOutvoted() {
        val s = ScanSession()
        s.offer(m(1), "en")
        s.offer(m(2), "en")
        assertNotNull(s.offer(m(1), "en"))
        assertEquals(listOf(1), s.items.map { it.printing.i })
    }

    @Test
    fun cardsSwappedQuicklyAreBothTaken() {
        val s = ScanSession()
        repeat(2) { s.offer(m(1), "en") }
        repeat(2) { s.offer(m(2), "en") }
        assertEquals(listOf(2, 1), s.items.map { it.printing.i })
    }

    @Test
    fun languagesAreSeparateLines() {
        val s = ScanSession()
        repeat(2) { s.offer(m(1), "en") }
        repeat(3) { s.offer(null, null) }
        repeat(2) { s.offer(m(1), "de") }
        assertEquals(listOf("de", "en"), s.items.map { it.language })
    }

    @Test
    fun editsReachTheResult() {
        val s = ScanSession()
        repeat(2) { s.offer(m(1), null) }
        val id = s.items.single().id
        s.update(id) { it.copy(foil = "foil", quantity = 4) }
        val card = s.result().items.single()
        assertEquals("foil", card.foil)
        assertEquals(4, card.quantity)
        assertEquals("en", card.language)
        s.remove(id)
        assertEquals(0, s.result().items.size)
    }

    private fun quicksand(set: String, conf: Float, i: Int) =
        Match(Printing(i, set, "140", "Quicksand", "id$i", true), conf, MatchSource.OCR_NAME)

    @Test
    fun aBetterPrintingCorrectsTheLineInsteadOfAddingOne() {
        // As on the device: no year at first (a guess), then 2010 read.
        val s = ScanSession()
        repeat(2) { s.offer(quicksand("plst", 0.5f, 1), "en") }
        repeat(2) { s.offer(quicksand("wwk", 0.55f, 2), "en") }
        val item = s.items.single()
        assertEquals("wwk", item.printing.setCode)
        assertEquals(1, item.quantity)
        assertEquals(listOf("plst"), item.alternatives.map { it.setCode })
    }

    @Test
    fun aWorseMatchDoesNotUndoIt() {
        val s = ScanSession()
        repeat(2) { s.offer(quicksand("wwk", 0.55f, 2), "en") }
        s.offer(quicksand("plst", 0.5f, 1), "en")
        assertEquals("wwk", s.items.single().printing.setCode)
    }

    @Test
    fun theUsersChoiceIsNotOverwritten() {
        val s = ScanSession()
        repeat(2) { s.offer(quicksand("plst", 0.5f, 1), "en") }
        val id = s.items.single().id
        s.update(id) { it.copy(printing = Printing(3, "vis", "166", "Quicksand", "id3", true), source = MatchSource.MANUAL, confidence = 1f) }
        s.offer(quicksand("wwk", 0.55f, 2), "en")
        assertEquals("vis", s.items.single().printing.setCode)
    }

    @Test
    fun aCorrectedLineJoinsItsTwin() {
        val s = ScanSession()
        repeat(2) { s.offer(quicksand("wwk", 0.55f, 2), "en") }
        repeat(3) { s.offer(null, null) }
        // The second copy is first guessed, then read properly.
        repeat(2) { s.offer(quicksand("plst", 0.5f, 1), "en") }
        repeat(2) { s.offer(quicksand("wwk", 0.55f, 2), "en") }
        val item = s.items.single()
        assertEquals("wwk", item.printing.setCode)
        assertEquals(2, item.quantity)
    }

    @Test
    fun manualCardsSkipTheVoteAndMergeLikeScans() {
        val s = ScanSession()
        val p = Printing(9, "ice", "61", "Brainstorm", "id9", true)
        s.addManual(p, emptyList())
        s.addManual(p, emptyList())
        val item = s.items.single()
        assertEquals(2, item.quantity)
        assertEquals(MatchSource.MANUAL, item.source)
    }

    @Test
    fun foilAndNonFoilAreSeparateLines() {
        val s = ScanSession()
        repeat(2) { s.offer(m(1), "en", "foil") }
        repeat(3) { s.offer(null, null) }
        repeat(2) { s.offer(m(1), "en") }
        assertEquals(listOf("normal", "foil"), s.items.map { it.foil })
        assertEquals("foil", s.result().items.last().foil)
    }
}
