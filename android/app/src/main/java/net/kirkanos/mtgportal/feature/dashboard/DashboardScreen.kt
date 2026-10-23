package net.kirkanos.mtgportal.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.model.Language
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.DashboardDto
import net.kirkanos.mtgportal.core.network.dto.MoverDto
import net.kirkanos.mtgportal.core.network.dto.MoversDto
import net.kirkanos.mtgportal.core.network.dto.ValuePointDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.Format
import net.kirkanos.mtgportal.core.ui.components.CHART_COLORS
import net.kirkanos.mtgportal.core.ui.components.ChartCard
import net.kirkanos.mtgportal.core.ui.components.ChartItem
import net.kirkanos.mtgportal.core.ui.components.Doughnut
import net.kirkanos.mtgportal.core.ui.components.ErrorRetry
import net.kirkanos.mtgportal.core.ui.components.LineChart
import net.kirkanos.mtgportal.core.ui.components.LineSeries
import net.kirkanos.mtgportal.core.ui.components.RankedBars
import net.kirkanos.mtgportal.core.ui.components.StatGrid
import net.kirkanos.mtgportal.core.ui.components.rarityColor
import net.kirkanos.mtgportal.core.ui.components.rarityLabel
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.di.AppContainer
import java.util.Locale

/** PALETTE in dashboard.js for the five colours plus colorless/multi (colorFor). */
private fun colorOf(key: String): Color = when (key) {
    "W" -> Color(0xFFF0E6C8)
    "U" -> Color(0xFF4FA8E0)
    "B" -> Color(0xFF8A8A94)
    "R" -> Color(0xFFE05656)
    "G" -> Color(0xFF4DBB6A)
    "colorless", "C" -> Color(0xFFC9A24D)
    "multi" -> Color(0xFFB57BFF)
    else -> Color(0xFF7B5CFF)
}

private val GREEN = Color(0xFF4DBB6A)
private val VIOLET = Color(0xFF7B5CFF)
private val BLUE = Color(0xFF4FA8E0)
private val GOLD = Color(0xFFC9A24D)
private val RED = Color(0xFFE05656)

data class DashboardState(
    val dashboard: ApiResult<DashboardDto>? = null,
    val history: List<ValuePointDto> = emptyList(),
    val movers: MoversDto? = null,
    val moversDays: Int = 30,
)

class DashboardViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(dashboard = null) }
        val hist = async { safeCall { c.api.valueHistory() } }
        val mov = async { safeCall { c.api.valueMovers(_state.value.moversDays) } }
        val d = safeCall { c.api.dashboard() }
        _state.update {
            it.copy(
                dashboard = d,
                history = (hist.await() as? ApiResult.Success)?.data.orEmpty(),
                movers = (mov.await() as? ApiResult.Success)?.data,
            )
        }
    }

    fun setMoversDays(days: Int) = viewModelScope.launch {
        _state.update { it.copy(moversDays = days) }
        (safeCall { c.api.valueMovers(days) } as? ApiResult.Success)?.let { r -> _state.update { it.copy(movers = r.data) } }
    }
}

@Composable
fun DashboardScreen() {
    val vm = containerViewModel { DashboardViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    when (val r = st.dashboard) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ApiResult.HttpError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.NetworkError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.Success -> DashboardContent(r.data, st, vm::setMoversDays)
    }
}

private fun cards(v: Double) = Format.count(v.toInt())

@Composable
private fun DashboardContent(d: DashboardDto, st: DashboardState, onMoversDays: (Int) -> Unit) {
    val s = d.stats
    val unknown = stringResource(R.string.db_unknown)
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            val gain = s.marketValue - s.purchaseValue
            StatGrid(
                listOf(
                    Format.count(s.entries) to stringResource(R.string.db_entries),
                    Format.count(s.distinct) to stringResource(R.string.db_distinct),
                    Format.count(s.totalQty) to stringResource(R.string.db_total),
                    s.setCount.toString() to stringResource(R.string.db_editions),
                    s.binderCount.toString() to stringResource(R.string.db_folders),
                    Format.eur(s.purchaseValue) to stringResource(R.string.db_purchase),
                    Format.eur(s.marketValue) to stringResource(R.string.db_market),
                    (if (gain >= 0) "+" else "") + Format.eur(gain) to stringResource(R.string.db_gain),
                    Format.eur(if (s.totalQty > 0) s.purchaseValue / s.totalQty else 0.0) to stringResource(R.string.db_avg),
                    String.format(Locale.GERMANY, "%.1f %%", if (s.totalQty > 0) s.foilQty * 100.0 / s.totalQty else 0.0) to stringResource(R.string.db_foil_share),
                    Format.eur(s.topCardPrice) to stringResource(R.string.db_top_card, s.topCardName.ifEmpty { "–" }),
                ),
            )
        }
        item { HistoryCard(st.history) }
        item { MoversCard(st.movers, st.moversDays, onMoversDays) }
        item {
            ChartCard(stringResource(R.string.db_rarity)) {
                Doughnut(d.rarity.map { ChartItem(rarityLabel(it.key).ifEmpty { unknown }, it.qty.toDouble(), rarityColor(it.key)) }, ::cards)
            }
        }
        item {
            ChartCard(stringResource(R.string.db_colors)) {
                val byKey = d.color.associateBy { it.key }
                RankedBars(COLOR_ORDER.mapNotNull { k -> byKey[k]?.takeIf { it.qty > 0 }?.let { ChartItem(colorName(k), it.qty.toDouble(), colorOf(k)) } }, ::cards)
            }
        }
        item { ChartCard(stringResource(R.string.db_top_sets)) { RankedBars(d.sets.map { ChartItem(it.key, it.qty.toDouble(), GOLD) }, ::cards) } }
        item { ChartCard(stringResource(R.string.db_top_set_value)) { RankedBars(d.setValue.map { ChartItem(it.name.ifEmpty { it.code }, it.value, GREEN) }, Format::eur) } }
        item {
            ChartCard(stringResource(R.string.db_foil)) {
                Doughnut(d.foil.mapIndexed { i, r -> ChartItem(r.key, r.qty.toDouble(), listOf(Color(0xFF9A9CAE), GOLD, VIOLET).getOrElse(i) { CHART_COLORS[i % CHART_COLORS.size] }) }, ::cards)
            }
        }
        item {
            ChartCard(stringResource(R.string.db_purchase_month)) {
                LineChart(d.purchaseByMonth.map { it.month }, listOf(LineSeries(stringResource(R.string.db_purchase), cumulative(d.purchaseByMonth), VIOLET)), Format::eur)
            }
        }
        item { ChartCard(stringResource(R.string.db_top_purchase)) { RankedBars(d.topPurchase.map { ChartItem(it.name, it.value, RED) }, Format::eur, labelWidth = 150) } }
        item { ChartCard(stringResource(R.string.db_top_market)) { RankedBars(d.topMarket.map { ChartItem(it.name, it.value, GREEN) }, Format::eur, labelWidth = 150) } }
        item { ChartCard(stringResource(R.string.db_types)) { RankedBars(d.types.sortedByDescending { it.qty }.map { ChartItem(it.key, it.qty.toDouble(), VIOLET) }, ::cards) } }
        item {
            ChartCard(stringResource(R.string.db_languages)) {
                Doughnut(d.language.mapIndexed { i, r -> ChartItem("${Language.flag(r.key)} ${r.key}", r.qty.toDouble(), CHART_COLORS[i % CHART_COLORS.size]) }, ::cards)
            }
        }
        item { ChartCard(stringResource(R.string.db_condition)) { RankedBars(groupConditions(d.condition).map { (l, q) -> ChartItem(l, q.toDouble(), GOLD) }, ::cards) } }
        item { ChartCard(stringResource(R.string.db_cmc)) { RankedBars(ordered(d.cmc, CMC_ORDER).map { ChartItem(it.key, it.qty.toDouble(), BLUE) }, ::cards, labelWidth = 40) } }
        item {
            ChartCard(stringResource(R.string.db_value_dist)) {
                RankedBars(d.valueDist.mapIndexed { i, n -> ChartItem(VALUE_BUCKETS.getOrElse(i) { "?" }, n.toDouble(), VIOLET) }, ::cards, labelWidth = 96)
            }
        }
        item {
            ChartCard(stringResource(R.string.db_value_color)) {
                val byKey = d.valueByColor.associateBy { it.key }
                RankedBars(COLOR_ORDER.mapNotNull { k -> byKey[k]?.let { ChartItem(colorName(k), it.value, colorOf(k)) } }, Format::eur)
            }
        }
        item { ChartCard(stringResource(R.string.db_value_rarity)) { RankedBars(d.valueByRarity.map { ChartItem(rarityLabel(it.key).ifEmpty { unknown }, it.value, rarityColor(it.key)) }, Format::eur) } }
        item {
            ChartCard(stringResource(R.string.db_added_month)) {
                // Newest first: on a phone the latest months matter, not 2019.
                RankedBars(d.addedPerMonth.reversed().map { ChartItem(it.month, it.qty.toDouble(), GREEN) }, ::cards, labelWidth = 64)
            }
        }
        item { RarityTableCard(d) }
        item { SetTableCard(d) }
    }
}

@Composable
private fun colorName(k: String) = when (k) {
    "W" -> stringResource(R.string.color_w)
    "U" -> stringResource(R.string.color_u)
    "B" -> stringResource(R.string.color_b)
    "R" -> stringResource(R.string.color_r)
    "G" -> stringResource(R.string.color_g)
    "multi" -> stringResource(R.string.color_multi)
    "colorless" -> stringResource(R.string.color_c)
    else -> k
}

@Composable
private fun HistoryCard(history: List<ValuePointDto>) {
    var days by rememberSaveable { mutableIntStateOf(0) }
    ChartCard(stringResource(R.string.db_history)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf(30 to R.string.db_range_30, 90 to R.string.db_range_90, 365 to R.string.db_range_365, 0 to R.string.db_range_all)) { (d, l) ->
                FilterChip(days == d, { days = d }, label = { Text(stringResource(l), fontSize = 12.sp) })
            }
        }
        val series = historyRange(history, days)
        when {
            history.isEmpty() -> Text(stringResource(R.string.db_no_history), color = PortalColors.TextDim, fontSize = 13.sp)
            series.size < 2 -> Text(stringResource(R.string.db_one_point), color = PortalColors.TextDim, fontSize = 13.sp)
            else -> LineChart(
                series.map { it.date },
                listOf(
                    LineSeries(stringResource(R.string.db_legend_market), series.map { it.marketEur }, GREEN),
                    LineSeries(stringResource(R.string.db_legend_purchase), series.map { it.purchaseEur }, VIOLET),
                ),
                Format::eur,
            )
        }
    }
}

@Composable
private fun MoversCard(movers: MoversDto?, days: Int, onDays: (Int) -> Unit) {
    ChartCard(stringResource(R.string.db_movers)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf(7, 30, 90, 365)) { d ->
                FilterChip(days == d, { onDays(d) }, label = {
                    Text(if (d == 365) stringResource(R.string.db_year) else stringResource(R.string.db_days_n, d), fontSize = 12.sp)
                })
            }
        }
        Text(stringResource(R.string.db_gainers), color = GREEN, fontWeight = FontWeight.Medium)
        MoverRows(movers?.gainers.orEmpty())
        Text(stringResource(R.string.db_losers), color = RED, fontWeight = FontWeight.Medium)
        MoverRows(movers?.losers.orEmpty())
    }
}

@Composable
private fun MoverRows(list: List<MoverDto>) {
    if (list.isEmpty()) {
        Text(stringResource(R.string.db_no_movement), color = PortalColors.TextDim, fontSize = 12.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        list.forEach { m ->
            val color = if (m.delta >= 0) GREEN else RED
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name + if (m.quantity > 1) " ×${m.quantity}" else "", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${Format.eur(m.priceThen)} → ${Format.eur(m.priceNow)}", fontSize = 11.sp, color = PortalColors.TextDim)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text((if (m.delta >= 0) "+" else "") + String.format(Locale.GERMANY, "%.0f %%", m.pct), color = color, fontSize = 12.sp)
                    Text((if (m.valueImpact >= 0) "+" else "") + Format.eur(m.valueImpact), color = color, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TableHeader(cols: List<String>) {
    Row {
        cols.forEachIndexed { i, c ->
            Text(c, fontSize = 11.sp, color = PortalColors.TextDim, modifier = if (i == 0) Modifier.weight(1f) else Modifier.width(66.dp))
        }
    }
}

@Composable
private fun RarityTableCard(d: DashboardDto) {
    ChartCard(stringResource(R.string.db_rarity_table)) {
        TableHeader(listOf(stringResource(R.string.db_rarity), stringResource(R.string.db_col_copies), stringResource(R.string.db_market), stringResource(R.string.db_col_share)))
        rarityTable(d.rarityTable).forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rarityLabel(r.key).replaceFirstChar { it.uppercase() }, color = rarityColor(r.key), fontSize = 13.sp)
                    Text("${r.entries} · Ø ${Format.eur(r.avg)}", fontSize = 11.sp, color = PortalColors.TextDim)
                }
                Text(Format.count(r.qty), fontSize = 12.sp, modifier = Modifier.width(66.dp))
                Text(Format.eur(r.market), fontSize = 12.sp, modifier = Modifier.width(66.dp))
                Text(String.format(Locale.GERMANY, "%.1f %%", r.share), fontSize = 12.sp, modifier = Modifier.width(66.dp))
            }
        }
    }
}

@Composable
private fun SetTableCard(d: DashboardDto) {
    ChartCard(stringResource(R.string.db_set_table)) {
        TableHeader(listOf(stringResource(R.string.db_editions), stringResource(R.string.db_col_copies), stringResource(R.string.db_market), stringResource(R.string.db_col_avg)))
        d.setTable.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name.ifEmpty { r.code }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.code} · ${r.entries}", fontSize = 11.sp, color = PortalColors.TextDim)
                }
                Text(Format.count(r.qty), fontSize = 12.sp, modifier = Modifier.width(66.dp))
                Text(Format.eur(r.market), fontSize = 12.sp, modifier = Modifier.width(66.dp))
                Text(Format.eur(if (r.qty > 0) r.market / r.qty else 0.0), fontSize = 12.sp, modifier = Modifier.width(66.dp))
            }
        }
    }
}
