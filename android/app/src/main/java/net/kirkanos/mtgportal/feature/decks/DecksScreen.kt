package net.kirkanos.mtgportal.feature.decks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.model.ImageUrls
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.BinderDto
import net.kirkanos.mtgportal.core.network.dto.DeckHitsDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.Format
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.components.ErrorRetry
import net.kirkanos.mtgportal.core.ui.components.ManaCostRow
import net.kirkanos.mtgportal.core.ui.components.isUnlocked
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.di.AppContainer

class DecksViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<ApiResult<List<BinderDto>>?>(null)
    val state: StateFlow<ApiResult<List<BinderDto>>?> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.collectionWrite.changes.drop(1).collect { load(quiet = true) } }
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _hits = MutableStateFlow<Map<String, DeckHitsDto>>(emptyMap())
    val hits: StateFlow<Map<String, DeckHitsDto>> = _hits.asStateFlow()
    private var searchJob: Job? = null

    /** Card hits come from the server, a moment after the last key; deck names match locally. */
    fun search(q: String) {
        _query.value = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val terms = q.trim()
            if (terms.isEmpty()) {
                _hits.value = emptyMap()
                return@launch
            }
            delay(300)
            val r = safeCall { c.api.deckSearch(terms) }
            _hits.value = (r as? ApiResult.Success)?.data.orEmpty()
        }
    }

    fun load(quiet: Boolean = false) = viewModelScope.launch {
        if (!quiet) _state.value = null
        val r = safeCall { c.api.binders() }
        if (quiet && r !is ApiResult.Success) return@launch
        _state.value = r
    }
}

/** Tints of the lid per colour (decks.js DECK_TINT). */
internal val DECK_TINT = mapOf(
    "W" to Color(0xFFC9C0A0), "U" to Color(0xFF3D79B0), "B" to Color(0xFF5B5266),
    "R" to Color(0xFFB44A3C), "G" to Color(0xFF3F8A50),
)

/**
 * decks.html: every deck as a deck box - a lid in the deck's colours, the art
 * of the cover card on the front, a label with name, count and value.
 */
@Composable
fun DecksScreen(onOpen: (String) -> Unit) {
    val vm = containerViewModel { DecksViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val images by appContainer().config.imageUrls.collectAsStateWithLifecycle()
    val unlocked = isUnlocked()
    var creating by remember { mutableStateOf(false) }
    val query by vm.query.collectAsStateWithLifecycle()
    val hits by vm.hits.collectAsStateWithLifecycle()
    // Formats folded away; open is the default.
    var folded by remember { mutableStateOf(setOf<String>()) }
    val labels = DeckFormat.entries.associateWith { stringResource(it.label) }

    Box(Modifier.fillMaxSize()) {
        when (val r = st) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is ApiResult.HttpError -> ErrorRetry(r, onRetry = { vm.load() })
            is ApiResult.NetworkError -> ErrorRetry(r, onRetry = { vm.load() })
            is ApiResult.Success -> {
                val all = r.data.filter { it.type.equals("deck", ignoreCase = true) && it.name.isNotEmpty() }
                val searching = query.isNotBlank()
                val decks = all.filter { DeckLogic.matches(it.name, query, hits.containsKey(it.name)) }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(start = 14.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (all.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "search") {
                            OutlinedTextField(
                                value = query, onValueChange = vm::search, singleLine = true,
                                placeholder = { Text(stringResource(R.string.decks_search)) },
                                trailingIcon = { if (searching) TextButton(onClick = { vm.search("") }) { Text("✕") } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (searching) stringResource(R.string.decks_search_count, decks.size, all.size)
                            else pluralStringResource(R.plurals.decks_count, all.size, all.size),
                            color = PortalColors.TextDim,
                        )
                    }
                    if (all.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.decks_empty), color = PortalColors.TextDim) }
                    } else if (decks.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.decks_search_none), color = PortalColors.TextDim) }
                    }
                    // One foldable section per format, alphabetical, decks without a format last;
                    // formats without (matching) decks are left out.
                    (DeckFormat.entries.drop(1).sortedBy { labels.getValue(it).lowercase() } + DeckFormat.NONE).forEach { f ->
                        val group = decks.filter { DeckFormat.of(it.format) == f }
                        if (group.isEmpty()) return@forEach
                        // While searching every hit shows, folded or not.
                        val open = searching || f.key !in folded
                        item(span = { GridItemSpan(maxLineSpan) }, key = "f-${f.key}") {
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !searching) {
                                    folded = if (f.key in folded) folded - f.key else folded + f.key
                                }.padding(top = 8.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Text(if (open) "▾ " else "▸ ", color = PortalColors.TextDim, fontSize = 17.sp)
                                Text(labels.getValue(f), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text("  (${group.size})", color = PortalColors.TextDim, fontSize = 14.sp)
                            }
                        }
                        if (open) items(group, key = { it.name }) { d -> DeckBox(d, images, if (searching) hits[d.name] else null) { onOpen(d.name) } }
                    }
                }
                if (creating) {
                    NewDeckDialog(r.data, onDismiss = { creating = false }, onCreate = { creating = false; onOpen(it) })
                }
            }
        }
        if (unlocked) {
            FloatingActionButton(
                onClick = { creating = true },
                containerColor = PortalColors.Accent,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) { Text("+", fontSize = 26.sp, color = PortalColors.Bg) }
        }
    }
}

@Composable
internal fun DeckBox(d: BinderDto, images: ImageUrls, hits: DeckHitsDto? = null, onClick: () -> Unit) {
    val tints = d.colors.mapNotNull { DECK_TINT[it] }
    val base = Color(0xFF20222E)
    val lid = if (tints.isEmpty()) listOf(Color(0xFF34364A), Color(0xFF2A2C3A))
    else listOf(lerp(base, tints.first(), 0.6f), lerp(base, tints.last(), 0.6f))
    val edge = lerp(PortalColors.Border, tints.firstOrNull() ?: PortalColors.Border, 0.5f)
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        // The depth of the box: a second outline behind it, a little offset.
        Box(
            Modifier.matchParentSize().offset(6.dp, 6.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0C0D13)).border(1.dp, Color(0xFF2A2C3A), RoundedCornerShape(10.dp)),
        )
        Column(
            Modifier.shadow(10.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF262838), Color(0xFF15161F))))
                .border(1.dp, Color(0xFF3A3C4D), RoundedCornerShape(10.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().height(28.dp).background(Brush.horizontalGradient(lid))
                    .border(1.dp, edge, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val format = DeckFormat.of(d.format)
                if (format != DeckFormat.NONE) FormatBadge(stringResource(format.label))
                Spacer(Modifier.weight(1f))
                ManaCostRow(DeckLogic.pips(d.colors), images, size = 15.dp)
            }
            Box(
                Modifier.padding(10.dp).fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0D0E14)).border(1.dp, edge, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val art = d.cover?.imageId?.let { images.card(it, ImageUrls.Size.ART_CROP) }
                if (art != null) {
                    AsyncImage(model = art, contentDescription = d.cover.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text("🃏", fontSize = 34.sp, color = PortalColors.TextDim.copy(alpha = 0.4f))
                }
            }
            Column(
                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF2B2D3B), Color(0xFF1D1E29))))
                    .border(1.dp, Color(0xFF3A3C4D), RoundedCornerShape(6.dp)).padding(8.dp),
            ) {
                Text(d.name, fontWeight = FontWeight.Bold, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp, fontSize = 14.sp)
                Row {
                    Text(stringResource(R.string.deck_cards, Format.count(d.total)), fontSize = 11.sp, color = PortalColors.TextDim, modifier = Modifier.weight(1f))
                    Text(Format.eur(d.marketValue), fontSize = 11.sp, color = PortalColors.Accent, fontWeight = FontWeight.SemiBold)
                }
                if (hits != null && hits.cards.isNotEmpty()) {
                    val more = if (hits.count > hits.cards.size) " +${hits.count - hits.cards.size}" else ""
                    Text(
                        "🔎 " + hits.cards.joinToString(", ") + more,
                        fontSize = 11.sp, color = PortalColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun NewDeckDialog(binders: List<BinderDto>, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val taken = binders.firstOrNull { it.name == name.trim() && !it.type.equals("deck", ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.decks_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.decks_new_hint), fontSize = 12.sp, color = PortalColors.TextDim)
                if (taken != null) Text(stringResource(R.string.decks_new_taken, taken.name), fontSize = 12.sp, color = PortalColors.Red)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank() && taken == null) { Text(stringResource(R.string.decks_new_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
