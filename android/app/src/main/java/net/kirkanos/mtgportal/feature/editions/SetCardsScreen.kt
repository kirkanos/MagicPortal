package net.kirkanos.mtgportal.feature.editions

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.data.BinderTarget
import net.kirkanos.mtgportal.core.model.Condition
import net.kirkanos.mtgportal.core.model.ImageUrls
import net.kirkanos.mtgportal.core.model.Language
import net.kirkanos.mtgportal.core.model.SetCode
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.BinderDto
import net.kirkanos.mtgportal.core.network.dto.RowPatchDto
import net.kirkanos.mtgportal.core.network.dto.SetCardDto
import net.kirkanos.mtgportal.core.network.dto.VariantDto
import net.kirkanos.mtgportal.core.network.dto.SetDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.Format
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.components.Badge
import net.kirkanos.mtgportal.core.ui.components.CardImage
import net.kirkanos.mtgportal.core.ui.components.ErrorRetry
import net.kirkanos.mtgportal.core.ui.components.WishStar
import net.kirkanos.mtgportal.core.ui.components.isUnlocked
import net.kirkanos.mtgportal.core.ui.components.rarityLabel
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.di.AppContainer
import net.kirkanos.mtgportal.feature.cards.SelectionBar
import net.kirkanos.mtgportal.feature.cards.moveMessage
import net.kirkanos.mtgportal.feature.collection.BinderPickerDialog

data class SetCardsState(val cards: ApiResult<List<SetCardDto>>? = null, val set: SetDto? = null)

class SetCardsViewModel(private val c: AppContainer, private val code: String) : ViewModel() {
    private val _state = MutableStateFlow(SetCardsState())
    val state: StateFlow<SetCardsState> = _state.asStateFlow()

    init {
        load()
        // After an edit - here or elsewhere - the ownership anew, without the
        // spinner: the grid and an open detail stay where they are.
        viewModelScope.launch { c.collectionWrite.changes.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) = viewModelScope.launch {
        if (!quiet) _state.value = SetCardsState()
        val sets = async { safeCall { c.api.sets() } }
        val cards = safeCall { c.api.setCards(SetCode.key(code)) }
        val set = (sets.await() as? ApiResult.Success)?.data?.get(SetCode.key(code))
        if (quiet && cards !is ApiResult.Success) return@launch
        _state.value = SetCardsState(cards, set ?: _state.value.set)
    }
}

/**
 * The edition overlay of the web (openEditionOverlay in editions.js), as a
 * page of its own: every card of the set, owned ones normal, missing ones
 * greyed out. Ownership comes with the cards since /api/sets/{code}/cards
 * carries it - the collection is never loaded.
 *
 * With write access the copies can be edited like in the card grid: a long
 * press selects owned cards to move them together, the detail has -1, +1
 * and move per row, and adds copies - also of cards still missing.
 */
@Composable
fun SetCardsScreen(code: String) {
    val vm = containerViewModel { SetCardsViewModel(it, code) }
    val st by vm.state.collectAsStateWithLifecycle()
    val images by appContainer().config.imageUrls.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("all") }
    var rarity by rememberSaveable { mutableStateOf("") }
    // The open card by number: after an edit the fresh list brings its new rows.
    var open by remember { mutableStateOf<String?>(null) }
    val unlocked = isUnlocked()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var selecting by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var binders by remember { mutableStateOf<List<BinderDto>>(emptyList()) }
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    val allCards = (st.cards as? ApiResult.Success)?.data.orEmpty()

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
            Box(Modifier.fillMaxSize()) {
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
                items(shown, key = { it.collectorNumber }) { card ->
                    val n = card.collectorNumber
                    val toggle = {
                        selecting = true
                        selected = if (n in selected) selected - n else selected + n
                    }
                    SetCardTile(
                        card, images,
                        selected = n in selected,
                        onClick = { if (selecting) { if (card.owned != null) toggle() } else open = n },
                        // Only owned cards have rows to move.
                        onLongClick = if (unlocked && card.owned != null) toggle else null,
                    )
                }
                if (selecting) item(span = full) { Spacer(Modifier.padding(bottom = 64.dp)) }
            }
            if (selecting) {
                // What "all" means here: the owned cards the filter shows.
                val ownedShown = shown.filter { it.owned != null }.map { it.collectorNumber }.toSet()
                val rows = allCards.filter { it.collectorNumber in selected }.flatMap { it.owned?.rows.orEmpty() }
                SelectionBar(
                    count = selected.size,
                    copies = rows.sumOf { it.quantity },
                    busy = moving,
                    allSelected = ownedShown.isNotEmpty() && selected.containsAll(ownedShown),
                    onAll = { selected = selected + ownedShown },
                    onNone = { selected = emptySet() },
                    onMove = {
                        picking = true
                        scope.launch { (safeCall { c.api.binders() } as? ApiResult.Success)?.let { binders = it.data } }
                    },
                    onClose = { selected = emptySet(); selecting = false },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            }
        }
    }

    if (picking) {
        BinderPickerDialog(binders, BinderTarget(""), sets = listOf(code), onDismiss = { picking = false }, onPick = { target ->
            picking = false
            val ids = allCards.filter { it.collectorNumber in selected }.flatMap { it.owned?.rows.orEmpty() }.map { it.id }.filter { it != 0L }
            moving = true
            scope.launch {
                val r = c.collectionWrite.moveRows(ids, target)
                moving = false
                if (r is ApiResult.Success) { selected = emptySet(); selecting = false }
                Toast.makeText(context, moveMessage(res, r), Toast.LENGTH_LONG).show()
            }
        })
    }

    open?.let { n ->
        allCards.firstOrNull { it.collectorNumber == n }?.let { card ->
            SetCardDetailSheet(card, code, images, onDismiss = { open = null })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SetCardTile(c: SetCardDto, images: ImageUrls, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val owned = c.owned
    Column(
        Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.border(3.dp, PortalColors.Accent, RoundedCornerShape(8.dp)).padding(3.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box {
            CardImage(images.card(c.imageId, ImageUrls.Size.SMALL), c.name, Modifier.fillMaxWidth().alpha(if (owned != null) 1f else 0.45f))
            if (owned != null) {
                Badge("✓", PortalColors.Green, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
                if (owned.copies > 1) Badge("×${owned.copies}", PortalColors.Accent, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
            } else {
                Badge(stringResource(R.string.reserved_missing_tag), PortalColors.Red, foreground = PortalColors.Text, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
            }
            WishStar(c.name, modifier = Modifier.align(Alignment.BottomEnd))
            if (selected) Badge("✓", PortalColors.Accent, modifier = Modifier.align(Alignment.Center))
        }
        Text("#${c.collectorNumber} ${c.name}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** editionCardDetailHTML(): image with flip, number, rarity, prices, Cardmarket, ownership. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetCardDetailSheet(c: SetCardDto, setCode: String, images: ImageUrls, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val unlocked = isUnlocked()
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
            if (unlocked) {
                SetCardEditor(c, setCode)
            } else if (owned == null) {
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

private val ADD_LANGUAGES = listOf("en", "de", "fr", "it", "es", "pt", "ja", "ko", "ru", "zhs", "zht")
private val ADD_FINISHES = listOf("normal", "foil", "etched")

/**
 * The rows of a set card with -1, +1 and move (like the card grid's detail),
 * and adding a copy in a chosen language, finish and binder. The rows come
 * fresh from the server after every write (SetCardsViewModel reloads).
 */
@Composable
private fun SetCardEditor(card: SetCardDto, setCode: String) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    var busy by remember { mutableStateOf(false) }
    var notice by remember(card.collectorNumber) { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<VariantDto?>(null) }
    var pickingAdd by remember { mutableStateOf(false) }
    var binders by remember { mutableStateOf<List<BinderDto>>(emptyList()) }
    var lang by rememberSaveable { mutableStateOf("en") }
    var finish by rememberSaveable { mutableStateOf("normal") }
    var target by remember { mutableStateOf(BinderTarget("")) }

    fun loadBinders() = scope.launch { (safeCall { c.api.binders() } as? ApiResult.Success)?.let { binders = it.data } }
    fun write(block: suspend () -> ApiResult<*>, ok: () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            notice = when (val r = block()) {
                is ApiResult.Success -> ok()
                is ApiResult.HttpError -> r.message
                is ApiResult.NetworkError -> r.cause.message ?: r.cause.javaClass.simpleName
            }
            busy = false
        }
    }
    val volatileText = stringResource(R.string.detail_edit_volatile)

    val owned = card.owned
    if (owned == null) {
        Text(stringResource(R.string.eo_missing_card), color = PortalColors.TextDim)
    } else {
        Text(stringResource(R.string.eo_in_collection, owned.copies), style = MaterialTheme.typography.titleSmall)
        owned.rows.sortedWith(compareBy({ it.language }, { it.foil })).forEach { v ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${Language.flag(v.language)} ${v.language.ifEmpty { "?" }.uppercase()}")
                    if (v.foil.isNotEmpty() && v.foil != "normal") Badge(v.foil, PortalColors.Accent2, foreground = PortalColors.Text)
                    Text(Condition.label(v.condition), color = PortalColors.TextDim, modifier = Modifier.weight(1f))
                    Text("×${v.quantity}")
                }
                val meta = listOfNotNull(
                    v.binderName.ifEmpty { null },
                    if (v.origin == "app") stringResource(R.string.detail_origin_app) else null,
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = PortalColors.TextDim)
                if (v.id != 0L) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !busy, onClick = {
                            write({ c.collectionWrite.patch(v.id, RowPatchDto(quantityDelta = -1)) }) { if (v.origin == "csv") volatileText else null }
                        }) { Text("−1") }
                        TextButton(enabled = !busy, onClick = {
                            // The catalog's number: the row's may carry leading zeros.
                            write({ c.collectionWrite.addOne(setCode, card.collectorNumber, v) }) { null }
                        }) { Text("+1") }
                        TextButton(enabled = !busy, onClick = { moving = v; loadBinders() }) { Text(stringResource(R.string.detail_move)) }
                    }
                }
            }
        }
    }

    HorizontalDivider()
    Text(stringResource(R.string.eo_add_title), style = MaterialTheme.typography.titleSmall)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(ADD_LANGUAGES) { l ->
            FilterChip(lang == l, { lang = l }, label = { Text("${Language.flag(l)} ${l.uppercase()}", fontSize = 12.sp) })
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(ADD_FINISHES) { f ->
            FilterChip(finish == f, { finish = f }, label = { Text(f.replaceFirstChar { it.uppercase() }, fontSize = 12.sp) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { pickingAdd = true; loadBinders() }, modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.eo_add_binder, target.name.ifEmpty { stringResource(R.string.cards_move_no_binder) }),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        androidx.compose.material3.Button(enabled = !busy, onClick = {
            write({ c.collectionWrite.addCopy(setCode, card.collectorNumber, finish, lang, "near_mint", target) }) {
                res.getString(R.string.eo_added, card.name)
            }
        }) { Text(stringResource(R.string.eo_add_button)) }
    }
    notice?.let { Text(it, fontSize = 12.sp, color = PortalColors.Accent) }

    moving?.let { v ->
        BinderPickerDialog(binders, BinderTarget(v.binderName, v.binderType), sets = listOf(setCode), onDismiss = { moving = null }, onPick = { t ->
            moving = null
            if (t.name == v.binderName) return@BinderPickerDialog
            write({ c.collectionWrite.patch(v.id, RowPatchDto(binderName = t.name, binderType = t.type)) }) { if (v.origin == "csv") volatileText else null }
        })
    }
    if (pickingAdd) {
        BinderPickerDialog(binders, target, sets = listOf(setCode), onDismiss = { pickingAdd = false }, onPick = { t -> pickingAdd = false; target = t })
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Text(label, color = PortalColors.TextDim, modifier = Modifier.width(150.dp))
        Text(value, fontWeight = FontWeight.Medium)
    }
}
