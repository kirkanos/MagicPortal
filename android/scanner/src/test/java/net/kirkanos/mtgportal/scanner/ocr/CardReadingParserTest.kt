package net.kirkanos.mtgportal.scanner.ocr

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The parser against what ML Kit really read from eleven cards across the
 * frame eras (ocr_fixtures.json, produced by OcrDumpTest on the phone).
 */
class CardReadingParserTest {
    private val fixtures: Map<String, List<OcrLine>> by lazy {
        val arr = JSONArray(javaClass.getResource("/ocr_fixtures.json")!!.readText())
        (0 until arr.length()).associate { i ->
            val card = arr.getJSONObject(i)
            val lines = card.getJSONArray("lines")
            card.getString("file") to (0 until lines.length()).map { j ->
                val l = lines.getJSONObject(j)
                OcrLine(l.getString("t"), l.getDouble("x").toFloat(), l.getDouble("y").toFloat(), l.getDouble("h").toFloat())
            }
        }
    }

    private fun read(file: String) = CardReadingParser.parse(fixtures.getValue(file))

    private fun check(file: String, set: String?, number: String?, total: Int?, rarity: Char?, lang: String?) {
        val r = read(file)
        assertEquals("$file set", set, r.setCode)
        assertEquals("$file number", number, r.numbers.firstOrNull()?.number)
        assertEquals("$file total", total, r.numbers.firstOrNull()?.total)
        assertEquals("$file rarity", rarity, r.rarity)
        assertEquals("$file language", lang, r.language)
    }

    @Test
    fun modernFrames() {
        check("snc_047.jpg", "snc", "47", 281, 'U', "en")
        check("2x2_bolt.jpg", "2x2", "117", 331, 'U', "en")
        // Showcase: the number is larger than the set size.
        check("m20_shivan.jpg", "m20", "335", 280, 'R', "en")
    }

    @Test
    fun zeroReadAsLetterO() = check("snc_ledger.jpg", "snc", "46", 281, 'R', "en")

    @Test
    fun commanderNumberWithoutTotal() = check("c21_solring.jpg", "c21", "263", null, null, "en")

    @Test
    fun rarityFirstFrameOf2025() = check("msh_bruce.jpg", "msh", "49", null, 'M', "en")

    @Test
    fun oldCopyrightLineWithSlashReadAsOne() {
        val r = read("7ed_counterspell.jpg")
        assertEquals(null, r.setCode)
        assertEquals(listOf(NumberCandidate("67", 350)), r.numbers)
        assertEquals(2001, r.year)
    }

    @Test
    fun ninetiesFramesHaveNoNumber_powerToughnessIsIgnored() {
        for (f in listOf("4ed_serra.jpg", "ice_brainstorm.jpg", "ice_necro.jpg")) {
            val r = read(f)
            assertEquals("$f numbers", emptyList<NumberCandidate>(), r.numbers)
            assertEquals("$f set", null, r.setCode)
            assertEquals("$f year", 1995, r.year)
        }
    }

    @Test
    fun truncatedNumberFallsBackToNameAndYear() {
        val r = read("m10_bolt.jpg")
        assertEquals(emptyList<NumberCandidate>(), r.numbers)
        assertEquals(2009, r.year)
        assertEquals("Lightning Bolt", r.name)
    }

    @Test
    fun titles() {
        assertEquals("A Little Chat", read("snc_047.jpg").name)
        assertEquals("Counterspell", read("7ed_counterspell.jpg").name)
        // The double-faced marker comes out as "A"; the fuzzy match deals with it.
        assertEquals("A Bruce Banner", read("msh_bruce.jpg").name)
        // Old fonts: OCR noise, again for the fuzzy match.
        assertEquals("Serra Age!", read("4ed_serra.jpg").name)
    }

    @Test
    fun copyrightSplits() {
        assertEquals(listOf(NumberCandidate("67", 350)), CardReadingParser.copyrightNumbers("Inc. 671350"))
        assertEquals(listOf(NumberCandidate("67", 350)), CardReadingParser.copyrightNumbers("Inc. 67/350"))
        assertEquals(listOf(NumberCandidate("183", 249)), CardReadingParser.copyrightNumbers("LLC 183/249"))
        assertEquals(emptyList<NumberCandidate>(), CardReadingParser.copyrightNumbers("All rights reserved."))
    }

    @Test
    fun digitLookalikes() {
        assertEquals("46", CardReadingParser.digits("o46"))
        assertEquals("49", CardReadingParser.digits("O049"))
        assertEquals("11", CardReadingParser.digits("l1"))
        assertEquals("0", CardReadingParser.digits("000"))
    }
}
