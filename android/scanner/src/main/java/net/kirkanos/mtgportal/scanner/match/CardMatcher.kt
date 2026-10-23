package net.kirkanos.mtgportal.scanner.match

import net.kirkanos.mtgportal.scanner.MatchSource
import net.kirkanos.mtgportal.scanner.catalog.CatalogIndex
import net.kirkanos.mtgportal.scanner.catalog.NameNormalizer
import net.kirkanos.mtgportal.scanner.catalog.Printing
import net.kirkanos.mtgportal.scanner.hash.CardHash
import net.kirkanos.mtgportal.scanner.hash.HashIndex
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
 *    name, the printing by its artwork when the hash index has it, otherwise
 *    by the copyright year.
 * D. Artwork only: foreign cards (the catalog knows English names) and
 *    unreadable titles. Taken only when the nearest scan is close and clearly
 *    closer than any other card's.
 *
 * Old printings of one card often share their art (Alpha, Beta, Revised);
 * the hash then finds the card, not the printing, and the user picks.
 */
class CardMatcher(private val index: CatalogIndex, private val hashes: HashIndex? = null) {

    /** [hash] is the camera image's artwork hash, null without a hash index. */
    fun match(r: CardReading, hash: CardHash? = null): Match? =
        bySetAndNumber(r) ?: byNameAndNumber(r, hash) ?: byName(r, hash) ?: byArtwork(hash)

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

    private fun byNameAndNumber(r: CardReading, hash: CardHash?): Match? {
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
                // Same number in several sets (reprints): the artwork decides.
                val ranked = if (hits.size > 1 && hash != null) rankByArtwork(hits, hash) else hits
                val conf = if (hits.size == 1) 0.85f * sim else 0.6f * sim
                return Match(ranked.first(), conf, MatchSource.OCR_NAME, (ranked.drop(1) + printings.filter { it !in hits }).take(12))
            }
            // The set size is the smallest print on the card and misread
            // most ("140/445" for 140/145); the number alone still beats
            // guessing by year.
            val byNumber = printings.filter { it.collectorNumber.equals(n.number, ignoreCase = true) }
            if (n.total != null && byNumber.size == 1) {
                return Match(byNumber.single(), 0.7f * sim, MatchSource.OCR_NAME, printings.filter { it !in byNumber }.take(12))
            }
        }
        return null
    }

    // ---- C ----

    private fun byName(r: CardReading, hash: CardHash?): Match? {
        val (cards, sim) = bestNames(r.name ?: return null) ?: return null
        if (hash != null && hashes != null) byNameAndArtwork(cards.map(index::printing), sim, hash)?.let { return it }
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
        // Regular printings the evidence allows: all of them without a year,
        // those of that year with one. A single one is as good as a number
        // (Quicksand in 2010 is Worldwake, Maze of Shadows only Tempest).
        val regular = printings.filter { index.sets[it.setCode]?.setType !in SIDE_SET_TYPES }
        val candidates = if (year == null) regular else regular.filter { index.sets[it.setCode]?.year == year }
        val conf = when {
            candidates.size == 1 && candidates[0] == pick -> 0.75f
            // Without a year the printing is a guess; a later frame that
            // reads the year must be able to outrank it (see ScanSession).
            year != null -> 0.55f
            else -> 0.5f
        }
        return Match(pick, conf * sim, MatchSource.OCR_NAME, printings.filter { it != pick }.take(12))
    }

    /** Hash distances (of 128 bits); tuned on camera images of real cards. */
    object HashThresholds {
        /** Farther than this is not the same picture. */
        const val MATCH = 28
        /** Up to this the picture alone is trusted. */
        const val ACCEPT = 20
        /** How much closer than any other card the best one must be. */
        const val GAP = 8
    }

    private companion object {
        /** Sets whose cards are rarely what someone scans: gold borders, promos, tokens, joke sets. */
        val SIDE_SET_TYPES = setOf("memorabilia", "promo", "token", "funny", "minigame", "alchemy", "vanguard", "treasure_chest")
    }

    // ---- artwork ----

    /** Among the printings of one name, the one whose scan looks most like the camera image. */
    private fun byNameAndArtwork(printings: List<Printing>, sim: Float, hash: CardHash): Match? {
        val h = hashes ?: return null
        val scored = printings.mapNotNull { p -> h.distance(hash, p.i)?.let { p to it } }.sortedBy { it.second }
        val (best, d) = scored.firstOrNull() ?: return null
        if (d > HashThresholds.MATCH) return null
        val conf = (if (d <= HashThresholds.ACCEPT) 0.85f else 0.65f) * sim
        val rest = scored.drop(1).map { it.first } + printings.filter { p -> scored.none { it.first == p } }
        return Match(best, conf, MatchSource.HASH, rest.take(12))
    }

    private fun rankByArtwork(ps: List<Printing>, hash: CardHash): List<Printing> {
        val h = hashes ?: return ps
        return ps.sortedBy { h.distance(hash, it.i) ?: Int.MAX_VALUE }
    }

    private fun byArtwork(hash: CardHash?): Match? {
        val h = hashes ?: return null
        val hits = h.nearest(hash ?: return null, 40)
        val best = hits.firstOrNull() ?: return null
        val name = index.printing(best.printing).name
        // The nearest scan of a different card: without a clear gap the
        // picture is ambiguous (or no card at all) and nothing is taken.
        val other = hits.firstOrNull { index.printing(it.printing).name != name }?.distance ?: Int.MAX_VALUE
        if (best.distance > HashThresholds.MATCH || other - best.distance < HashThresholds.GAP) return null
        val same = hits.filter { index.printing(it.printing).name == name }.map { index.printing(it.printing) }
        val conf = if (best.distance <= HashThresholds.ACCEPT) 0.8f else 0.55f
        return Match(same.first(), conf, MatchSource.HASH, same.drop(1).take(12))
    }

    // ---- names ----

    /**
     * The catalog entries whose name is closest to an OCR title. Also tries
     * the title without a leading one-letter word: the double-faced marker
     * reads as "A" ("A Bruce Banner"), but "A Little Chat" is a real name, so
     * both readings compete.
     */
    internal fun bestNames(title: String): Pair<IntArray, Float>? {
        if (shouting(title)) return null
        val readings = buildList {
            add(NameNormalizer.normalize(title))
            Regex("""^\S\s+(.+)$""").matchEntire(title.trim())?.let { add(NameNormalizer.normalize(it.groupValues[1])) }
        }.filter { it.length >= 3 }.distinct()
        var best: Pair<String, Float>? = null
        for (q in readings) for (name in index.byName.keys) {
            if (kotlin.math.abs(name.length - q.length) > 3) continue
            val d = NameNormalizer.levenshtein(q, name)
            // Short words are close to many names: "Decode" is two edits
            // from "Deicide". Up to six letters, one misread letter only.
            if (q.length <= 6 && d > 1) continue
            val s = 1f - d.toFloat() / maxOf(q.length, name.length)
            if (best == null || s > best.second) best = name to s
        }
        val (name, score) = best ?: return null
        if (score < 0.6f) return null
        return index.byName.getValue(name) to score
    }

    /**
     * Card titles are set in title case. Text in capitals is packaging,
     * a sleeve or the table ("STEAM DECK", "COLOR"), not a card name.
     */
    private fun shouting(title: String): Boolean {
        val letters = title.filter(Char::isLetter)
        return letters.length >= 4 && letters.count(Char::isUpperCase) > letters.length * 0.6
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
