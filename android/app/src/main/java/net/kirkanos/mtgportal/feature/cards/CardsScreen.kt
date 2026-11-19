package net.kirkanos.mtgportal.feature.cards

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.core.data.BinderTarget
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.BinderDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.components.isUnlocked
import net.kirkanos.mtgportal.feature.collection.BinderPickerDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import net.kirkanos.mtgportal.core.network.dto.MoveRowsResultDto
import net.kirkanos.mtgportal.core.network.dto.SelectionRowDto
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
fun CardsScreen(binder: String?, set: String?, onBack: (() -> Unit)? = null) {
    var openCard by remember { mutableStateOf<CardGroupDto?>(null) }
    val onOpenCard: (CardGroupDto) -> Unit = { openCard = it }
    val vm = containerViewModel { CardsViewModel(it, binder, set) }
    val st by vm.state.collectAsStateWithLifecycle()
    val images by appContainer().config.imageUrls.collectAsStateWithLifecycle()
    var filtersOpen by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    // Multi selection to move many cards at once: a long press starts it,
    // taps then toggle. Only with write access. The selection holds the rows
    // of each group, so "all" also covers cards that are not loaded yet.
    val unlocked = isUnlocked()
    var selected by remember { mutableStateOf(emptyMap<String, List<SelectionRowDto>>()) }
    var selecting by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var binders by remember { mutableStateOf<List<BinderDto>>(emptyList()) }
    var moving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    val c = appContainer()

    // Infinite scroll: the web's IntersectionObserver with a 600 px margin,
    // here "a dozen tiles before the end".
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 12
        }
    }
    LaunchedEffect(nearEnd, st.groups.size) { if (nearEnd) vm.loadMore() }
    // A different binder (e.g. after its last cards were moved away) starts
    // at the top; coming back to the screen keeps the position.
    var shownBinder by rememberSaveable { mutableStateOf(st.filters.binder) }
    LaunchedEffect(st.filters.binder) {
        if (st.filters.binder != shownBinder) {
            shownBinder = st.filters.binder
            grid.scrollToItem(0)
        }
    }

    // The binder page names the kind of binder (folder, list, deck).
    LaunchedEffect(st.filters.binder) {
        if (st.filters.binder.isNotEmpty() && binders.none { it.name == st.filters.binder }) {
            (safeCall { c.api.binders() } as? ApiResult.Success)?.let { binders = it.data }
        }
    }
    val binderType = binders.firstOrNull { it.name == st.filters.binder }?.type

    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    // Laid out like ManaBox's collection: value up top, one search row, a
    // dense three-column grid.
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = full) { Header(st, set, binderType, onBack) }
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
                val toggle = {
                    selecting = true
                    selected = if (g.key in selected) selected - g.key else selected + (g.key to rowsOf(g))
                }
                CardTile(
                    g, st.sets[SetCode.key(g.setCode)], images,
                    selected = g.key in selected,
                    onClick = { if (selecting) toggle() else onOpenCard(g) },
                    onLongClick = if (unlocked) toggle else null,
                )
            }
        }
        if (st.loadingMore) item(span = full) { Loading() }
        // Room for the selection bar, so it never covers the last row.
        if (selecting) item(span = full) { Spacer(Modifier.padding(bottom = 64.dp)) }
    }
    if (selecting) {
        val rows = selectedRows(selected, st.filters.binder)
        SelectionBar(
            count = selected.size,
            copies = rows.sumOf { it.second },
            busy = moving,
            allSelected = st.total > 0 && selected.size >= st.total,
            onAll = {
                moving = true
                scope.launch {
                    when (val r = vm.selectionForFilter()) {
                        is ApiResult.Success -> selected = selected + r.data.groups.associate { it.key to it.variants }
                        else -> Toast.makeText(context, res.getString(R.string.cards_select_failed), Toast.LENGTH_LONG).show()
                    }
                    moving = false
                }
            },
            onNone = { selected = emptyMap() },
            onMove = {
                picking = true
                scope.launch { (safeCall { c.api.binders() } as? ApiResult.Success)?.let { binders = it.data } }
            },
            onClose = { selected = emptyMap(); selecting = false },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    }

    if (picking) {
        BinderPickerDialog(binders, BinderTarget(st.filters.binder), onDismiss = { picking = false }, onPick = { target ->
            picking = false
            val ids = selectedRows(selected, st.filters.binder).map { it.first }
            moving = true
            scope.launch {
                val r = c.collectionWrite.moveRows(ids, target)
                moving = false
                if (r is ApiResult.Success) { selected = emptyMap(); selecting = false }
                val text = moveMessage(res, r)
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        })
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
private fun Header(st: CardsUiState, set: String?, binderType: String?, onBack: (() -> Unit)?) {
    val s = st.facets?.stats
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (st.filters.binder.isNotEmpty()) {
            BinderHead(st.filters.binder, binderType, onBack)
        } else {
            Text(
                set?.uppercase() ?: stringResource(R.string.nav_cards),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            )
        }
        if (s != null) {
            Text(Format.eur(s.marketValue), style = MaterialTheme.typography.titleLarge, color = PortalColors.Accent, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.cards_header_counts, Format.count(s.totalQty), Format.count(s.distinct), Format.eur(s.purchaseValue)),
                fontSize = 13.sp, color = PortalColors.TextDim,
            )
        }
    }
}

/**
 * Head of a binder page (binder.html on the web): which binder is open, as
 * a card with an accent edge, so it does not read like the whole collection.
 */
@Composable
private fun BinderHead(name: String, type: String?, onBack: (() -> Unit)?) {
    val (icon, kind) = when (type?.lowercase()) {
        "list" -> Icons.List to R.string.binder_kind_list
        "deck" -> Icons.Planeswalker to R.string.binder_kind_deck
        else -> Icons.Folder to R.string.binder_kind_folder
    }
    Column(Modifier.padding(bottom = 6.dp)) {
        if (onBack != null) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("← " + stringResource(R.string.binder_back), color = PortalColors.TextDim, fontSize = 13.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(12.dp)).background(PortalColors.BgCard),
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(PortalColors.Accent))
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIconView(icon, PortalColors.Accent, size = 30.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        stringResource(kind).uppercase(), color = PortalColors.Accent,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    )
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
            }
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

/** The toast after moving many rows: how many, where, merged, ManaBox warning. */
internal fun moveMessage(res: android.content.res.Resources, r: ApiResult<MoveRowsResultDto>): String = when (r) {
    is ApiResult.Success -> {
        val d = r.data
        val name = d.binderName.ifEmpty { res.getString(R.string.cards_move_no_binder) }
        res.getString(R.string.cards_moved, d.moved, name) +
            (if (d.merged > 0) " " + res.getString(R.string.cards_moved_merged, d.merged) else "") +
            (if (d.volatile) "\n" + res.getString(R.string.cards_moved_volatile) else "")
    }
    is ApiResult.HttpError -> r.message
    is ApiResult.NetworkError -> res.getString(R.string.cards_move_failed)
}

/**
 * The collection rows behind selected tiles, with their copies: in a binder
 * view only the rows in that binder (the tile shows only those), else all.
 */
internal fun selectedRows(selected: Map<String, List<SelectionRowDto>>, binder: String): List<Pair<Long, Int>> =
    selected.values.flatten().filter { it.id != 0L && (binder.isEmpty() || it.binderName == binder) }
        .map { it.id to it.quantity }

internal fun rowsOf(g: CardGroupDto): List<SelectionRowDto> =
    g.variants.map { SelectionRowDto(it.id, it.quantity, it.binderName) }

/**
 * The bar while selecting: close (ends selecting), the count, one button that
 * selects everything or - once everything is selected - nothing, and move.
 */
@Composable
internal fun SelectionBar(
    count: Int,
    copies: Int,
    busy: Boolean,
    allSelected: Boolean,
    onAll: () -> Unit,
    onNone: () -> Unit,
    onMove: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Surface(color = PortalColors.BgCard, shadowElevation = 8.dp, shape = RoundedCornerShape(16.dp), modifier = modifier.padding(10.dp).fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { AppIconView(Icons.Close, PortalColors.Text) }
            Text(stringResource(R.string.cards_selected, count, copies), modifier = Modifier.weight(1f), maxLines = 2, fontSize = 14.sp)
            if (allSelected) {
                TextButton(onClick = onNone, enabled = !busy) { Text(stringResource(R.string.cards_select_none)) }
            } else {
                TextButton(onClick = onAll, enabled = !busy) { Text(stringResource(R.string.cards_select_all)) }
            }
            Button(onClick = onMove, enabled = !busy && copies > 0) { Text(stringResource(R.string.detail_move)) }
        }
    }
}

/**
 * A grid tile, slim like ManaBox's: the image with quantity, foil and
 * language marks, the name, and set symbol with price. Selected tiles get a
 * frame and a check mark; unselected ones dim while selecting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardTile(
    g: CardGroupDto,
    set: SetDto?,
    images: ImageUrls,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.border(3.dp, PortalColors.Accent, RoundedCornerShape(8.dp)).padding(3.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box {
            CardImage(images.card(g.imageId, ImageUrls.Size.SMALL), g.name, Modifier.fillMaxWidth())
            if (g.anyFoil) Badge("Foil", PortalColors.Accent2, foreground = PortalColors.Text, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
            if (g.totalQty > 1) Badge("×${g.totalQty}", PortalColors.Accent, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
            val flags = g.langs.joinToString(" ") { Language.flag(it).ifEmpty { it.uppercase() } }
            if (flags.isNotEmpty()) {
                Text(flags, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
            }
            if (selected) Badge("✓", PortalColors.Accent, modifier = Modifier.align(Alignment.Center))
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
            Filter(R.string.cards_all_styles, f.style, facets?.styles.orEmpty().map { it to styleLabel(it) }) { v -> onChange { it.copy(style = v) } }
            Filter(R.string.cards_all_types, f.type, facets?.types.orEmpty().map { it to it }) { v -> onChange { it.copy(type = v) } }
            Filter(
                R.string.cards_all_subtypes, f.subtype, st.subtypes.map { it to it },
                enabled = st.subtypes.isNotEmpty(),
            ) { v -> onChange { it.copy(subtype = v) } }
        }
    }

    if (setPickerOpen) {
        SetPickerDialog(
            // In a binder view only the sets in that binder (plus the chosen
            // one, so it can be seen and switched off).
            sets = facets?.sets.orEmpty()
                .filter { s -> st.filters.binder.isEmpty() || s.code == st.filters.set || facets?.setsByBinder?.get(st.filters.binder)?.contains(s.code) != false }
                .map { it.code to it.name },
            setIndex = st.sets,
            onPick = { code -> setPickerOpen = false; onChange { it.copy(set = code) } },
            onDismiss = { setPickerOpen = false },
        )
    }
}

/** Labels of the card variants, as on the web (STYLE_LABEL in grid.js). */
@Composable
private fun styleLabel(style: String) = when (style) {
    "fullart" -> "Full Art"
    "borderless" -> stringResource(R.string.cards_style_borderless)
    "showcase" -> "Showcase"
    "extendedart" -> "Extended Art"
    "artseries" -> stringResource(R.string.cards_style_artseries)
    else -> style
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
