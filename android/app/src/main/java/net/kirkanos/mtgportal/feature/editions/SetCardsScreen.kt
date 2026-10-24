package net.kirkanos.mtgportal.feature.editions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.model.ImageUrls
import net.kirkanos.mtgportal.core.model.Language
import net.kirkanos.mtgportal.core.model.SetCode
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.SetCardDto
import net.kirkanos.mtgportal.core.network.dto.SetDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.Format
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.components.Badge
import net.kirkanos.mtgportal.core.ui.components.CardImage
import net.kirkanos.mtgportal.core.ui.components.ErrorRetry
import net.kirkanos.mtgportal.core.ui.components.WishStar
import net.kirkanos.mtgportal.core.ui.components.rarityLabel
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.di.AppContainer

data class SetCardsState(val cards: ApiResult<List<SetCardDto>>? = null, val set: SetDto? = null)

class SetCardsViewModel(private val c: AppContainer, private val code: String) : ViewModel() {
    private val _state = MutableStateFlow(SetCardsState())
    val state: StateFlow<SetCardsState> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = SetCardsState()
        val sets = async { safeCall { c.api.sets() } }
        val cards = safeCall { c.api.setCards(SetCode.key(code)) }
        val set = (sets.await() as? ApiResult.Success)?.data?.get(SetCode.key(code))
        _state.value = SetCardsState(cards, set)
    }
}

/**
 * The edition overlay of the web (openEditionOverlay in editions.js), as a
 * page of its own: every card of the set, owned ones normal, missing ones
 * greyed out. Ownership comes with the cards since /api/sets/{code}/cards
 * carries it - the collection is never loaded.
 */
@Composable
fun SetCardsScreen(code: String) {
    val vm = containerViewModel { SetCardsViewModel(it, code) }
    val st by vm.state.collectAsStateWithLifecycle()
    val images by appContainer().config.imageUrls.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("all") }
    var rarity by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<SetCardDto?>(null) }

    when (val r = st.cards) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ApiResult.HttpError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.NetworkError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.Success -> {
            val cards = r.data
            val stats = overlayStats(cards, rarity)
            val shown = cards.filter {
                (rarity.isEmpty() || it.rarity.lowercase() == rarity) &&
                    when (filter) { "owned" -> it.owned != null; "missing" -> it.owned == null; else -> true }
            }
            val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = full) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            images.setIcon(st.set?.iconSvgUri)?.let {
                                AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(26.dp),
                                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(PortalColors.Text))
                            }
                            Text(st.set?.name ?: code, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Text(SetCode.display(code), color = PortalColors.TextDim)
                        }
                        val pct = if (stats.total > 0) Math.round(stats.owned * 100f / stats.total) else 0
                        Text(stringResource(R.string.eo_sub, stats.owned, stats.total, pct, stats.missing, Format.eur(stats.missingValue)), fontSize = 13.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("all" to R.string.eo_all, "owned" to R.string.eo_only_owned, "missing" to R.string.eo_only_missing)) { (k, l) ->
                                FilterChip(filter == k, { filter = k }, label = { Text(stringResource(l), fontSize = 12.sp) })
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item { FilterChip(rarity.isEmpty(), { rarity = "" }, label = { Text(stringResource(R.string.cards_all_rarities), fontSize = 12.sp) }) }
                            items(raritiesIn(cards)) { rar -> FilterChip(rarity == rar, { rarity = rar }, label = { Text(rarityLabel(rar), fontSize = 12.sp) }) }
                        }
                    }
                }
                if (shown.isEmpty()) item(span = full) { Text(stringResource(R.string.reserved_empty_filter), color = PortalColors.TextDim) }
                items(shown, key = { it.collectorNumber }) { c -> SetCardTile(c, images) { open = c } }
            }
        }
    }

    open?.let { SetCardDetailSheet(it, images, onDismiss = { open = null }) }
}

@Composable
private fun SetCardTile(c: SetCardDto, images: ImageUrls, onClick: () -> Unit) {
    val owned = c.owned
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box {
            CardImage(images.card(c.imageId, ImageUrls.Size.SMALL), c.name, Modifier.fillMaxWidth().alpha(if (owned != null) 1f else 0.45f))
            if (owned != null) {
                Badge("✓", PortalColors.Green, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
                if (owned.copies > 1) Badge("×${owned.copies}", PortalColors.Accent, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
            } else {
                Badge(stringResource(R.string.reserved_missing_tag), PortalColors.Red, foreground = PortalColors.Text, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
            }
            WishStar(c.name, modifier = Modifier.align(Alignment.BottomEnd))
        }
        Text("#${c.collectorNumber} ${c.name}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** editionCardDetailHTML(): image with flip, number, rarity, prices, Cardmarket, ownership. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetCardDetailSheet(c: SetCardDto, images: ImageUrls, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    var back by remember(c.collectorNumber) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val face = if (back) ImageUrls.Face.BACK else ImageUrls.Face.FRONT
                CardImage(images.card(c.imageId, ImageUrls.Size.NORMAL, face, hasBack = c.hasBack), c.name, Modifier.fillMaxWidth(0.72f))
                if (c.hasBack) TextButton(onClick = { back = !back }) { Text(stringResource(R.string.detail_flip)) }
            }
            Text(c.name, style = MaterialTheme.typography.titleLarge)
            if (c.typeLine.isNotEmpty()) Text(c.typeLine, color = PortalColors.TextDim)
            HorizontalDivider()
            Fact(stringResource(R.string.eo_number), "#${c.collectorNumber}")
            Fact(stringResource(R.string.detail_rarity), rarityLabel(c.rarity))
            Fact(stringResource(R.string.eo_market), c.priceEur?.let(Format::eur) ?: "–")
            c.priceEurFoil?.let { Fact(stringResource(R.string.eo_market_foil), Format.eur(it)) }
            if (c.cardmarketUri.isNotEmpty()) {
                TextButton(onClick = { uri.openUri(c.cardmarketUri) }) { Text(stringResource(R.string.eo_view_cardmarket)) }
            }
            WishStar(c.name, label = stringResource(R.string.detail_wishlist))
            val owned = c.owned
            if (owned == null) {
                Text(stringResource(R.string.eo_missing_card), color = PortalColors.TextDim)
            } else {
                Text(stringResource(R.string.eo_in_collection, owned.copies), style = MaterialTheme.typography.titleSmall)
                owned.byLang.entries.sortedByDescending { it.value }.forEach { (lang, n) ->
                    Row {
                        Text("${Language.flag(lang)} ${lang.uppercase()}", modifier = Modifier.width(120.dp))
                        Text("$n")
                    }
                }
                if (owned.byBinder.isNotEmpty()) {
                    val binders = owned.byBinder.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} (${it.value})" }
                    Text(stringResource(R.string.eo_folder, binders), fontSize = 13.sp, color = PortalColors.TextDim)
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Text(label, color = PortalColors.TextDim, modifier = Modifier.width(150.dp))
        Text(value, fontWeight = FontWeight.Medium)
    }
}
