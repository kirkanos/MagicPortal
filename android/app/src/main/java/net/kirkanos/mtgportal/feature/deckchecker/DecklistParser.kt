package net.kirkanos.mtgportal.feature.deckchecker

data class DeckEntry(val name: String, val qty: Int)

/**
 * parseDecklist() in deck-checker.js, line for line: section headers are
 * skipped, "4 Name" and "4x Name" both count, a trailing set/number like
 * "(2X2) 141" or "[2X2]" and a foil marker like "*F*" are stripped.
 */
object DecklistParser {
    private val SKIP_HEADERS = Regex("""^(deck|deckliste|sideboard|maybeboard|commander|companion)\s*:?\s*$""", RegexOption.IGNORE_CASE)
    private val LINE = Regex("""^(\d+)\s*x?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val SET_SUFFIX = Regex("""\s*[(\[][A-Za-z0-9]{2,6}[)\]]\s*[\w-]*\s*$""")
    private val FOIL_SUFFIX = Regex("""\s*\*[^*]*\*\s*$""")

    fun parse(text: String): List<DeckEntry> = text.split(Regex("\r?\n")).mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || SKIP_HEADERS.matches(line)) return@mapNotNull null
        val m = LINE.matchEntire(line) ?: return@mapNotNull null
        val qty = m.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: 1
        var name = m.groupValues[2].trim()
        name = SET_SUFFIX.replace(name, "").trim()
        name = FOIL_SUFFIX.replace(name, "").trim()
        DeckEntry(name, qty)
    }
}
