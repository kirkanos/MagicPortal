package net.kirkanos.mtgportal.scanner.hash

import net.kirkanos.mtgportal.scanner.MatchSource
import net.kirkanos.mtgportal.scanner.catalog.CatalogIndex
import net.kirkanos.mtgportal.scanner.match.CardMatcher
import net.kirkanos.mtgportal.scanner.ocr.CardReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

class HashIndexTest {
    private val catalog by lazy { CatalogIndex.parse(javaClass.getResource("/catalog_subset.json")!!.readText()) }

    /** The /api/catalog/hashes format: "MPH1", count, then id + h + v per printing. */
    private fun mph1(entries: List<Pair<String, CardHash>>): ByteArray {
        val buf = ByteBuffer.allocate(8 + 32 * entries.size)
        buf.put("MPH1".toByteArray()).putInt(entries.size)
        for ((id, hash) in entries) {
            val u = UUID.fromString(id)
            buf.putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).putLong(hash.h).putLong(hash.v)
        }
        return buf.array()
    }

    private fun id(set: String, num: String) = catalog.find(set, num)!!.scryfallId

    // Far-apart synthetic hashes; flip() moves one by a few bits, as a camera does.
    private val brainstorm = CardHash(0x0123456789abcdefL, 0x7edcba9876543210L)
    private val counterLea = CardHash(0x5555aaaa5555aaaaL, 0x0f0f0f0f0f0f0f0fL)
    private val counter7ed = CardHash(0x3c3c3c3cc3c3c3c3L, 0x6666999966669999L)
    private val necro = CardHash(-0x0123456789abcdf0L, 0x1111222233334444L)

    private fun flip(h: CardHash, bits: Int) = CardHash(h.h xor ((1L shl bits) - 1), h.v)

    private val hashes by lazy {
        HashIndex.parse(
            mph1(
                listOf(
                    id("ice", "61") to brainstorm,
                    id("lea", "54") to counterLea,
                    id("7ed", "67") to counter7ed,
                    id("ice", "154") to necro,
                    // A printing the catalog does not know is skipped.
                    "00000000-0000-0000-0000-000000000000" to brainstorm,
                ),
            ),
            catalog,
        )
    }
    private val matcher by lazy { CardMatcher(catalog, hashes) }

    private fun nothingRead() = CardReading(null, null, null, emptyList(), null, null)

    @Test
    fun parsesAndDropsUnknownPrintings() {
        assertEquals(4, hashes.size)
        val near = hashes.nearest(flip(brainstorm, 3), 2)
        assertEquals("Brainstorm", catalog.printing(near[0].printing).name)
        assertEquals(3, near[0].distance)
    }

    @Test
    fun artworkAloneFindsAForeignCard() {
        // Nothing readable in English - a German Brainstorm, say.
        val m = matcher.match(nothingRead().copy(name = "Brainstorm auf Deutsch"), flip(brainstorm, 6))!!
        assertEquals("ice/61", "${m.printing.setCode}/${m.printing.collectorNumber}")
        assertEquals(MatchSource.HASH, m.source)
        assertTrue(m.confidence >= 0.8f)
    }

    @Test
    fun aPictureFarFromEverythingIsNoCard() {
        assertNull(matcher.match(nothingRead(), CardHash(0, 0)))
    }

    @Test
    fun artworkPicksThePrintingOfANameOnlyRead() {
        // Title read, no number: the year would say 7th Edition, the artwork says Alpha.
        val m = matcher.match(nothingRead().copy(name = "Counterspell", year = 2001), flip(counterLea, 4))!!
        assertEquals("lea/54", "${m.printing.setCode}/${m.printing.collectorNumber}")
        assertEquals(MatchSource.HASH, m.source)
    }

    @Test
    fun withoutAMatchingScanTheYearStillDecides() {
        val m = matcher.match(nothingRead().copy(name = "Counterspell", year = 2001), CardHash(0, 0))!!
        assertEquals("7ed/67", "${m.printing.setCode}/${m.printing.collectorNumber}")
        assertEquals(MatchSource.OCR_NAME, m.source)
    }
}
