package net.kirkanos.mtgportal.feature.editions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.EditionDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.Format
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.components.ErrorRetry
import net.kirkanos.mtgportal.core.ui.components.StatGrid
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.di.AppContainer

private val SORTS = listOf(
    "value-desc" to R.string.ed_sort_value_desc,
    "name" to R.string.ed_sort_name,
    "owned-desc" to R.string.ed_sort_owned_desc,
    "missing-desc" to R.string.ed_sort_missing_desc,
    "missing-asc" to R.string.ed_sort_missing_asc,
    "complete-desc" to R.string.ed_sort_complete_desc,
    "dupes-desc" to R.string.ed_sort_dupes_desc,
    "released-desc" to R.string.ed_sort_released_desc,
    "released-asc" to R.string.ed_sort_released_asc,
)

class EditionsViewModel(private val c: AppContainer) : ViewModel() {
    private val _data = MutableStateFlow<ApiResult<List<EditionDto>>?>(null)
    val data: StateFlow<ApiResult<List<EditionDto>>?> = _data.asStateFlow()

    val types: StateFlow<TypeFilter> = c.settings.editionTypes.map { TypeFilter.decode(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TypeFilter.decode(c.settings.editionTypes.value))

    init {
        load()
        // Reload after writes made elsewhere (see CollectionWriteRepository.changes).
        viewModelScope.launch { c.collectionWrite.changes.drop(1).collect { load() } }
    }

    fun load() = viewModelScope.launch {
        _data.value = null
        _data.value = safeCall { c.api.editions() }
    }

    fun setTypes(f: TypeFilter) = viewModelScope.launch { c.settings.setEditionTypes(f.encode()) }
}

/** SET_TYPE_LABEL in editions.js: translated where the web translates, else the raw name. */
@Composable
fun setTypeLabel(type: String): String = when (type) {
    "core" -> stringResource(R.string.set_type_core)
    "expansion" -> stringResource(R.string.set_type_expansion)
    "draft_innovation" -> stringResource(R.string.set_type_draft_innovation)
    "box" -> stringResource(R.string.set_type_box)
    "funny" -> stringResource(R.string.set_type_funny)
    "masters" -> "Masters"
    "commander" -> "Commander"
    "remastered" -> "Remastered"
    "duel_deck" -> "Duel Deck"
    "from_the_vault" -> "From the Vault"
    "premium_deck" -> "Premium Deck"
    "" -> "–"
    else -> type.replaceFirstChar { it.uppercase() }
}

@Composable
fun EditionsScreen(onOpenSet: (String) -> Unit) {
    val vm = containerViewModel { EditionsViewModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val types by vm.types.collectAsStateWithLifecycle()
    val images by appContainer().config.imageUrls.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var owned by rememberSaveable { mutableStateOf("all") }
    var sort by rememberSaveable { mutableStateOf("value-desc") }
    var typeDialog by remember { mutableStateOf(false) }

    when (val r = data) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ApiResult.HttpError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.NetworkError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.Success -> {
            val all = r.data
            val shown = filterEditions(all, types, owned, query, sort)
            val st = editionStats(all)
            val allTypes = all.map { it.setType }.filter { it.isNotEmpty() }.distinct()

            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    StatGrid(
                        listOf(
                            st.collected.toString() to stringResource(R.string.ed_collected),
                            Format.count(st.distinct) to stringResource(R.string.ed_distinct),
                            st.complete.toString() to stringResource(R.string.ed_complete),
                            Format.count(st.dupeCards) to stringResource(R.string.ed_dupes),
                        ),
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.ed_search)) }, modifier = Modifier.fillMaxWidth())
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("all" to R.string.ed_all, "owned" to R.string.ed_only_owned, "missing" to R.string.ed_only_missing)) { (k, l) ->
                                FilterChip(owned == k, { owned = k }, label = { Text(stringResource(l), fontSize = 12.sp) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { typeDialog = true }) { Text(typeButtonLabel(types)) }
                            SortMenu(sort) { sort = it }
                        }
                        Text(stringResource(R.string.ed_hint), fontSize = 12.sp, color = PortalColors.TextDim)
                        Text(pluralStringResource(R.plurals.ed_result_count, all.size, shown.size, all.size), fontSize = 13.sp, color = PortalColors.TextDim)
                    }
                }
                items(shown, key = { it.code }) { e ->
                    EditionRow(e, images.setIcon(e.iconSvgUri)) { onOpenSet(e.code) }
                }
            }

            if (typeDialog) {
                TypeFilterDialog(types, allTypes, onChange = vm::setTypes, onDismiss = { typeDialog = false })
            }
        }
    }
}

@Composable
private fun typeButtonLabel(f: TypeFilter): String = when (f.mode) {
    "collectable" -> stringResource(R.string.ed_types_collectable)
    "all" -> stringResource(R.string.ed_types_all)
    else -> when (f.types.size) {
        0 -> stringResource(R.string.ed_types_none)
        1 -> setTypeLabel(f.types.first())
        else -> pluralStringResource(R.plurals.ed_types_n, f.types.size, f.types.size)
    }
}

@Composable
private fun SortMenu(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(stringResource(SORTS.first { it.first == selected }.second).substringAfter(": "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SORTS.forEach { (k, l) -> DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { open = false; onSelect(k) }) }
        }
    }
}

/** One row of the web's edition table, stacked for a phone. */
@Composable
private fun EditionRow(e: EditionDto, icon: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PortalColors.BgCard).clickable(onClick = onClick)
            .padding(12.dp).alpha(if (e.inCollection) 1f else 0.6f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) {
                AsyncImage(model = icon, contentDescription = null, modifier = Modifier.size(22.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(PortalColors.Text))
            } else {
                Text(if (e.inCollection) "🗂️" else "⬚")
            }
            Text(e.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.code, fontSize = 12.sp, color = PortalColors.TextDim)
        }
        if (e.total == null) {
            Text(pluralStringResource(R.plurals.ed_cards_unknown_size, e.owned, e.owned), fontSize = 12.sp, color = PortalColors.TextDim)
        } else {
            val pct = if (e.total > 0) minOf(100, Math.round(e.owned * 100f / e.total)) else 0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ed_of_total, e.owned, e.total), fontSize = 13.sp)
                LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.weight(1f), color = PortalColors.Accent, trackColor = PortalColors.Border)
                Text("$pct%", fontSize = 12.sp, color = PortalColors.Accent)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when {
                    e.missing == null -> stringResource(R.string.ed_missing_unknown)
                    e.missing == 0 -> stringResource(R.string.ed_complete_tag)
                    else -> stringResource(R.string.ed_missing_n, Format.count(e.missing))
                },
                fontSize = 12.sp,
                color = if (e.missing == 0) PortalColors.Green else PortalColors.TextDim,
            )
            if (e.dupeCount > 0) Text(pluralStringResource(R.plurals.ed_dupes_n, e.dupeCount, e.dupeCount, e.dupeExtra), fontSize = 12.sp, color = PortalColors.TextDim)
            Box(Modifier.weight(1f))
            Text(Format.eur(e.marketValue), fontSize = 13.sp, color = PortalColors.Accent)
        }
        if (e.totalCopies > 0) Text(stringResource(R.string.ed_copies, Format.count(e.totalCopies)), fontSize = 11.sp, color = PortalColors.TextDim)
    }
}

/**
 * The web's set-type multi-select with its two presets. Ticking a box in a
 * preset turns it into a custom choice starting from what the preset showed.
 */
@Composable
private fun TypeFilterDialog(current: TypeFilter, allTypes: List<String>, onChange: (TypeFilter) -> Unit, onDismiss: () -> Unit) {
    val sel = current.effective(allTypes)
    val labelled = allTypes.map { it to setTypeLabel(it) }.sortedBy { it.second }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ed_type_filter_title)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ed_close)) } },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(current.mode == "collectable", { onChange(TypeFilter("collectable")) }, label = { Text(stringResource(R.string.ed_types_collectable), fontSize = 12.sp) })
                    FilterChip(current.mode == "all", { onChange(TypeFilter("all")) }, label = { Text(stringResource(R.string.ed_types_all), fontSize = 12.sp) })
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(labelled, key = { it.first }) { (type, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val next = if (type in sel) sel - type else sel + type
                                onChange(TypeFilter("custom", next))
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = type in sel, onCheckedChange = null)
                            Text(label, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        },
    )
}
