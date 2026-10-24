package net.kirkanos.mtgportal.feature.deckchecker

import org.junit.Assert.assertEquals
import org.junit.Test

// Mirrors parseDecklist() in deck-checker.js.
class DecklistParserTest {
    private fun parse(s: String) = DecklistParser.parse(s)

    @Test
    fun basicFormats() = assertEquals(
        listOf(DeckEntry("Lightning Bolt", 1), DeckEntry("Brainstorm", 4), DeckEntry("Sol Ring", 2)),
        parse("1 Lightning Bolt\n4x Brainstorm\n2X Sol Ring"),
    )

    @Test
    fun headersAndBlankLinesAreSkipped() = assertEquals(
        listOf(DeckEntry("Island", 10), DeckEntry("Duress", 2)),
        parse("Deck\n10 Island\n\nSideboard:\n2 Duress\nCommander\n"),
    )

    @Test
    fun setSuffixAndFoilMarkerAreStripped() = assertEquals(
        listOf(DeckEntry("Thoughtseize", 4), DeckEntry("Counterspell", 1), DeckEntry("Fire // Ice", 2)),
        parse("4 Thoughtseize (2X2) 141\n1 Counterspell [MH2]\n2 Fire // Ice *F*"),
    )

    @Test
    fun linesWithoutCountAreIgnored() = assertEquals(
        listOf(DeckEntry("Opt", 1)),
        parse("Lightning Bolt\n1 Opt\n// comment\nx Brainstorm"),
    )

    @Test
    fun zeroCountBecomesOneAndWindowsNewlinesWork() = assertEquals(
        listOf(DeckEntry("Opt", 1), DeckEntry("Ponder", 3)),
        parse("0 Opt\r\n3 Ponder\r\n"),
    )
}
