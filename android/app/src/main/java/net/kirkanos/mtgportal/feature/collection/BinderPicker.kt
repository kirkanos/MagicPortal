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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.data.BinderTarget
import net.kirkanos.mtgportal.core.network.dto.BinderDto

/** Existing binders by type, "without binder", and a new one by name. */
@Composable
fun BinderPickerDialog(binders: List<BinderDto>, current: BinderTarget, onPick: (BinderTarget) -> Unit, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf("binder") }
    // A name that exists is that binder, with its type.
    val existing = binders.firstOrNull { it.name.equals(newName.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_add_target)) },
        text = {
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.scan_add_no_binder)) },
                        modifier = Modifier.clickable { onPick(BinderTarget("")) },
                        trailingContent = { if (current.name.isEmpty()) Text("✓") },
                    )
                }
                for ((type, label) in listOf("binder" to R.string.nav_binders, "list" to R.string.nav_lists, "deck" to R.string.nav_decks)) {
                    val group = binders.filter { it.type == type && it.name.isNotEmpty() }
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
