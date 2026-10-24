package net.kirkanos.mtgportal.feature.dashboard

import net.kirkanos.mtgportal.core.model.Condition
import net.kirkanos.mtgportal.core.network.dto.KeyQty
import net.kirkanos.mtgportal.core.network.dto.MonthValue
import net.kirkanos.mtgportal.core.network.dto.RarityRowDto
import net.kirkanos.mtgportal.core.network.dto.ValuePointDto
import java.time.LocalDate

/** The statistics page's data rules (dashboard.js), separate from drawing. */

/** COLOR_ORDER in dashboard.js: fixed, independent of the data order. */
val COLOR_ORDER = listOf("W", "U", "B", "R", "G", "multi", "colorless")

/** Mana value buckets in their natural order. */
val CMC_ORDER = listOf("0", "1", "2", "3", "4", "5", "6", "7+")

/** VALUE_BUCKETS in dashboard.js; valueDist has one count per bucket. */
val VALUE_BUCKETS = listOf("< 0,10 €", "0,10–0,50 €", "0,50–1 €", "1–5 €", "5–20 €", "20–100 €", "> 100 €")

/** renderValueOverTimeChart(): the purchase value per month, summed up. */
fun cumulative(months: List<MonthValue>): List<Double> {
    var sum = 0.0
    return months.map { sum += it.value; sum }
}

/**
 * groupByLabel(rows, conditionLabel): merges keys that share a label, so
 * light_played and lightly_played end up in one bar, sorted descending.
 */
fun groupConditions(rows: List<KeyQty>): List<Pair<String, Int>> =
    rows.groupBy { Condition.label(it.key) }
        .map { (label, list) -> label to list.sumOf { it.qty } }
        .sortedByDescending { it.second }

/** Keeps only buckets that have cards, in their fixed order. */
fun ordered(rows: List<KeyQty>, order: List<String>): List<KeyQty> {
    val byKey = rows.associateBy { it.key }
    return order.mapNotNull { k -> byKey[k]?.takeIf { it.qty > 0 } }
}

/** The 30/90/365/all range of the value history; days = 0 means everything. */
fun historyRange(points: List<ValuePointDto>, days: Int, today: LocalDate = LocalDate.now()): List<ValuePointDto> {
    if (days <= 0) return points
    val cutoff = today.minusDays(days.toLong()).toString()
    return points.filter { it.date >= cutoff }
}

data class RarityTableRow(val key: String, val entries: Int, val qty: Int, val market: Double, val avg: Double, val share: Double)

/** renderRarityTable(): known rarities first in their order, the rest after. */
fun rarityTable(rows: List<RarityRowDto>): List<RarityTableRow> {
    val order = listOf("common", "uncommon", "rare", "mythic", "special")
    val total = rows.sumOf { it.market }
    val byKey = rows.associateBy { it.key.ifEmpty { "—" } }
    val keys = order.filter { it in byKey } + byKey.keys.filter { it !in order }
    return keys.map { k ->
        val r = byKey.getValue(k)
        RarityTableRow(
            key = k,
            entries = r.entries,
            qty = r.qty,
            market = r.market,
            avg = if (r.qty > 0) r.market / r.qty else 0.0,
            share = if (total > 0) r.market / total * 100 else 0.0,
        )
    }
}
