package net.kirkanos.mtgportal.feature.reserved

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.ui.components.HelpButton
import net.kirkanos.mtgportal.core.model.ImageUrls
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.ReservedCardDto
import net.kirkanos.mtgportal.core.network.dto.ReservedDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.Format
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.components.Badge
import net.kirkanos.mtgportal.core.ui.components.CardImage
import net.kirkanos.mtgportal.core.ui.components.ErrorRetry
import net.kirkanos.mtgportal.core.ui.components.StatGrid
import net.kirkanos.mtgportal.core.ui.components.WishStar
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.di.AppContainer
import java.util.Locale

class ReservedViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<ApiResult<ReservedDto>?>(null)
    val state: StateFlow<ApiResult<ReservedDto>?> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = null
        _state.value = safeCall { c.api.reserved() }
    }
}

/** Filter and sort of reserved.js renderGrid(), unchanged. ~570 cards, client-side is fine. */
internal fun filterReserved(cards: List<ReservedCardDto>, query: String, owned: String, sort: String): List<ReservedCardDto> {
    val q = query.trim().lowercase()
    val price = { c: ReservedCardDto -> c.cheapestPrice ?: 0.0 }
    return cards
        .filter { q.isEmpty() || it.name.lowercase().contains(q) }
        .filter { owned == "all" || (owned == "owned") == it.owned }
        .sortedWith(
            when (sort) {
                "price-asc" -> compareBy(price).thenBy { it.name }
                "name" -> compareBy { it.name }
                else -> compareByDescending(price).thenBy { it.name }
            },
        )
}

@Composable
fun ReservedScreen() {
    val vm = containerViewModel { ReservedViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val images by appContainer().config.imageUrls.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var owned by rememberSaveable { mutableStateOf("all") }
    var sort by rememberSaveable { mutableStateOf("price-desc") }

    when (val r = st) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ApiResult.HttpError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.NetworkError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.Success -> {
            val s = r.data.summary
            val shown = filterReserved(r.data.cards, query, owned, sort)
            val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = full) {
                    val pct = if (s.total > 0) s.owned * 100.0 / s.total else 0.0
                    StatGrid(
                        listOf(
                            s.total.toString() to stringResource(R.string.reserved_total),
                            s.owned.toString() to stringResource(R.string.reserved_collected),
                            s.missing.toString() to stringResource(R.string.reserved_missing),
                            String.format(Locale.GERMANY, "%.1f %%", pct) to stringResource(R.string.reserved_completion),
                            Format.eur(s.ownedValue) to stringResource(R.string.reserved_collected_value),
                            Format.eur(s.costToComplete) to stringResource(R.string.reserved_cost_to_complete) +
                                (if (s.missingUnpriced > 0) " (" + stringResource(R.string.reserved_unpriced, s.missingUnpriced) + ")" else ""),
                        ),
                    )
                }
                item(span = full) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            query, { query = it }, singleLine = true,
                            placeholder = { Text(stringResource(R.string.reserved_search)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("all" to R.string.reserved_all, "owned" to R.string.reserved_only_owned, "missing" to R.string.reserved_only_missing)
                                .forEach { (k, l) -> FilterChip(owned == k, { owned = k }, label = { Text(stringResource(l), fontSize = 12.sp) }) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("price-desc" to R.string.reserved_sort_price_desc, "price-asc" to R.string.reserved_sort_price_asc, "name" to R.string.reserved_sort_name)
                                .forEach { (k, l) ->
                                    FilterChip(sort == k, { sort = k }, label = {
                                        Text(stringResource(l).substringAfter(": "), fontSize = 12.sp)
                                    })
                                }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                pluralStringResource(R.plurals.reserved_result_count, r.data.cards.size, shown.size, r.data.cards.size),
                                fontSize = 13.sp, color = PortalColors.TextDim, modifier = Modifier.weight(1f),
                            )
                            HelpButton(stringResource(R.string.reserved_hint))
                        }
                    }
                }
                if (shown.isEmpty()) item(span = full) { Text(stringResource(R.string.reserved_empty_filter), color = PortalColors.TextDim) }
                items(shown, key = { it.name }) { card -> ReservedTile(card, images) }
            }
        }
    }
}

@Composable
private fun ReservedTile(c: ReservedCardDto, images: ImageUrls) {
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box {
            // Missing cards are dimmed, like .eo-card.missing on the web.
            CardImage(images.card(c.imageId, ImageUrls.Size.SMALL), c.name, Modifier.fillMaxWidth().alpha(if (c.owned) 1f else 0.45f))
            if (c.owned) {
                Badge("✓", PortalColors.Green, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
                if (c.quantity > 1) Badge("×${c.quantity}", PortalColors.Accent, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
            } else {
                Badge(stringResource(R.string.reserved_missing_tag), PortalColors.Red, foreground = PortalColors.Text, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
            }
            WishStar(c.name, modifier = Modifier.align(Alignment.BottomEnd))
        }
        Text(c.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            when {
                c.owned -> stringResource(R.string.reserved_value, Format.eur(c.ownedValue))
                c.cheapestPrice != null -> stringResource(R.string.reserved_from, Format.eur(c.cheapestPrice))
                else -> stringResource(R.string.reserved_no_price)
            },
            fontSize = 11.sp,
            color = if (c.owned) PortalColors.Green else PortalColors.Accent,
        )
        if (!c.owned && c.cardmarketUri.isNotEmpty()) {
            TextButton(onClick = { uri.openUri(c.cardmarketUri) }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.cardmarket), fontSize = 11.sp)
            }
        }
    }
}
