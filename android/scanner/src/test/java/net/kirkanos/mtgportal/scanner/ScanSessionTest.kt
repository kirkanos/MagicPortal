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
}
