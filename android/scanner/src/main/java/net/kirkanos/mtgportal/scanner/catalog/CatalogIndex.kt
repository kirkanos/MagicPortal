package net.kirkanos.mtgportal.scanner.catalog

import org.json.JSONArray
import org.json.JSONObject

/** A set as the index knows it. */
data class CatalogSet(val code: String, val name: String, val releasedAt: String, val cardCount: Int, val setType: String) {
    val year: Int? get() = releasedAt.take(4).toIntOrNull()
}

/**
 * One printing; [i] is its position in the index arrays. [finishes] in the
 * collection's words (normal, foil, etched); empty when the server does not
 * know them yet, which allows every finish.
 */
data class Printing(
    val i: Int,
    val setCode: String,
    val collectorNumber: String,
    val name: String,
    val scryfallId: String,
    val hasImage: Boolean,
    val finishes: Set<String> = emptySet(),
) {
    fun hasFinish(f: String) = finishes.isEmpty() || f in finishes

    /** [wanted] if this printing exists so, else its first finish: a foil-only promo is foil. */
    fun finishFor(wanted: String) = if (hasFinish(wanted)) wanted else FINISH_ORDER.first { it in finishes }

    companion object {
        val FINISH_ORDER = listOf("normal", "foil", "etched")

        /** Bits of /api/catalog/index: 4 nonfoil, 8 foil, 16 etched. */
        fun finishes(flags: Int): Set<String> = buildSet {
            if (flags and 4 != 0) add("normal")
            if (flags and 8 != 0) add("foil")
            if (flags and 16 != 0) add("etched")
        }
    }
}

/**
 * The Scryfall catalog on the phone, built from /api/catalog/index.
 *
 * Read-only and loaded once per scanner session, so it lives in plain arrays
 * rather than a database: 110,000 printings fit in a few tens of MB, a lookup
 * by set and number is one hash probe, and the fuzzy name search walks a list
 * of distinct names. Room would add annotation processing and migrations for
 * data that is never written on the device.
 */
class CatalogIndex private constructor(
    private val setCodes: Array<String>,
    private val numbers: Array<String>,
    private val names: Array<String>,
    private val ids: Array<String>,
    private val flags: IntArray,
    val sets: Map<String, CatalogSet>,
) {
    private val byKey: HashMap<String, Int> = HashMap<String, Int>(setCodes.size * 2).apply {
        for (i in setCodes.indices) put(key(setCodes[i], numbers[i]), i)
    }

    /** Normalised name (and each face of a double-faced card) -> printing indices. */
    val byName: Map<String, IntArray> = run {
        val tmp = HashMap<String, MutableList<Int>>()
        for (i in names.indices) {
            val full = names[i]
            tmp.getOrPut(NameNormalizer.normalize(full)) { mutableListOf() } += i
            if ("//" in full) full.split("//").forEach { face ->
                val n = NameNormalizer.normalize(face)
                if (n.isNotEmpty()) tmp.getOrPut(n) { mutableListOf() } += i
            }
        }
        tmp.mapValues { it.value.distinct().toIntArray() }
    }

    val size get() = setCodes.size

    fun printing(i: Int) = Printing(i, setCodes[i], numbers[i], names[i], ids[i], flags[i] and 1 != 0, Printing.finishes(flags[i]))

    /** Set code of a printing, without building the [Printing]. */
    fun setOf(i: Int) = setCodes[i]

    fun find(setCode: String, number: String): Printing? = byKey[key(setCode, number)]?.let(::printing)

    fun hasSet(code: String) = code in sets

    private val byId: HashMap<String, Int> by lazy { HashMap<String, Int>(ids.size * 2).apply { ids.forEachIndexed { i, id -> put(id, i) } } }

    /** Printing by Scryfall ID (lowercase, with dashes), for the hash index. */
    fun indexOfId(id: String): Int? = byId[id]

    companion object {
        private fun key(set: String, number: String) = set.lowercase() + "|" + number.lowercase()

        /** Parses the JSON of /api/catalog/index: {"sets":[[...]],"cards":[[set,num,name,id,flags]]}. */
        fun parse(json: String): CatalogIndex {
            val root = JSONObject(json)
            val setArr = root.getJSONArray("sets")
            val sets = HashMap<String, CatalogSet>(setArr.length())
            for (i in 0 until setArr.length()) {
                val s = setArr.getJSONArray(i)
                sets[s.getString(0)] = CatalogSet(s.getString(0), s.getString(1), s.getString(2), s.optInt(3), s.optString(4))
            }
            val cards: JSONArray = root.getJSONArray("cards")
            val n = cards.length()
            val setCodes = arrayOfNulls<String>(n)
            val numbers = arrayOfNulls<String>(n)
            val names = arrayOfNulls<String>(n)
            val ids = arrayOfNulls<String>(n)
            val flags = IntArray(n)
            // Set codes repeat thousands of times; share one String per code.
            val intern = HashMap<String, String>()
            for (i in 0 until n) {
                val c = cards.getJSONArray(i)
                setCodes[i] = c.getString(0).let { intern.getOrPut(it) { it } }
                numbers[i] = c.getString(1)
                names[i] = c.getString(2)
                ids[i] = c.getString(3)
                flags[i] = c.optInt(4)
            }
            @Suppress("UNCHECKED_CAST")
            return CatalogIndex(setCodes as Array<String>, numbers as Array<String>, names as Array<String>, ids as Array<String>, flags, sets)
        }
    }
}
