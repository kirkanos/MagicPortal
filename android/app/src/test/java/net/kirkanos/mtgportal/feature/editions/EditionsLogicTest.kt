package net.kirkanos.mtgportal.feature.editions

import net.kirkanos.mtgportal.core.network.dto.EditionDto
import net.kirkanos.mtgportal.core.network.dto.SetCardDto
import net.kirkanos.mtgportal.core.network.dto.SetCardOwnedDto
import org.junit.Assert.assertEquals
import org.junit.Test

// The same rules as editions.js.
class EditionsLogicTest {
    private val eds = listOf(
        EditionDto("SNC", "Streets of New Capenna", setType = "expansion", releasedAt = "2022-04-29", owned = 227, total = 467, missing = 240, dupeCount = 31, marketValue = 61.0, inCollection = true),
        EditionDto("LEA", "Limited Edition Alpha", setType = "core", releasedAt = "1993-08-05", owned = 0, total = 295, missing = 295, marketValue = 0.0, inCollection = false),
        EditionDto("PLST", "The List", setType = "memorabilia", releasedAt = "2020-07-08", owned = 4, total = 4, missing = 0, dupeCount = 1, marketValue = 2.0, inCollection = true),
        EditionDto("TFOO", "Token Foo", setType = "token", releasedAt = "", owned = 0, total = null, missing = null, inCollection = false),
    )

    private fun codes(types: TypeFilter = TypeFilter(), owned: String = "all", q: String = "", sort: String = "name") =
        filterEditions(eds, types, owned, q, sort).map { it.code }

    @Test
    fun collectablePresetKeepsOwnedSetsOfOtherTypes() =
        assertEquals(listOf("LEA", "SNC", "PLST"), codes())

    @Test
    fun allAndCustom() {
        assertEquals(4, codes(TypeFilter("all")).size)
        assertEquals(listOf("TFOO"), codes(TypeFilter("custom", setOf("token"))))
        assertEquals(emptyList<String>(), codes(TypeFilter("custom")))
    }

    @Test
    fun ownedFilterAndSearchByNameOrCode() {
        assertEquals(listOf("SNC", "PLST"), codes(owned = "owned"))
        assertEquals(listOf("LEA"), codes(owned = "missing"))
        assertEquals(listOf("PLST"), codes(q = "plst"))
        assertEquals(listOf("LEA"), codes(q = "alpha"))
    }

    @Test
    fun sorts() {
        val all = TypeFilter("all")
        // Equal value (0 €) ties by name.
        assertEquals(listOf("SNC", "PLST", "LEA", "TFOO"), codes(all, sort = "value-desc"))
        // Unknown size sorts last on "fewest missing", first-but-one on "most missing".
        assertEquals("TFOO", codes(all, sort = "missing-asc").last())
        assertEquals(listOf("LEA", "SNC", "PLST", "TFOO"), codes(all, sort = "missing-desc"))
        // Completion: unknown size counts as -1.
        assertEquals(listOf("PLST", "SNC", "LEA", "TFOO"), codes(all, sort = "complete-desc"))
        // No release date: last when ascending.
        assertEquals("TFOO", codes(all, sort = "released-asc").last())
        assertEquals(listOf("SNC", "PLST", "LEA", "TFOO"), codes(all, sort = "released-desc"))
    }

    @Test
    fun statsCountAllEditions() =
        assertEquals(EditionStats(collected = 2, distinct = 231, complete = 1, dupeCards = 32), editionStats(eds))

    @Test
    fun typeFilterRoundTrip() {
        val f = TypeFilter("custom", setOf("token", "core"))
        assertEquals(f, TypeFilter.decode(f.encode()))
        assertEquals(TypeFilter(), TypeFilter.decode(null))
        assertEquals(TypeFilter(), TypeFilter.decode("garbage"))
        assertEquals(setOf("core", "expansion"), TypeFilter().effective(listOf("core", "expansion", "token")))
    }

    @Test
    fun overlay() {
        val owned = SetCardOwnedDto(copies = 2)
        val cards = listOf(
            SetCardDto("1", "A", rarity = "rare", priceEur = 5.0, owned = owned),
            SetCardDto("2", "B", rarity = "rare", priceEur = null, priceEurFoil = 3.0),
            SetCardDto("3", "C", rarity = "common", priceEur = 0.1),
            SetCardDto("4", "D", rarity = "mythic"),
        )
        assertEquals(OverlayStats(owned = 1, total = 4, missing = 3, missingValue = 3.1), overlayStats(cards, "").let { it.copy(missingValue = Math.round(it.missingValue * 100) / 100.0) })
        assertEquals(OverlayStats(owned = 1, total = 2, missing = 1, missingValue = 3.0), overlayStats(cards, "rare"))
        assertEquals(listOf("common", "rare", "mythic"), raritiesIn(cards))
    }
}
