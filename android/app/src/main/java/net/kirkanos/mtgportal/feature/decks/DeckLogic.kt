package net.kirkanos.mtgportal.feature.decks

import androidx.annotation.StringRes
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.network.dto.CardGroupDto

/** The groups of the deck list, in this order (decks.js DECK_TYPES). */
enum class DeckType(@StringRes val label: Int) {
    CREATURE(R.string.deck_type_creature),
    PLANESWALKER(R.string.deck_type_planeswalker),
    BATTLE(R.string.deck_type_battle),
    INSTANT(R.string.deck_type_instant),
    SORCERY(R.string.deck_type_sorcery),
    ARTIFACT(R.string.deck_type_artifact),
    ENCHANTMENT(R.string.deck_type_enchantment),
    LAND(R.string.deck_type_land),
    OTHER(R.string.deck_type_other),
}

object DeckLogic {
    /**
     * The group of a card: the front face decides, creatures before artifacts
     * (an artifact creature is a creature), lands before artifacts.
     */
    fun typeOf(typeLine: String): DeckType {
        val front = typeLine.substringBefore("//").lowercase()
        return listOf(
            "creature" to DeckType.CREATURE, "planeswalker" to DeckType.PLANESWALKER, "battle" to DeckType.BATTLE,
            "instant" to DeckType.INSTANT, "sorcery" to DeckType.SORCERY, "land" to DeckType.LAND,
            "artifact" to DeckType.ARTIFACT, "enchantment" to DeckType.ENCHANTMENT,
        ).firstOrNull { front.contains(it.first) }?.second ?: DeckType.OTHER
    }

    /** One entry per card name, whatever printings it comes in. */
    data class Entry(val name: String, val groups: List<CardGroupDto>) {
        val first get() = groups.first()
        val quantity get() = groups.sumOf { it.totalQty }
        /** The art for the list: the first printing that has an image. */
        val imageGroup get() = groups.firstOrNull { it.imageId.isNotEmpty() } ?: first
    }

    fun sections(groups: List<CardGroupDto>): List<Pair<DeckType, List<Entry>>> {
        val entries = groups.groupBy { it.name }.map { (name, gs) -> Entry(name, gs) }
        return entries.groupBy { typeOf(it.first.typeLine) }
            .toSortedMap(compareBy { it.ordinal })
            .map { (type, list) -> type to list.sortedBy { it.name.lowercase() } }
    }

    /** "{W}{B}" for the colour pips; colourless decks show {C}. */
    fun pips(colors: List<String>): String = if (colors.isEmpty()) "{C}" else colors.joinToString("") { "{$it}" }

    fun value(groups: List<CardGroupDto>): Double =
        groups.sumOf { g -> g.variants.sumOf { (it.marketPrice ?: 0.0) * it.quantity } }
}
