package net.kirkanos.mtgportal.feature.decks

import net.kirkanos.mtgportal.core.network.dto.CardGroupDto
import org.junit.Assert.assertEquals
import org.junit.Test

class DeckLogicTest {
    @Test
    fun typeOfFollowsTheFrontFace() {
        assertEquals(DeckType.CREATURE, DeckLogic.typeOf("Artifact Creature — Golem"))
        assertEquals(DeckType.LAND, DeckLogic.typeOf("Artifact Land"))
        assertEquals(DeckType.INSTANT, DeckLogic.typeOf("Instant // Sorcery"))
        assertEquals(DeckType.CREATURE, DeckLogic.typeOf("Creature — Human // Land"))
        assertEquals(DeckType.ENCHANTMENT, DeckLogic.typeOf("Enchantment — Aura"))
        assertEquals(DeckType.OTHER, DeckLogic.typeOf(""))
    }

    @Test
    fun sectionsMergePrintingsAndKeepTheOrder() {
        val gs = listOf(
            CardGroupDto(key = "a", name = "Swamp", typeLine = "Basic Land — Swamp", totalQty = 4),
            CardGroupDto(key = "b", name = "Swamp", typeLine = "Basic Land — Swamp", totalQty = 3, imageId = "x1"),
            CardGroupDto(key = "c", name = "Vampire Champion", typeLine = "Creature — Vampire", totalQty = 4),
            CardGroupDto(key = "d", name = "Impale", typeLine = "Sorcery", totalQty = 2),
        )
        val s = DeckLogic.sections(gs)
        assertEquals(listOf(DeckType.CREATURE, DeckType.SORCERY, DeckType.LAND), s.map { it.first })
        val swamp = s.last().second.single()
        assertEquals(7, swamp.quantity)
        assertEquals("x1", swamp.imageGroup.imageId)
    }

    @Test
    fun pips() {
        assertEquals("{C}", DeckLogic.pips(emptyList()))
        assertEquals("{W}{B}", DeckLogic.pips(listOf("W", "B")))
    }
}
