package net.kirkanos.mtgportal.feature.decks

import androidx.annotation.StringRes
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.network.dto.CardGroupDto

/** The groups of the deck list, in this order (decks.js DECK_TYPES). */
enum class DeckType(@StringRes val label: Int) {
    COMMANDER(R.string.deck_type_commander),
    CREATURE(R.string.deck_type_creature),
    PLANESWALKER(R.string.deck_type_planeswalker),
    BATTLE(R.string.deck_type_battle),
    INSTANT(R.string.deck_type_instant),
    SORCERY(R.string.deck_type_sorcery),
    ARTIFACT(R.string.deck_type_artifact),
    ENCHANTMENT(R.string.deck_type_enchantment),
    LAND(R.string.deck_type_land),
    OTHER(R.string.deck_type_other),
    TOKEN(R.string.deck_type_token),
}

/** Deck formats (decks.js DECK_FORMATS); "" is a deck without a format. */
enum class DeckFormat(val key: String, @StringRes val label: Int, val hasCommander: Boolean = false) {
    NONE("", R.string.deck_format_none),
    STANDARD("standard", R.string.deck_format_standard),
    PIONEER("pioneer", R.string.deck_format_pioneer),
    MODERN("modern", R.string.deck_format_modern),
    LEGACY("legacy", R.string.deck_format_legacy),
    VINTAGE("vintage", R.string.deck_format_vintage),
    PAUPER("pauper", R.string.deck_format_pauper),
    COMMANDER("commander", R.string.deck_format_commander, true),
    BRAWL("brawl", R.string.deck_format_brawl, true),
    OATHBREAKER("oathbreaker", R.string.deck_format_oathbreaker, true),
    ;

    companion object {
        fun of(key: String): DeckFormat = entries.firstOrNull { it.key == key.lowercase() } ?: NONE
    }
}

object DeckLogic {
    private val tokenWord = Regex("\\btoken\\b")

    /** Tokens and emblems are not part of the deck; they are listed on their own. */
    fun isToken(typeLine: String): Boolean {
        val front = typeLine.substringBefore("//").trim().lowercase()
        return tokenWord.containsMatchIn(front) || front.startsWith("emblem")
    }

    /**
     * The group of a card: tokens apart, then the front face decides, creatures
     * before artifacts (an artifact creature is a creature), lands before artifacts.
     */
    fun typeOf(typeLine: String): DeckType {
        if (isToken(typeLine)) return DeckType.TOKEN
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

    /** The sections of the list: the commander first, then the card types. */
    fun sections(groups: List<CardGroupDto>, commander: String? = null): List<Pair<DeckType, List<Entry>>> {
        val entries = groups.groupBy { it.name }.map { (name, gs) -> Entry(name, gs) }
        return entries.groupBy { if (it.name == commander) DeckType.COMMANDER else typeOf(it.first.typeLine) }
            .toSortedMap(compareBy { it.ordinal })
            .map { (type, list) -> type to list.sortedBy { it.name.lowercase() } }
    }

    /**
     * A deck shows in a search when its name holds every term, or the server
     * found a card in it that matches (name, type line, rules text).
     */
    fun matches(deckName: String, query: String, hasCardHit: Boolean): Boolean {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val name = deckName.lowercase()
        return hasCardHit || terms.all { name.contains(it) }
    }

    /** "{W}{B}" for the colour pips; colourless decks show {C}. */
    fun pips(colors: List<String>): String = if (colors.isEmpty()) "{C}" else colors.joinToString("") { "{$it}" }

    fun value(groups: List<CardGroupDto>): Double =
        groups.sumOf { g -> g.variants.sumOf { (it.marketPrice ?: 0.0) * it.quantity } }
}
