package net.kirkanos.mtgportal.feature.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.data.BinderTarget
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.BinderDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.theme.PortalColors

/**
 * Binders that already hold any of [sets], most copies first, with those
 * copies. [binderSets] is /api/binders/sets.
 */
internal fun binderSuggestions(
    binders: List<BinderDto>,
    binderSets: Map<String, Map<String, Int>>,
    sets: Collection<String>,
): List<Pair<BinderDto, Int>> {
    val codes = sets.map { it.uppercase() }.filter { it.isNotEmpty() }.toSet()
    if (codes.isEmpty()) return emptyList()
    return binders.filter { it.name.isNotEmpty() }
        .map { b -> b to codes.sumOf { binderSets[b.name]?.get(it) ?: 0 } }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<BinderDto, Int>> { it.second }.thenBy { it.first.name.lowercase() })
}

/**
 * Existing binders by type, "without binder", and a new one by name. [sets]
 * are the editions of the cards being added or moved: binders that hold them
 * already come first, as suggestions.
 */
@Composable
fun BinderPickerDialog(
    binders: List<BinderDto>,
    current: BinderTarget,
    onPick: (BinderTarget) -> Unit,
    onDismiss: () -> Unit,
    sets: Collection<String> = emptyList(),
) {
    val c = appContainer()
    var binderSets by remember { mutableStateOf<Map<String, Map<String, Int>>>(emptyMap()) }
    LaunchedEffect(sets.isNotEmpty()) {
        if (sets.isNotEmpty()) (safeCall { c.api.binderSets() } as? ApiResult.Success)?.let { binderSets = it.data }
    }
    val suggested = binderSuggestions(binders, binderSets, sets)
    val taken = suggested.map { it.first.name }.toSet()
    var newName by remember { mutableStateOf("") }
    // With many binders: a filter over the names.
    var filter by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf("binder") }
    // A name that exists is that binder, with its type.
    val existing = binders.firstOrNull { it.name.equals(newName.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_add_target)) },
        text = {
            LazyColumn {
                if (binders.size > 8) {
                    item {
                        OutlinedTextField(
                            value = filter, onValueChange = { filter = it }, singleLine = true,
                            placeholder = { Text(stringResource(R.string.binder_filter)) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        )
                    }
                }
                val shown = suggested.filter { it.first.name.contains(filter.trim(), ignoreCase = true) }
                if (shown.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.binder_suggested), style = MaterialTheme.typography.labelLarge,
                            color = PortalColors.Accent, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(shown, key = { "s|${it.first.name}" }) { (b, copies) ->
                        ListItem(
                            headlineContent = { Text("★ ${b.name}") },
                            supportingContent = { Text(stringResource(R.string.binder_suggested_copies, copies)) },
                            trailingContent = { if (current.name == b.name) Text("✓") },
                            modifier = Modifier.clickable { onPick(BinderTarget(b.name, b.type)) },
                        )
                    }
                }
                if (filter.isBlank()) item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.scan_add_no_binder)) },
                        modifier = Modifier.clickable { onPick(BinderTarget("")) },
                        trailingContent = { if (current.name.isEmpty()) Text("✓") },
                    )
                }
                for ((type, label) in listOf("binder" to R.string.nav_binders, "list" to R.string.nav_lists, "deck" to R.string.nav_decks)) {
                    val group = binders.filter {
                        it.type == type && it.name.isNotEmpty() && it.name !in taken && it.name.contains(filter.trim(), ignoreCase = true)
                    }
                    if (group.isEmpty()) continue
                    item { Text(stringResource(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
                    items(group, key = { "$type|${it.name}" }) { b ->
                        ListItem(
                            headlineContent = { Text(b.name) },
                            supportingContent = { Text(stringResource(R.string.binders_cards, b.total.toString())) },
                            trailingContent = { if (current.name == b.name) Text("✓") },
                            modifier = Modifier.clickable { onPick(BinderTarget(b.name, b.type)) },
                        )
                    }
                }
                item {
                    Text(stringResource(R.string.scan_add_new_binder), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = newType == "binder", onClick = { newType = "binder" }, label = { Text(stringResource(R.string.nav_binders)) })
                        FilterChip(selected = newType == "list", onClick = { newType = "list" }, label = { Text(stringResource(R.string.nav_lists)) })
                    }
                    if (existing != null) Text(stringResource(R.string.scan_add_exists), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(existing?.let { BinderTarget(it.name, it.type) } ?: BinderTarget(newName.trim(), newType)) },
                enabled = newName.isNotBlank(),
            ) {
                Text(stringResource(R.string.scan_add_new_binder_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
