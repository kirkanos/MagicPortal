package net.kirkanos.mtgportal.scanner.match

import net.kirkanos.mtgportal.scanner.MatchSource
import net.kirkanos.mtgportal.scanner.catalog.CatalogIndex
import net.kirkanos.mtgportal.scanner.ocr.CardReadingParser
import net.kirkanos.mtgportal.scanner.ocr.OcrLine
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End to end on the JVM: what ML Kit read from eleven real cards, through the
 * parser, against the printings of those cards and some look-alikes from the
 * real catalog (488 printings in 177 sets, catalog_subset.json).
 */
class CardMatcherTest {
    private val index by lazy { CatalogIndex.parse(javaClass.getResource("/catalog_subset.json")!!.readText()) }
    private val matcher by lazy { CardMatcher(index) }

    private val ocr: Map<String, List<OcrLine>> by lazy {
        val arr = JSONArray(javaClass.getResource("/ocr_fixtures.json")!!.readText())
        (0 until arr.length()).associate { i ->
            val c = arr.getJSONObject(i)
            val ls = c.getJSONArray("lines")
            c.getString("file") to (0 until ls.length()).map { j ->
                val l = ls.getJSONObject(j)
                OcrLine(l.getString("t"), l.getDouble("x").toFloat(), l.getDouble("y").toFloat(), l.getDouble("h").toFloat())
            }
        }
    }

    private fun match(file: String) = matcher.match(CardReadingParser.parse(ocr.getValue(file)))

    private fun expect(file: String, set: String, number: String, source: MatchSource) {
        val m = match(file)
        assertNotNull("$file: no match", m)
        assertEquals("$file", "$set/$number", "${m!!.printing.setCode}/${m.printing.collectorNumber}")
        assertEquals("$file source", source, m.source)
    }

    @Test
    fun tierA_setAndNumberPrinted() {
        expect("snc_047.jpg", "snc", "47", MatchSource.OCR_SET_NUMBER)
        expect("snc_ledger.jpg", "snc", "46", MatchSource.OCR_SET_NUMBER)
        expect("2x2_bolt.jpg", "2x2", "117", MatchSource.OCR_SET_NUMBER)
        expect("m20_shivan.jpg", "m20", "335", MatchSource.OCR_SET_NUMBER)
        expect("c21_solring.jpg", "c21", "263", MatchSource.OCR_SET_NUMBER)
        expect("msh_bruce.jpg", "msh", "49", MatchSource.OCR_SET_NUMBER)
    }

    @Test
    fun tierA_isConfident() {
        for (f in listOf("snc_047.jpg", "2x2_bolt.jpg", "c21_solring.jpg")) {
            assertTrue("$f confidence", match(f)!!.confidence >= 0.9f)
        }
    }

    @Test
    fun tierB_numberWithoutSetCode() = expect("7ed_counterspell.jpg", "7ed", "67", MatchSource.OCR_NAME)

    @Test
    fun tierC_nameAndCopyrightYear() {
        expect("ice_brainstorm.jpg", "ice", "61", MatchSource.OCR_NAME)
        expect("ice_necro.jpg", "ice", "154", MatchSource.OCR_NAME)
        // "Serra Age!" - fuzzy name, then 1995 picks Fourth Edition.
        expect("4ed_serra.jpg", "4ed", "50", MatchSource.OCR_NAME)
        // Number cut off by OCR; the name and 2009 lead to Magic 2010.
        expect("m10_bolt.jpg", "m10", "146", MatchSource.OCR_NAME)
    }

    @Test
    fun tierC_offersTheOtherPrintings() {
        val m = match("ice_brainstorm.jpg")!!
        assertTrue("alternatives", m.alternatives.isNotEmpty())
        assertTrue("name-only is not auto-accepted", m.confidence < 0.8f)
    }

    @Test
    fun setCodeVariantsOnlyKeepKnownSets() {
        assertEquals(listOf("m20"), matcher.setVariants("M2O"))
        assertEquals(listOf("snc"), matcher.setVariants("5NC"))
        assertTrue(matcher.setVariants("ZZZ").isEmpty())
    }

    @Test
    fun aLeadingLetterIsTriedBothWays() {
        // "A Bruce Banner": the face name without the marker wins.
        val (idx, score) = matcher.bestNames("A Bruce Banner")!!
        assertEquals("Bruce Banner // The Incredible Hulk", index.printing(idx[0]).name)
        assertTrue(score > 0.9f)
        // ... while "A Little Chat" keeps its "A".
        assertEquals("A Little Chat", index.printing(matcher.bestNames("A Little Chat")!!.first[0]).name)
    }
}
