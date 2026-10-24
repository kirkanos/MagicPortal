package net.kirkanos.mtgportal.scanner.catalog

/**
 * Twin of normalizeName in backend/catalog.go: lowercase, diacritics folded
 * with the same table as the web's collation (collateFold in cards.go), word
 * separators to one space, everything else dropped. Both sides are tested
 * against backend/testdata/names.json.
 */
object NameNormalizer {
    private val FOLD = mapOf(
        'á' to "a", 'à' to "a", 'â' to "a", 'ä' to "a", 'ã' to "a", 'å' to "a",
        'é' to "e", 'è' to "e", 'ê' to "e", 'ë' to "e",
        'í' to "i", 'ì' to "i", 'î' to "i", 'ï' to "i",
        'ó' to "o", 'ò' to "o", 'ô' to "o", 'ö' to "o", 'õ' to "o", 'ø' to "o",
        'ú' to "u", 'ù' to "u", 'û' to "u", 'ü' to "u",
        'ñ' to "n", 'ç' to "c", 'ý' to "y", 'ÿ' to "y", 'š' to "s", 'ž' to "z",
        'ß' to "ss", 'æ' to "ae", 'œ' to "oe",
    )

    fun normalize(s: String): String {
        val b = StringBuilder(s.length)
        var space = false
        for (r in s.lowercase()) {
            val folded = FOLD[r]
            when {
                folded != null -> { b.append(folded); space = false }
                r in 'a'..'z' || r in '0'..'9' -> { b.append(r); space = false }
                r == ' ' || r == '-' || r == '/' -> if (!space && b.isNotEmpty()) { b.append(' '); space = true }
            }
        }
        return b.toString().trim()
    }

    /** Edit distance with two reused rows; inputs are short card names. */
    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
