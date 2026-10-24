package net.kirkanos.mtgportal.scanner.ocr

/** A collector number as read, possibly with the set size printed next to it. */
data class NumberCandidate(val number: String, val total: Int? = null)

/** What one camera frame (or one card image) revealed about the card. */
data class CardReading(
    /** Title text as read, unmatched; the matcher compares it fuzzily. */
    val name: String?,
    /** Lowercase set code as printed (2X2, SNC, M20, ...), not yet validated. */
    val setCode: String?,
    /** Collection language code (en, de, ...), from the set/language line. */
    val language: String?,
    /** Possible collector numbers; several when OCR left it ambiguous. */
    val numbers: List<NumberCandidate>,
    /** C U R M L T S P, when printed. */
    val rarity: Char?,
    /** Newest copyright year, a hint for the era of old printings. */
    val year: Int?,
) {
    /** True when the card itself names its printing: set code plus number. */
    val hasPrinting get() = setCode != null && numbers.isNotEmpty()
}

/**
 * Turns ML Kit's lines into a [CardReading].
 *
 * Every rule here comes from what ML Kit actually reads off real cards (see
 * OcrDumpTest and the fixtures), not from what the card is supposed to say:
 *
 * - M15 frame and later, two lines bottom left: "047/281 U" over
 *   "SNC • EN > ARTIST". The bullet often sticks to the code ("SNC• EN"),
 *   the artist shares the line, and a zero may come out as "o" ("o46/281").
 * - Commander and similar sets print the number alone: "263".
 * - The 2025+ frame puts the rarity first: "M 0049", read as "M O049".
 * - 2003-2014 frames print "67/350" at the end of the copyright line, and the
 *   slash is read as a "1": "...Inc. 671350". These have no set code.
 * - 1993-1997 frames print no number at all; only the name and the copyright
 *   year are there.
 * - Power/toughness ("4/4", read as "44/44") sits bottom right, so number
 *   lines are only taken from the left part of the card.
 */
object CardReadingParser {
    private const val BOTTOM = 0.88f
    private const val LEFT = 0.35f
    private const val TITLE = 0.13f

    private const val D = "[0-9oOIl|]" // a digit as OCR may render it

    /** "047/281 U", "o46/281 R", "117/331 U", "335/280 R" */
    private val NUMBER_TOTAL = Regex("""^($D{1,4})\s*/\s*($D{1,4})(?:\s+([CURMLTSPB]))?$""")

    /** "M O049" - rarity first, 2025+ frame */
    private val RARITY_NUMBER = Regex("""^([CURMLTSPB])\s+($D{3,4})$""")

    /** "263" - the number alone */
    private val BARE_NUMBER = Regex("""^($D{1,4})$""")

    /** "SNC • EN > ARTIST", "2X2• EN", "C21 • EN >MIKE BIEREK" */
    private val SET_LANG = Regex("""^([A-Z0-9]{3,5})\s*[•·*.,]?\s*([A-Z]{2})\b""")

    private val COPYRIGHT = Regex("""wizards|coast|©|™|\binc\b""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""(?:19|20)\d{2}""")

    /** Printed language codes -> collection codes (ManaBox/Scryfall vocabulary). */
    private val LANGUAGES = mapOf(
        "EN" to "en", "DE" to "de", "FR" to "fr", "IT" to "it", "ES" to "es", "SP" to "es", "PT" to "pt",
        "JP" to "ja", "JA" to "ja", "KR" to "ko", "KO" to "ko", "RU" to "ru", "CS" to "zhs", "CT" to "zht",
    )

    fun parse(lines: List<OcrLine>): CardReading {
        val bottom = lines.filter { it.y > BOTTOM }
        var setCode: String? = null
        var language: String? = null
        var rarity: Char? = null
        val numbers = mutableListOf<NumberCandidate>()

        for (line in bottom.filter { it.x < LEFT }) {
            val t = line.text.trim()
            NUMBER_TOTAL.matchEntire(t)?.let { m ->
                numbers += NumberCandidate(digits(m.groupValues[1]), digits(m.groupValues[2]).toIntOrNull())
                m.groupValues[3].firstOrNull()?.let { rarity = it }
                return@let
            }
            RARITY_NUMBER.matchEntire(t)?.let { m ->
                rarity = m.groupValues[1].first()
                numbers += NumberCandidate(digits(m.groupValues[2]))
            }
            BARE_NUMBER.matchEntire(t)?.let { m -> numbers += NumberCandidate(digits(m.groupValues[1])) }
            if (setCode == null) {
                SET_LANG.find(t.uppercase())?.let { m ->
                    val code = m.groupValues[1]
                    val lang = LANGUAGES[m.groupValues[2]]
                    // A set code has a letter; "2021 EN" is not one.
                    if (lang != null && code.any { it.isLetter() }) {
                        setCode = code.lowercase()
                        language = lang
                    }
                }
            }
        }

        val copyright = bottom.filter { COPYRIGHT.containsMatchIn(it.text) }
        // Old frames: "67/350" at the end of the copyright line, slash misread.
        if (numbers.isEmpty()) copyright.forEach { numbers += copyrightNumbers(it.text) }
        val year = copyright.flatMap { YEAR.findAll(it.text).map { y -> y.value.toInt() } }.maxOrNull()

        return CardReading(
            name = titleOf(lines),
            setCode = setCode,
            language = language,
            numbers = numbers.distinct(),
            rarity = rarity,
            year = year,
        )
    }

    /** OCR's letter look-alikes back to digits, leading zeros dropped ("o46" -> "46", "O049" -> "49"). */
    internal fun digits(s: String): String {
        val d = s.map {
            when (it) {
                'o', 'O' -> '0'
                'I', 'l', '|' -> '1'
                else -> it
            }
        }.joinToString("")
        return d.trimStart('0').ifEmpty { "0" }
    }

    /**
     * The trailing "number/total" of an old copyright line, where the slash
     * may come out as 1, l, I or |. Every way to split the digit run is a
     * candidate as long as the number fits into the set; the catalog decides.
     */
    internal fun copyrightNumbers(text: String): List<NumberCandidate> {
        val tail = Regex("""([0-9/|lI]{3,8})\s*$""").find(text)?.groupValues?.get(1) ?: return emptyList()
        val out = mutableListOf<NumberCandidate>()
        for (i in 1 until tail.length - 1) {
            if (tail[i] !in "/1lI|") continue
            val number = tail.substring(0, i)
            val total = tail.substring(i + 1)
            if (number.length !in 1..3 || total.length !in 2..3) continue
            if (!number.all { it.isDigit() } || !total.all { it.isDigit() }) continue
            val n = number.toInt()
            val t = total.toInt()
            if (n in 1..t) out += NumberCandidate(n.toString(), t)
        }
        return out
    }

    /** The title: in the top band, left half, the tallest text there. */
    private fun titleOf(lines: List<OcrLine>): String? =
        lines.filter { it.y < TITLE && it.x < 0.45f && it.text.count(Char::isLetter) >= 3 }
            .maxByOrNull { it.h }?.text?.trim()
}
