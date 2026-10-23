package net.kirkanos.mtgportal.scanner.match

import net.kirkanos.mtgportal.scanner.MatchSource
import net.kirkanos.mtgportal.scanner.catalog.CatalogIndex
import net.kirkanos.mtgportal.scanner.catalog.NameNormalizer
import net.kirkanos.mtgportal.scanner.catalog.Printing
import net.kirkanos.mtgportal.scanner.ocr.CardReading

/** The best printing for a reading, how sure the matcher is, and the alternatives. */
data class Match(
    val printing: Printing,
    val confidence: Float,
    val source: MatchSource,
    /** Other printings of the same card, best first: the user may pick one. */
    val alternatives: List<Printing> = emptyList(),
)

/**
 * Finds the printing for a [CardReading], in three tiers:
 *
 * A. Set code and number printed (M15 frame and later): one lookup. The name,
 *    if readable, only confirms.
 * B. Number without set code (2003-2014 frames): the name gives the card, the
 *    number - and the set size printed next to it - the printing.
 * C. Name only (1993-1997 frames, or a number OCR could not read): the card by
 *    name, the printing by the copyright year. Old printings of one card
 *    often share their art; the image hash stage will narrow that down later.
 */
class CardMatcher(private val index: CatalogIndex) {

    fun match(r: CardReading): Match? = bySetAndNumber(r) ?: byNameAndNumber(r) ?: byName(r)

    // ---- A ----

    private fun bySetAndNumber(r: CardReading): Match? {
        val set = r.setCode ?: return null
        for (code in setVariants(set)) for (n in r.numbers) {
            val p = index.find(code, n.number) ?: continue
            val sim = r.name?.let { nameSimilarity(it, p.name) }
            // A clearly different name means set or number were misread.
            if (sim != null && sim < 0.4f) continue
            return Match(p, if (sim != null && sim >= 0.7f) 0.98f else 0.9f, MatchSource.OCR_SET_NUMBER)
        }
        return null
    }

    /**
     * OCR swaps O/0, I/1, S/5 and B/8; set codes legitimately contain digits
     * (2X2, M20, 40K). Instead of guessing, try every spelling and keep the
     * ones the catalog knows.
     */
    internal fun setVariants(code: String): List<String> {
        val swaps = mapOf('0' to "o", 'o' to "0", '1' to "il", 'i' to "1", 'l' to "1", '5' to "s", 's' to "5", '8' to "b", 'b' to "8")
        var variants = listOf("")
        for (ch in code.lowercase()) {
            variants = variants.flatMap { prefix -> (listOf(ch) + (swaps[ch]?.toList() ?: emptyList())).map { prefix + it } }
            if (variants.size > 32) variants = variants.take(32)
        }
        return (listOf(code.lowercase()) + variants).distinct().filter(index::hasSet)
    }

    // ---- B ----

    private fun byNameAndNumber(r: CardReading): Match? {
        if (r.numbers.isEmpty()) return null
        val (cards, sim) = bestNames(r.name ?: return null) ?: return null
        val printings = cards.map(index::printing)
        for (n in r.numbers) {
            // The printed set size never exceeds Scryfall's card count, which
            // also counts foil-only and promo variants (7ED: 350 printed, 708).
            val hits = printings.filter { it.collectorNumber.equals(n.number, ignoreCase = true) }
                .filter { p -> n.total == null || index.sets[p.setCode]?.cardCount?.let { n.total <= it + 10 } ?: true }
                .sortedBy { p -> if (n.total == null) 0 else index.sets[p.setCode]?.cardCount?.minus(n.total) ?: Int.MAX_VALUE }
            if (hits.isNotEmpty()) {
                val conf = if (hits.size == 1) 0.85f * sim else 0.6f * sim
                return Match(hits.first(), conf, MatchSource.OCR_NAME, (hits.drop(1) + printings.filter { it !in hits }).take(12))
            }
        }
        return null
    }

    // ---- C ----

    private fun byName(r: CardReading): Match? {
        val (cards, sim) = bestNames(r.name ?: return null) ?: return null
        val printings = cards.map(index::printing).sortedWith(
            compareBy<Printing> { if (index.sets[it.setCode]?.setType in SIDE_SET_TYPES) 1 else 0 }
                .thenByDescending { index.sets[it.setCode]?.year ?: 0 }
                // Within a year the original comes first, reprints and
                // variant sets follow it ...
                .thenBy { index.sets[it.setCode]?.releasedAt.orEmpty() }
                // ... and variant sets carry a qualifier in their name
                // ("Fourth Edition Foreign Black Border"). The border colour
                // from the cropper will settle these properly.
                .thenBy { index.sets[it.setCode]?.name?.length ?: 0 },
        )
        // The copyright year points at the printing: the newest one released
        // in or before that year.
        val year = r.year
        val pick = year?.let { y -> printings.firstOrNull { (index.sets[it.setCode]?.year ?: 0) <= y } } ?: printings.first()
        return Match(pick, 0.55f * sim, MatchSource.OCR_NAME, printings.filter { it != pick }.take(12))
    }

    private companion object {
        /** Sets whose cards are rarely what someone scans: gold borders, promos, tokens, joke sets. */
        val SIDE_SET_TYPES = setOf("memorabilia", "promo", "token", "funny", "minigame", "alchemy", "vanguard", "treasure_chest")
    }

    // ---- names ----

    /**
     * The catalog entries whose name is closest to an OCR title. Also tries
     * the title without a leading one-letter word: the double-faced marker
     * reads as "A" ("A Bruce Banner"), but "A Little Chat" is a real name, so
     * both readings compete.
     */
    internal fun bestNames(title: String): Pair<IntArray, Float>? {
        val readings = buildList {
            add(NameNormalizer.normalize(title))
            Regex("""^\S\s+(.+)$""").matchEntire(title.trim())?.let { add(NameNormalizer.normalize(it.groupValues[1])) }
        }.filter { it.length >= 3 }.distinct()
        var best: Pair<String, Float>? = null
        for (q in readings) for (name in index.byName.keys) {
            if (kotlin.math.abs(name.length - q.length) > 3) continue
            val s = 1f - NameNormalizer.levenshtein(q, name).toFloat() / maxOf(q.length, name.length)
            if (best == null || s > best.second) best = name to s
        }
        val (name, score) = best ?: return null
        if (score < 0.6f) return null
        return index.byName.getValue(name) to score
    }

    private fun nameSimilarity(title: String, catalogName: String): Float {
        val q = NameNormalizer.normalize(title)
        val faces = listOf(catalogName) + if ("//" in catalogName) catalogName.split("//") else emptyList()
        return faces.maxOf { f ->
            val n = NameNormalizer.normalize(f)
            1f - NameNormalizer.levenshtein(q, n).toFloat() / maxOf(q.length, n.length, 1)
        }
    }
}
