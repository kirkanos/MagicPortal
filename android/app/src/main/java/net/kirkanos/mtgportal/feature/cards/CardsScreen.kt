package net.kirkanos.mtgportal.feature.cards

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.model.ImageUrls
import net.kirkanos.mtgportal.core.model.Language
import net.kirkanos.mtgportal.core.model.SetCode
import net.kirkanos.mtgportal.core.network.dto.CardGroupDto
import net.kirkanos.mtgportal.core.network.dto.SetDto
import net.kirkanos.mtgportal.core.ui.Format
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.components.Badge
import net.kirkanos.mtgportal.core.ui.components.CardImage
import net.kirkanos.mtgportal.core.ui.components.ErrorRetry
import net.kirkanos.mtgportal.core.ui.components.SetSymbol
import net.kirkanos.mtgportal.core.ui.components.rarityColor
import net.kirkanos.mtgportal.core.ui.components.rarityLabel
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge as CountBadge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import net.kirkanos.mtgportal.core.ui.icons.AppIconView
import net.kirkanos.mtgportal.core.ui.icons.Icons

/** The sort orders of the web grid, in its order (sort-by in index.html). */
private val SORTS = listOf(
    "name" to R.string.cards_sort_name,
    "set" to R.string.cards_sort_set,
    "value-desc" to R.string.cards_sort_value_desc,
    "value-asc" to R.string.cards_sort_value_asc,
    "price-desc" to R.string.cards_sort_price_desc,
    "price-asc" to R.string.cards_sort_price_asc,
    "rarity" to R.string.cards_sort_rarity,
    "released-desc" to R.string.cards_sort_released_desc,
    "released-asc" to R.string.cards_sort_released_asc,
    "added-desc" to R.string.cards_sort_added_desc,
)

private val COLORS = listOf(
    "W" to R.string.color_w, "U" to R.string.color_u, "B" to R.string.color_b,
    "R" to R.string.color_r, "G" to R.string.color_g,
)

@Composable
fun CardsScreen(binder: String?, set: String?) {
    var openCard by remember { mutableStateOf<CardGroupDto?>(null) }
    val onOpenCard: (CardGroupDto) -> Unit = { openCard = it }
    val vm = containerViewModel { CardsViewModel(it, binder, set) }
    val st by vm.state.collectAsStateWithLifecycle()
    val images by appContainer().config.imageUrls.collectAsStateWithLifecycle()
    var filtersOpen by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()

    // Infinite scroll: the web's IntersectionObserver with a 600 px margin,
    // here "a dozen tiles before the end".
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 12
        }
    }
    LaunchedEffect(nearEnd, st.groups.size) { if (nearEnd) vm.loadMore() }

    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    // Laid out like ManaBox's collection: value up top, one search row, a
    // dense three-column grid.
    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = full) { Header(st, binder, set) }
        item(span = full) {
            Controls(
                st = st,
                onQuery = vm::setQuery,
                onSort = { s -> vm.setFilters { it.copy(sort = s) } },
                onOpenFilters = { filtersOpen = true },
            )
        }
        item(span = full) {
            if (!st.loading && st.error == null) {
                Text(
                    stringResource(
                        R.string.cards_result_count,
                        Format.count(st.total),
                        Format.count(st.facets?.stats?.distinct ?: st.total),
                    ),
                    color = PortalColors.TextDim,
                    fontSize = 13.sp,
                )
            }
        }

        when {
            st.error != null -> item(span = full) { ErrorRetry(st.error!!, onRetry = vm::loadAll) }
            st.loading && st.groups.isEmpty() -> item(span = full) { Loading() }
            st.groups.isEmpty() && st.facets?.stats?.entries == 0 ->
                item(span = full) { Text(stringResource(R.string.cards_collection_empty), color = PortalColors.TextDim) }
            st.groups.isEmpty() ->
                item(span = full) { Text(stringResource(R.string.cards_empty), color = PortalColors.TextDim) }
            else -> items(st.groups, key = { it.key }) { g ->
                CardTile(g, st.sets[SetCode.key(g.setCode)], images, onClick = { onOpenCard(g) })
            }
        }
        if (st.loadingMore) item(span = full) { Loading() }
    }

    openCard?.let { CardDetailSheet(it, onDismiss = { openCard = null }) }

    if (filtersOpen) {
        FilterSheet(st, onChange = vm::setFilters, onReset = vm::resetFilters, onDismiss = { filtersOpen = false })
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Market value large, counts and purchase value small - the collection at a glance. */
@Composable
private fun Header(st: CardsUiState, binder: String?, set: String?) {
    val s = st.facets?.stats
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            binder ?: set?.uppercase() ?: stringResource(R.string.nav_cards),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
        )
        if (s != null) {
            Text(Format.eur(s.marketValue), style = MaterialTheme.typography.titleLarge, color = PortalColors.Accent, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.cards_header_counts, Format.count(s.totalQty), Format.count(s.distinct), Format.eur(s.purchaseValue)),
                fontSize = 13.sp, color = PortalColors.TextDim,
            )
        }
    }
}

/** One row: search, sort menu, filters with the number of active ones. */
@Composable
private fun Controls(st: CardsUiState, onQuery: (String) -> Unit, onSort: (String) -> Unit, onOpenFilters: () -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = st.filters.query,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.cards_search)) },
            leadingIcon = { AppIconView(Icons.Search, PortalColors.TextDim) },
            trailingIcon = {
                if (st.filters.query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) { AppIconView(Icons.Close, PortalColors.TextDim, size = 20.dp) }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = PortalColors.BgCard,
                unfocusedContainerColor = PortalColors.BgCard,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { sortOpen = true }) { AppIconView(Icons.Sort, PortalColors.Text) }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                SORTS.forEach { (k, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label), color = if (k == st.filters.sort) PortalColors.Accent else PortalColors.Text) },
                        onClick = { sortOpen = false; onSort(k) },
                    )
                }
            }
        }
        IconButton(onClick = onOpenFilters) {
            val n = st.filters.activeCount
            BadgedBox(badge = { if (n > 0) CountBadge { Text("$n") } }) { AppIconView(Icons.Filter, PortalColors.Text) }
        }
    }
}

/**
 * A grid tile, slim like ManaBox's: the image with quantity, foil and
 * language marks, the name, and set symbol with price.
 */
@Composable
fun CardTile(g: CardGroupDto, set: SetDto?, images: ImageUrls, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box {
            CardImage(images.card(g.imageId, ImageUrls.Size.SMALL), g.name, Modifier.fillMaxWidth())
            if (g.anyFoil) Badge("Foil", PortalColors.Accent2, foreground = PortalColors.Text, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
            if (g.totalQty > 1) Badge("×${g.totalQty}", PortalColors.Accent, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
            val flags = g.langs.joinToString(" ") { Language.flag(it).ifEmpty { it.uppercase() } }
            if (flags.isNotEmpty()) {
                Text(flags, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
            }
        }
        Text(g.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SetSymbol(images.setIcon(set?.iconSvgUri), g.rarity)
            Text(g.setCode.uppercase(), fontSize = 11.sp, color = rarityColor(g.rarity), modifier = Modifier.weight(1f), maxLines = 1)
            Text(if (g.marketPrice > 0) Format.eur(g.marketPrice) else "–", fontSize = 11.sp, color = PortalColors.Text)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    st: CardsUiState,
    onChange: ((CardFilters) -> CardFilters) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val f = st.filters
    val facets = st.facets
    var setPickerOpen by remember { mutableStateOf(false) }

    // Fully expanded right away: half open, the lower filters were hidden.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Reset on top: at the bottom it sat below the fold on a phone.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cards_filter), modifier = Modifier.weight(1f))
                TextButton(onClick = onReset, enabled = f.activeCount > 0) { Text(stringResource(R.string.cards_filter_reset)) }
            }
            OutlinedButton(onClick = { setPickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                val name = facets?.sets?.firstOrNull { it.code == f.set }?.name
                Text(name?.let { "$it (${f.set})" } ?: stringResource(R.string.cards_all_sets), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Filter(R.string.cards_all_rarities, f.rarity, facets?.rarities.orEmpty().map { it to rarityLabel(it) }) { v -> onChange { it.copy(rarity = v) } }
            val colors = COLORS.filter { (k, _) -> facets?.colors.orEmpty().contains(k) }.map { (k, l) -> k to stringResource(l) } +
                (if (facets?.colorless == true) listOf("C" to stringResource(R.string.color_c)) else emptyList())
            Filter(R.string.cards_all_colors, f.color, colors) { v -> onChange { it.copy(color = v) } }
            Filter(R.string.cards_all_foils, f.foil, listOf("normal" to "Normal", "foil" to "Foil", "etched" to "Etched")) { v -> onChange { it.copy(foil = v) } }
            Filter(R.string.cards_all_binders, f.binder, facets?.binders.orEmpty().map { it to it }) { v -> onChange { it.copy(binder = v) } }
            Filter(R.string.cards_all_types, f.type, facets?.types.orEmpty().map { it to it }) { v -> onChange { it.copy(type = v) } }
            Filter(
                R.string.cards_all_subtypes, f.subtype, st.subtypes.map { it to it },
                enabled = st.subtypes.isNotEmpty(),
            ) { v -> onChange { it.copy(subtype = v) } }
        }
    }

    if (setPickerOpen) {
        SetPickerDialog(
            sets = facets?.sets.orEmpty().map { it.code to it.name },
            setIndex = st.sets,
            onPick = { code -> setPickerOpen = false; onChange { it.copy(set = code) } },
            onDismiss = { setPickerOpen = false },
        )
    }
}

/** A select with an "all" entry first, like the web's selects. */
@Composable
private fun Filter(@StringRes allLabel: Int, selected: String, options: List<Pair<String, String>>, enabled: Boolean = true, onSelect: (String) -> Unit) {
    Dropdown(selected, listOf("" to stringResource(allLabel)) + options, onSelect, Modifier.fillMaxWidth(), enabled)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }, modifier = modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}

/** The web's searchable set dropdown with symbols (populateSetFilter in grid.js). */
@Composable
private fun SetPickerDialog(
    sets: List<Pair<String, String>>,
    setIndex: Map<String, SetDto>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val images by appContainer().config.imageUrls.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    val shown = sets.filter { (code, name) -> q.isBlank() || name.contains(q, true) || code.contains(q, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, placeholder = { Text(stringResource(R.string.cards_search_set)) }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        Text(
                            stringResource(R.string.cards_all_sets),
                            modifier = Modifier.fillMaxWidth().clickable { onPick("") }.padding(vertical = 10.dp),
                        )
                    }
                    items(shown, key = { it.first }) { (code, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(code) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SetSymbol(images.setIcon(setIndex[SetCode.key(code)]?.iconSvgUri), null, 18.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(code, color = PortalColors.TextDim, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
    )
}
