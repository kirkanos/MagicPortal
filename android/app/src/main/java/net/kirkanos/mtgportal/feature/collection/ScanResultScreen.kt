package net.kirkanos.mtgportal.feature.collection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.scanner.ScanSessionResult

/**
 * What a scan session produced. Taking the cards into the collection needs
 * the write API; until then the list can be copied in the usual import
 * format.
 */
@Composable
fun ScanResultScreen(result: ScanSessionResult?, onScanAgain: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val items = result?.items.orEmpty()
    val total = items.sumOf { it.quantity }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(pluralStringResource(R.plurals.scan_result_title, total, total), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.scan_result_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        Row {
            Button(onClick = { copyList(context, scanAsList(result ?: return@Button)); copied = true }, enabled = items.isNotEmpty()) {
                Text(stringResource(if (copied) R.string.scan_result_copied else R.string.scan_result_copy))
            }
            OutlinedButton(onClick = onScanAgain, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.scan_result_again)) }
        }
        LazyColumn(Modifier.padding(top = 8.dp)) {
            items(items) { c ->
                ListItem(
                    leadingContent = { Text("${c.quantity}×") },
                    headlineContent = { Text(c.name) },
                    supportingContent = {
                        Text("${c.setCode.uppercase()} #${c.collectorNumber} · ${c.language.uppercase()}" + if (c.foil != "normal") " · ${c.foil}" else "")
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

private fun copyList(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Scan", text))
}
