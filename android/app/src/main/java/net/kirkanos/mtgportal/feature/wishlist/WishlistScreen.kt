package net.kirkanos.mtgportal.feature.wishlist

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.ui.components.HelpButton
import net.kirkanos.mtgportal.core.model.Condition
import net.kirkanos.mtgportal.core.model.ImageUrls
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.WishlistDto
import net.kirkanos.mtgportal.core.network.dto.WishlistInputDto
import net.kirkanos.mtgportal.core.network.dto.WishlistItemDto
import net.kirkanos.mtgportal.core.ui.Format
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.components.CardImage
import net.kirkanos.mtgportal.core.ui.components.ErrorRetry
import net.kirkanos.mtgportal.core.ui.components.StatGrid
import net.kirkanos.mtgportal.core.ui.components.isUnlocked
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import java.util.Locale

private val SORTS = listOf(
    "default" to R.string.wl_sort_default,
    "price-desc" to R.string.wl_sort_price_desc,
    "price-asc" to R.string.wl_sort_price_asc,
    "change-asc" to R.string.wl_sort_change_asc,
    "name" to R.string.wl_sort_name,
    "added" to R.string.wl_sort_added,
)

@Composable
fun WishlistScreen() {
    val vm = containerViewModel { WishlistViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val res = LocalResources.current

    LaunchedEffect(st.message) {
        st.message?.let {
            Toast.makeText(context, res.getString(R.string.wl_save_failed, it), Toast.LENGTH_LONG).show()
            vm.clearMessage()
        }
    }

    when (val r = st.data) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ApiResult.HttpError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.NetworkError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.Success -> WishlistContent(r.data, st.exportText, vm)
    }
}

@Composable
private fun WishlistContent(data: WishlistDto, exportText: String?, vm: WishlistViewModel) {
    val images by appContainer().config.imageUrls.collectAsStateWithLifecycle()
    val unlocked = isUnlocked()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var state by rememberSaveable { mutableStateOf("all") }
    var sort by rememberSaveable { mutableStateOf("default") }
    var newName by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = filterWishlist(data.items, query, state, sort)
    val s = data.summary
    val emptyMsg = stringResource(R.string.wl_export_empty)

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            StatGrid(
                listOf(
                    s.items.toString() to stringResource(R.string.wl_entries),
                    Format.eur(s.value) to stringResource(R.string.wl_total_cost) +
                        (if (s.unpriced > 0) " (" + stringResource(R.string.wl_unpriced, s.unpriced) + ")" else ""),
                    s.targetsMet.toString() to stringResource(R.string.wl_targets_met),
                    s.upgrades.toString() to stringResource(R.string.wl_upgrades),
                    s.days.toString() to stringResource(R.string.wl_days),
                ),
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.wl_search)) }, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("all" to R.string.wl_state_all, "target" to R.string.wl_state_target, "upgrade" to R.string.wl_state_upgrade, "missing" to R.string.wl_state_missing)) { (k, l) ->
                        FilterChip(state == k, { state = k }, label = { Text(stringResource(l), fontSize = 12.sp) })
                    }
                }
                SortMenu(sort) { sort = it }
                // Adding is a write; exporting is not.
                if (unlocked) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            newName, { newName = it }, singleLine = true,
                            placeholder = { Text(stringResource(R.string.wl_add_name), fontSize = 13.sp) },
                            modifier = Modifier.weight(1f),
                        )
                        Button(enabled = newName.isNotBlank(), onClick = { vm.add(newName); newName = "" }) { Text(stringResource(R.string.wl_add)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        vm.export { text ->
                            if (text == null) Toast.makeText(context, emptyMsg, Toast.LENGTH_SHORT).show()
                            else copyToClipboard(context, text)
                        }
                    }) { Text(stringResource(R.string.wl_export)) }
                    HelpButton(stringResource(R.string.wl_howto))
                }
                exportText?.let {
                    Text(stringResource(R.string.wl_export_copied), fontSize = 12.sp, color = PortalColors.TextDim)
                    SelectionContainer {
                        Text(it, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(PortalColors.BgCard).padding(8.dp))
                    }
                }
                Text(pluralStringResource(R.plurals.wl_result_count, data.items.size, shown.size, data.items.size), fontSize = 13.sp, color = PortalColors.TextDim)
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    stringResource(if (data.items.isEmpty()) R.string.wl_empty else R.string.wl_empty_filter),
                    color = PortalColors.TextDim,
                )
            }
        }
        items(shown, key = { it.name }) { item ->
            WishlistRow(
                item = item,
                images = images,
                days = s.days,
                unlocked = unlocked,
                editing = editing == item.name,
                onToggleEdit = { editing = if (editing == item.name) null else item.name },
                onSave = vm::save,
                onRemove = { vm.remove(item.name) },
            )
        }
    }
}

@Composable
private fun SortMenu(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(stringResource(SORTS.first { it.first == selected }.second)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SORTS.forEach { (k, l) -> DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { open = false; onSelect(k) }) }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Cardmarket", text))
}

@Composable
private fun WishlistRow(
    item: WishlistItemDto,
    images: ImageUrls,
    days: Int,
    unlocked: Boolean,
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onSave: (WishlistInputDto) -> Unit,
    onRemove: () -> Unit,
) {
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (item.targetMet) PortalColors.Green.copy(alpha = 0.12f) else PortalColors.BgCard).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(64.dp)) { CardImage(images.card(item.imageId, ImageUrls.Size.SMALL), item.name) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row {
                    Text(item.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                    if (item.priority > 0) Text(" " + "!".repeat(item.priority), color = PortalColors.Red, fontWeight = FontWeight.Bold)
                }
                PriceLine(item, days)
                OwnedLine(item)
                if (item.cardmarketUri.isNotEmpty()) {
                    Text(stringResource(R.string.cardmarket), color = PortalColors.Accent, fontSize = 13.sp, modifier = Modifier.clickable { uri.openUri(item.cardmarketUri) })
                }
            }
        }
        if (unlocked) {
            Row {
                TextButton(onClick = onToggleEdit) { Text(stringResource(if (editing) R.string.wl_done else R.string.wl_edit)) }
                TextButton(onClick = onRemove) { Text("× " + stringResource(R.string.wl_remove), color = PortalColors.Red) }
            }
            if (editing) EditFields(item, onSave)
        }
    }
}

@Composable
private fun PriceLine(item: WishlistItemDto, days: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            item.unknownToCatalog -> Text(stringResource(R.string.wl_unknown_name), color = PortalColors.Red, fontSize = 13.sp)
            item.price == null -> Text(stringResource(R.string.wl_no_price), color = PortalColors.TextDim, fontSize = 13.sp)
            else -> {
                Text(Format.eur(item.price), color = if (item.targetMet) PortalColors.Green else PortalColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                val where = if (item.priceSetName.isNotEmpty()) "${item.priceSetName} #${item.priceNumber}" else "${item.priceSetCode} #${item.priceNumber}"
                Text(where + if (item.pinned) " 📌" else "", fontSize = 12.sp, color = PortalColors.TextDim, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            }
        }
        item.changePct?.let { pct ->
            val color = when { pct < 0 -> PortalColors.Green; pct > 0 -> PortalColors.Red; else -> PortalColors.TextDim }
            Text((if (pct > 0) "+" else "") + String.format(Locale.GERMANY, "%.1f %%", pct), color = color, fontSize = 12.sp)
        }
    }
    item.priceThen?.let { Text(pluralStringResource(R.plurals.wl_days_ago, days, days, Format.eur(it)), fontSize = 11.sp, color = PortalColors.TextDim) }
}

@Composable
private fun OwnedLine(item: WishlistItemDto) {
    if (item.ownedQuantity == 0) {
        Text(stringResource(R.string.wl_not_owned), fontSize = 12.sp, color = PortalColors.TextDim)
        return
    }
    val cond = if (item.bestCondition.isNotEmpty()) Condition.label(item.bestCondition) else stringResource(R.string.wl_unknown_condition)
    val text = "${item.ownedQuantity}× $cond" + if (item.needsUpgrade) " · " + stringResource(R.string.wl_upgrade_tag) else ""
    Text(text, fontSize = 12.sp, color = if (item.needsUpgrade) PortalColors.Accent else PortalColors.Green)
}

/**
 * The four inline fields of the web row. Each saves when it loses focus or,
 * for the choices, right away - with the whole row, like saveField().
 */
@Composable
private fun EditFields(item: WishlistItemDto, onSave: (WishlistInputDto) -> Unit) {
    val base = item.toInput()
    var target by remember(item.name, item.targetEur) { mutableStateOf(item.targetEur?.let { String.format(Locale.US, "%.2f", it) } ?: "") }
    var note by remember(item.name, item.note) { mutableStateOf(item.note) }
    // "Done" on the keyboard must save: it only closes the keyboard, the field
    // keeps its focus, and saving is tied to losing it (blur on the web).
    val focus = LocalFocusManager.current
    val done = KeyboardActions(onDone = { focus.clearFocus() })

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            target, { target = it },
            label = { Text(stringResource(R.string.wl_target)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = done,
            modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                if (!f.isFocused) {
                    val v = target.replace(',', '.').trim().toDoubleOrNull()
                    if (v != item.targetEur) onSave(base.copy(targetEur = v))
                }
            },
        )
        Text(stringResource(R.string.wl_min_condition), fontSize = 12.sp, color = PortalColors.TextDim)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf("") + Condition.ORDER) { c ->
                FilterChip(
                    selected = item.minCondition == c,
                    onClick = { if (item.minCondition != c) onSave(base.copy(minCondition = c)) },
                    label = { Text(if (c.isEmpty()) stringResource(R.string.wl_any) else Condition.label(c), fontSize = 12.sp) },
                )
            }
        }
        Text(stringResource(R.string.wl_priority), fontSize = 12.sp, color = PortalColors.TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to R.string.wl_prio_0, 1 to R.string.wl_prio_1, 2 to R.string.wl_prio_2).forEach { (p, l) ->
                FilterChip(item.priority == p, { if (item.priority != p) onSave(base.copy(priority = p)) }, label = { Text(stringResource(l), fontSize = 12.sp) })
            }
        }
        OutlinedTextField(
            note, { note = it },
            label = { Text(stringResource(R.string.wl_note)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = done,
            modifier = Modifier.fillMaxWidth().onFocusChanged { f -> if (!f.isFocused && note != item.note) onSave(base.copy(note = note)) },
        )
    }
}
