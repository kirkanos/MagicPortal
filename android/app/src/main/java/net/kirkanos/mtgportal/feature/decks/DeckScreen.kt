package net.kirkanos.mtgportal.feature.decks

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.data.BinderTarget
import net.kirkanos.mtgportal.core.model.Condition
import net.kirkanos.mtgportal.core.model.ImageUrls
import net.kirkanos.mtgportal.core.model.Language
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.BinderDto
import net.kirkanos.mtgportal.core.network.dto.CardGroupDto
import net.kirkanos.mtgportal.core.network.dto.VariantDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.Format
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.components.Badge
import net.kirkanos.mtgportal.core.ui.components.CardImage
import net.kirkanos.mtgportal.core.ui.components.ErrorRetry
import net.kirkanos.mtgportal.core.ui.components.ManaCostRow
import net.kirkanos.mtgportal.core.ui.components.isUnlocked
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.di.AppContainer
import net.kirkanos.mtgportal.feature.collection.BinderPickerDialog

data class DeckState(
    val deck: BinderDto? = null,
    val cards: ApiResult<List<CardGroupDto>>? = null,
    val binders: List<BinderDto> = emptyList(),
)

class DeckViewModel(private val c: AppContainer, private val name: String) : ViewModel() {
    private val _state = MutableStateFlow(DeckState())
    val state: StateFlow<DeckState> = _state.asStateFlow()

    init {
        load()
        // After a write - here, in the scanner or elsewhere - quietly anew.
        viewModelScope.launch { c.collectionWrite.changes.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) = viewModelScope.launch {
        if (!quiet) _state.value = _state.value.copy(cards = null)
        val binders = async { safeCall { c.api.binders() } }
        val cards = safeCall { c.api.cards(binder = name, sort = "name", limit = 500) }
        val bs = (binders.await() as? ApiResult.Success)?.data
        if (quiet && cards !is ApiResult.Success) return@launch
        val groups: ApiResult<List<CardGroupDto>> = when (cards) {
            is ApiResult.Success -> ApiResult.Success(cards.data.groups)
            is ApiResult.HttpError -> cards
            is ApiResult.NetworkError -> cards
        }
        _state.value = DeckState(
            deck = bs?.firstOrNull { it.name == name } ?: _state.value.deck,
            cards = groups,
            binders = bs ?: _state.value.binders,
        )
    }
}

/**
 * One deck, laid out like ManaBox: the cover art on top, then the cards by
 * type with a small art crop, the count and the mana cost - or as a grid of
 * card images. With write access cards come in from the collection (one copy
 * moves out of its binder), from the scanner, and go out into a binder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckScreen(name: String, onScan: () -> Unit) {
    val vm = containerViewModel { DeckViewModel(it, name) }
    val st by vm.state.collectAsStateWithLifecycle()
    val images by appContainer().config.imageUrls.collectAsStateWithLifecycle()
    val unlocked = isUnlocked()
    var view by rememberSaveable { mutableStateOf("list") }
    var open by remember { mutableStateOf<DeckLogic.Entry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var coverPicking by remember { mutableStateOf(false) }
    var formatEditing by remember { mutableStateOf(false) }

    val groups = (st.cards as? ApiResult.Success)?.data.orEmpty()
    when (val r = st.cards) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ApiResult.HttpError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.NetworkError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.Success -> {
            val sections = DeckLogic.sections(groups, st.deck?.commander?.name)
            val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = full) { DeckHeader(name, st.deck, groups, images) }
                item(span = full) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("list" to R.string.deck_view_list, "grid" to R.string.deck_view_grid).forEachIndexed { i, (k, l) ->
                                SegmentedButton(
                                    selected = view == k, onClick = { view = k },
                                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                                ) { Text(stringResource(l)) }
                            }
                        }
                        if (unlocked) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { adding = true }, modifier = Modifier.weight(1f)) { Text("＋ " + stringResource(R.string.deck_add), maxLines = 1) }
                                OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) { Text("📷 " + stringResource(R.string.deck_scan), maxLines = 1) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { formatEditing = true }, enabled = groups.isNotEmpty(), modifier = Modifier.weight(1f)) {
                                    Text("⚙ " + stringResource(R.string.deck_format), maxLines = 1)
                                }
                                OutlinedButton(onClick = { coverPicking = true }, enabled = groups.isNotEmpty(), modifier = Modifier.weight(1f)) {
                                    Text("🖼 " + stringResource(R.string.deck_cover), maxLines = 1)
                                }
                            }
                        }
                    }
                }
                if (groups.isEmpty()) {
                    item(span = full) { Text(stringResource(R.string.deck_empty), color = PortalColors.TextDim, modifier = Modifier.padding(16.dp)) }
                } else if (view == "grid") {
                    // The deck first, its tokens below under their own heading.
                    val (tokens, cards) = groups.partition { DeckLogic.isToken(it.typeLine) }
                    listOf(DeckType.OTHER to cards, DeckType.TOKEN to tokens).forEach { (type, list) ->
                        if (type == DeckType.TOKEN && list.isNotEmpty()) {
                            item(span = full, key = "h-grid-token") { SectionHeader(type, list.sumOf { it.totalQty }) }
                        }
                        items(list, key = { it.key }) { g ->
                            Box(Modifier.padding(horizontal = 4.dp, vertical = 4.dp).clickable { open = DeckLogic.Entry(g.name, listOf(g)) }) {
                                CardImage(images.card(g.imageId, ImageUrls.Size.SMALL), g.name, Modifier.fillMaxWidth())
                                if (g.totalQty > 1) Badge("×${g.totalQty}", PortalColors.Accent, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                            }
                        }
                    }
                } else {
                    sections.forEach { (type, entries) ->
                        item(span = full, key = "h-$type") { SectionHeader(type, entries.sumOf { it.quantity }) }
                        entries.forEach { e ->
                            item(span = full, key = "e-${e.name}") { DeckLine(e, images) { open = e } }
                        }
                    }
                }
            }
        }
    }

    open?.let { e ->
        // The entry anew from the latest list: after a move its rows changed.
        val fresh = groups.filter { it.name == e.name }.let { gs ->
            if (e.groups.size == 1) gs.filter { it.key == e.first.key } else gs
        }
        if (fresh.isEmpty()) {
            open = null
        } else {
            DeckCardSheet(name, st.deck, DeckLogic.Entry(e.name, fresh), images, st.binders, onDismiss = { open = null })
        }
    }
    if (adding) AddCardsSheet(name, images, onDismiss = { adding = false })
    if (coverPicking) CoverDialog(name, st.deck, groups, images, onDismiss = { coverPicking = false })
    if (formatEditing) FormatDialog(name, st.deck, groups, onDismiss = { formatEditing = false })
}

@Composable
private fun DeckHeader(name: String, deck: BinderDto?, groups: List<CardGroupDto>, images: ImageUrls) {
    Box(Modifier.fillMaxWidth().height(180.dp).background(PortalColors.BgElevated)) {
        deck?.cover?.imageId?.let { images.card(it, ImageUrls.Size.ART_CROP) }?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, PortalColors.Bg.copy(alpha = 0.6f), PortalColors.Bg))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            val format = DeckFormat.of(deck?.format.orEmpty())
            if (format != DeckFormat.NONE || deck?.commander != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (format != DeckFormat.NONE) FormatBadge(stringResource(format.label))
                    deck?.commander?.let { Text("👑 ${it.name}", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ManaCostRow(DeckLogic.pips(deck?.colors.orEmpty()), images, size = 16.dp)
                val tokens = groups.filter { DeckLogic.isToken(it.typeLine) }.sumOf { it.totalQty }
                Text(stringResource(R.string.deck_cards, Format.count(groups.sumOf { it.totalQty } - tokens)), color = PortalColors.TextDim, fontSize = 13.sp)
                if (tokens > 0) Text("🪙 " + stringResource(R.string.deck_tokens, Format.count(tokens)), color = PortalColors.TextDim, fontSize = 13.sp)
                Text(Format.eur(DeckLogic.value(groups)), color = PortalColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A line of the list: art crop, count, name, mana cost (ManaBox's deck list). */
@Composable
private fun DeckLine(e: DeckLogic.Entry, images: ImageUrls, onClick: () -> Unit) {
    val g = e.imageGroup
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(width = 52.dp, height = 38.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF0D0E14))) {
            images.card(g.imageId, ImageUrls.Size.ART_CROP)?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text("${e.quantity}", color = PortalColors.TextDim, modifier = Modifier.width(22.dp))
        Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        ManaCostRow(e.first.manaCost.substringBefore("//").trim(), images, size = 15.dp)
    }
}

/** A card of the deck: its rows, take copies out into a binder, use it as the cover. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckCardSheet(deck: String, d: BinderDto?, e: DeckLogic.Entry, images: ImageUrls, binders: List<BinderDto>, onDismiss: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    val unlocked = isUnlocked()
    // Taking out: first how many (for more than one copy), then where to.
    var out by remember { mutableStateOf<Pair<CardGroupDto, VariantDto>?>(null) }
    var outQty by remember { mutableStateOf(1) }
    var picking by remember { mutableStateOf(false) }
    val g0 = e.imageGroup

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardImage(images.card(g0.imageId, ImageUrls.Size.NORMAL), e.name, Modifier.fillMaxWidth(0.62f).align(Alignment.CenterHorizontally))
            Text(e.name, style = MaterialTheme.typography.titleLarge)
            if (g0.typeLine.isNotEmpty()) Text(g0.typeLine, color = PortalColors.TextDim)
            HorizontalDivider()
            e.groups.forEach { g ->
                g.variants.forEach { v ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${g.setCode} #${g.collectorNumber}", fontSize = 13.sp, color = PortalColors.TextDim)
                        Text("${Language.flag(v.language)} ${v.language.uppercase()}", fontSize = 13.sp)
                        if (v.foil.isNotEmpty() && v.foil != "normal") Badge(v.foil, PortalColors.Accent2, foreground = PortalColors.Text)
                        Text(Condition.label(v.condition), fontSize = 13.sp, color = PortalColors.TextDim, modifier = Modifier.weight(1f))
                        Text("×${v.quantity}")
                        if (unlocked && v.id != 0L) {
                            TextButton(onClick = {
                                outQty = v.quantity
                                out = g to v
                                if (v.quantity == 1) picking = true
                            }) { Text(stringResource(R.string.deck_take_out)) }
                        }
                    }
                }
            }
            if (unlocked && g0.imageId.isNotEmpty()) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val r = c.collectionWrite.setDeckCover(deck, g0.setCode, g0.collectorNumber)
                        val msg = if (r is ApiResult.Success) res.getString(R.string.deck_cover_set, e.name) else (r as? ApiResult.HttpError)?.message ?: "–"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }) { Text("🖼 " + stringResource(R.string.deck_cover_use)) }
            }
            val format = DeckFormat.of(d?.format.orEmpty())
            if (unlocked && format.hasCommander) {
                val isCommander = d?.commander?.name == e.name
                // A printing of the card that lies in the deck.
                val printing = e.groups.first()
                OutlinedButton(onClick = {
                    scope.launch {
                        val r = if (isCommander) c.collectionWrite.setDeckSettings(deck, format.key)
                        else c.collectionWrite.setDeckSettings(deck, format.key, printing.setCode, printing.collectorNumber)
                        val msg = when {
                            r !is ApiResult.Success -> (r as? ApiResult.HttpError)?.message ?: "–"
                            isCommander -> res.getString(R.string.deck_saved)
                            else -> res.getString(R.string.deck_commander_set, e.name)
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }) { Text("👑 " + stringResource(if (isCommander) R.string.deck_commander_unset else R.string.deck_commander_make)) }
            }
        }
    }

    val pending = out
    if (pending != null && !picking) {
        // How many: chips 1..n.
        AlertDialog(
            onDismissRequest = { out = null },
            title = { Text(stringResource(R.string.deck_take_out_qty)) },
            text = {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..pending.second.quantity).forEach { n -> FilterChip(outQty == n, { outQty = n }, label = { Text("$n") }) }
                }
            },
            confirmButton = { TextButton(onClick = { picking = true }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { out = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (pending != null && picking) {
        val (g, v) = pending
        BinderPickerDialog(binders.filter { it.name != deck }, BinderTarget(""), onDismiss = { picking = false; out = null }, onPick = { t ->
            picking = false
            out = null
            if (t.name == deck) return@BinderPickerDialog
            scope.launch {
                val r = c.collectionWrite.moveRows(listOf(v.id), t, if (outQty < v.quantity) outQty else 0)
                val msg = when (r) {
                    is ApiResult.Success -> res.getString(R.string.deck_taken_out, outQty, g.name, t.name.ifEmpty { res.getString(R.string.scan_add_no_binder) }) +
                        if (r.data.volatile) " " + res.getString(R.string.deck_volatile) else ""
                    is ApiResult.HttpError -> r.message
                    is ApiResult.NetworkError -> r.cause.message ?: "–"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        })
    }
}

/** Search the collection; "+1" moves one copy of a row into the deck. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCardsSheet(deck: String, images: ImageUrls, onDismiss: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<CardGroupDto, VariantDto>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(q, refresh) {
        if (q.trim().length < 2) { results = null; return@LaunchedEffect }
        delay(300)
        val r = safeCall { c.api.cards(q = q.trim(), sort = "name", limit = 40) }
        if (r is ApiResult.Success) {
            results = r.data.groups.flatMap { g -> g.variants.filter { it.binderName != deck && it.id != 0L }.map { g to it } }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.deck_add_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.deck_add_hint), fontSize = 12.sp, color = PortalColors.TextDim)
            OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text(stringResource(R.string.deck_add_search)) }, modifier = Modifier.fillMaxWidth())
            val list = results
            if (list != null && list.isEmpty()) Text(stringResource(R.string.deck_add_none), color = PortalColors.TextDim, fontSize = 13.sp)
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list.orEmpty(), key = { it.second.id }) { (g, v) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PortalColors.BgCard).padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CardImage(images.card(g.imageId, ImageUrls.Size.SMALL), g.name, Modifier.width(40.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${g.setCode} #${g.collectorNumber} · ${Language.flag(v.language)} ${v.language.uppercase()}" +
                                    (if (v.foil.isNotEmpty() && v.foil != "normal") " · ${v.foil}" else "") +
                                    " · ${v.binderName.ifEmpty { stringResource(R.string.scan_add_no_binder) }} · ×${v.quantity}",
                                fontSize = 12.sp, color = PortalColors.TextDim, maxLines = 2,
                            )
                        }
                        Button(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                val r = c.collectionWrite.moveRows(listOf(v.id), BinderTarget(deck, "deck"), if (v.quantity > 1) 1 else 0)
                                busy = false
                                val msg = when (r) {
                                    is ApiResult.Success -> res.getString(R.string.deck_added, g.name, v.binderName.ifEmpty { res.getString(R.string.scan_add_no_binder) }) +
                                        if (r.data.volatile) " " + res.getString(R.string.deck_volatile) else ""
                                    is ApiResult.HttpError -> r.message
                                    is ApiResult.NetworkError -> r.cause.message ?: "–"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                refresh++
                            }
                        }) { Text("+1") }
                    }
                }
            }
        }
    }
}

/** Every card of the deck as an art crop to pick the cover from. */
@Composable
private fun CoverDialog(deck: String, d: BinderDto?, groups: List<CardGroupDto>, images: ImageUrls, onDismiss: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val choices = groups.filter { it.imageId.isNotEmpty() }.distinctBy { it.imageId }
    fun pick(setCode: String, number: String) = scope.launch {
        c.collectionWrite.setDeckCover(deck, setCode, number)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.deck_cover_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.deck_cover_hint), fontSize = 12.sp, color = PortalColors.TextDim)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 460.dp),
                ) {
                    items(choices, key = { it.imageId }) { g ->
                        val selected = d?.cover?.imageId == g.imageId
                        Column(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .border(2.dp, if (selected) PortalColors.Accent else Color.Transparent, RoundedCornerShape(8.dp))
                                .background(PortalColors.BgCard).clickable { pick(g.setCode, g.collectorNumber) },
                        ) {
                            AsyncImage(
                                model = images.card(g.imageId, ImageUrls.Size.ART_CROP), contentDescription = g.name,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                            )
                            Text(g.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(5.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        dismissButton = {
            if (d?.cover != null && d.coverPicked) {
                TextButton(onClick = { pick("", "") }) {
                    if (d.commander != null) Text(stringResource(R.string.deck_cover_commander))
                    else Text(stringResource(R.string.deck_cover_remove), color = PortalColors.Red)
                }
            }
        },
    )
}

@Composable
internal fun FormatBadge(label: String) {
    Text(
        label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = Color(0xFFE8E4D8),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/** Format of the deck and, for formats with one, its commander. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatDialog(deck: String, d: BinderDto?, groups: List<CardGroupDto>, onDismiss: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var format by remember { mutableStateOf(DeckFormat.of(d?.format.orEmpty())) }
    var commander by remember { mutableStateOf(d?.commander?.name) }
    var busy by remember { mutableStateOf(false) }
    val entries = DeckLogic.sections(groups).flatMap { it.second }
    // Legendary cards can lead a deck; without any, every card is offered.
    val choices = entries.filter { it.first.typeLine.contains("Legendary", ignoreCase = true) }.ifEmpty { entries }.sortedBy { it.name.lowercase() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.deck_format)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // "No format" first, then the formats alphabetical.
                    val labels = DeckFormat.entries.associateWith { stringResource(it.label) }
                    (listOf(DeckFormat.NONE) + DeckFormat.entries.drop(1).sortedBy { labels.getValue(it).lowercase() }).forEach { f ->
                        FilterChip(format == f, { format = f }, label = { Text(labels.getValue(f)) })
                    }
                }
                if (format.hasCommander) {
                    Text("👑 " + stringResource(R.string.deck_commander), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Text(stringResource(R.string.deck_commander_hint), fontSize = 12.sp, color = PortalColors.TextDim)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(commander == null, { commander = null }, label = { Text(stringResource(R.string.deck_commander_none)) })
                        choices.forEach { e -> FilterChip(commander == e.name, { commander = e.name }, label = { Text(e.name) }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val cmd = entries.firstOrNull { it.name == commander }?.first?.takeIf { format.hasCommander }
                    val r = c.collectionWrite.setDeckSettings(deck, format.key, cmd?.setCode.orEmpty(), cmd?.collectorNumber.orEmpty())
                    busy = false
                    if (r is ApiResult.Success) onDismiss()
                    else Toast.makeText(context, (r as? ApiResult.HttpError)?.message ?: "–", Toast.LENGTH_LONG).show()
                }
            }) { Text(stringResource(R.string.deck_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun SectionHeader(type: DeckType, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(type.label), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("$count", color = PortalColors.TextDim)
    }
}
