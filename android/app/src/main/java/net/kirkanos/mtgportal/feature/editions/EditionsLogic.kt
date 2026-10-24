package net.kirkanos.mtgportal.feature.editions

import net.kirkanos.mtgportal.core.network.dto.EditionDto
import net.kirkanos.mtgportal.core.network.dto.SetCardDto

/**
 * The editions page's rules (editions.js), unchanged and testable. The
 * aggregation itself (buildEditions) runs on the server since
 * /api/editions; what stays here is what the page does with the result.
 */

/** Set types shown by default (COLLECTABLE_SET_TYPES in editions.js). */
val COLLECTABLE_SET_TYPES = setOf("core", "expansion", "masters", "draft_innovation", "commander", "remastered")

/** The set-type filter: a preset, or a custom choice of types. */
data class TypeFilter(val mode: String = "collectable", val types: Set<String> = emptySet()) {
    /** Stored like localStorage['mtg_editions_type'] on the web, as "mode|a,b,c". */
    fun encode() = mode + "|" + types.sorted().joinToString(",")

    companion object {
        fun decode(s: String?): TypeFilter {
            val (mode, list) = (s ?: "").split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (mode !in setOf("collectable", "all", "custom")) return TypeFilter()
            return TypeFilter(mode, list.split(',').filter { it.isNotEmpty() }.toSet())
        }
    }
}

/** effectiveSelectedTypes(): what the checkboxes show for the current filter. */
fun TypeFilter.effective(allTypes: List<String>): Set<String> = when (mode) {
    "all" -> allTypes.toSet()
    "collectable" -> allTypes.filter { it in COLLECTABLE_SET_TYPES }.toSet()
    else -> types
}

/** renderTable(): type, owned and text filter, then the sort. */
fun filterEditions(list: List<EditionDto>, types: TypeFilter, owned: String, query: String, sort: String): List<EditionDto> {
    val q = query.trim().lowercase()
    return list
        .filter {
            when (types.mode) {
                // Owned sets always show, whatever their type.
                "collectable" -> it.setType in COLLECTABLE_SET_TYPES || it.inCollection
                "custom" -> it.setType in types.types
                else -> true
            }
        }
        .filter { owned == "all" || (owned == "owned") == it.inCollection }
        .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.code.lowercase().contains(q) }
        .let { sortEditions(it, sort) }
}

/** sortEditions() in editions.js, tie-breaker by name everywhere. */
fun sortEditions(list: List<EditionDto>, sort: String): List<EditionDto> {
    val byName = compareBy<EditionDto> { it.name }
    val completion = { e: EditionDto -> if (e.total != null && e.total > 0) e.owned.toDouble() / e.total else -1.0 }
    return list.sortedWith(
        when (sort) {
            "name" -> byName
            "owned-desc" -> compareByDescending<EditionDto> { it.owned }.then(byName)
            "missing-desc" -> compareByDescending<EditionDto> { it.missing ?: -1 }.then(byName)
            "missing-asc" -> compareBy<EditionDto> { it.missing ?: Int.MAX_VALUE }.then(byName)
            "complete-desc" -> compareByDescending(completion).then(byName)
            "dupes-desc" -> compareByDescending<EditionDto> { it.dupeCount }.then(byName)
            "released-desc" -> compareByDescending<EditionDto> { it.releasedAt }.then(byName)
            "released-asc" -> compareBy<EditionDto> { it.releasedAt.ifEmpty { "9999" } }.then(byName)
            else -> compareByDescending<EditionDto> { it.marketValue }.then(byName)
        },
    )
}

data class EditionStats(val collected: Int, val distinct: Int, val complete: Int, val dupeCards: Int)

/** renderStats(): over all editions, not the filtered list - like the web. */
fun editionStats(list: List<EditionDto>): EditionStats {
    val inColl = list.filter { it.inCollection }
    return EditionStats(
        collected = inColl.size,
        distinct = list.sumOf { it.owned },
        complete = inColl.count { it.total != null && it.missing == 0 },
        dupeCards = list.sumOf { it.dupeCount },
    )
}

private val RARITY_ORDER = listOf("common", "uncommon", "rare", "mythic", "special", "bonus")

/** Rarities present in a set, in card order (populateOverlayRarities). */
fun raritiesIn(cards: List<SetCardDto>): List<String> =
    cards.map { it.rarity.lowercase() }.filter { it.isNotEmpty() }.distinct()
        .sortedBy { RARITY_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }

/** cardMarketPrice() of the overlay: normal price, else foil price, else 0. */
fun setCardPrice(c: SetCardDto): Double = c.priceEur?.takeIf { it > 0 } ?: c.priceEurFoil?.takeIf { it > 0 } ?: 0.0

data class OverlayStats(val owned: Int, val total: Int, val missing: Int, val missingValue: Double)

/** overlayStats(): restricted to the chosen rarity. */
fun overlayStats(cards: List<SetCardDto>, rarity: String): OverlayStats {
    val inScope = cards.filter { rarity.isEmpty() || it.rarity.lowercase() == rarity }
    val owned = inScope.count { it.owned != null }
    return OverlayStats(
        owned = owned,
        total = inScope.size,
        missing = inScope.size - owned,
        missingValue = inScope.filter { it.owned == null }.sumOf(::setCardPrice),
    )
}
