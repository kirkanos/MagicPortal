package net.kirkanos.mtgportal.feature.cards

import net.kirkanos.mtgportal.core.network.dto.CollectionRowDto
import net.kirkanos.mtgportal.core.network.dto.VariantDto

/**
 * The detail sheet's variants after a write, without reloading the card:
 * [removed] rows go, [row] (the row as the server now has it) replaces the
 * one with its id or is added. The market price is taken over from a variant
 * with the same finish - the price depends on foil, not on binder,
 * language or condition.
 */
fun applyWrite(variants: List<VariantDto>, row: CollectionRowDto?, removed: Set<Long> = emptySet()): List<VariantDto> {
    val kept = variants.filter { it.id !in removed }
    if (row == null) return kept
    val price = variants.firstOrNull { it.foil == row.foil }?.marketPrice
    val existing = kept.firstOrNull { it.id == row.id }
    // A row whose finish changed takes the price of that finish, if known.
    val base = when {
        existing == null -> VariantDto(added = "", marketPrice = price)
        existing.foil != row.foil -> existing.copy(marketPrice = price.takeIf { variants.any { it.foil == row.foil } })
        else -> existing
    }
    val updated = base.copy(
        id = row.id,
        origin = row.origin,
        language = row.language,
        foil = row.foil,
        condition = row.condition,
        quantity = row.quantity,
        binderName = row.binderName,
        binderType = row.binderType,
    )
    return if (existing != null) kept.map { if (it.id == row.id) updated else it } else kept + updated
}
