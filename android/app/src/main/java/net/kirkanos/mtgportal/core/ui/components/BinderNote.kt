package net.kirkanos.mtgportal.core.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.theme.PortalColors

/**
 * The note of a binder (folder, list or deck) in an overview: a symbol, a tap
 * shows the text - the web's tooltip, without a mouse to hover with.
 */
@Composable
fun NoteIcon(title: String, note: String, modifier: Modifier = Modifier) {
    if (note.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Text(
        "📝", fontSize = 15.sp,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).clickable { open = true }.padding(horizontal = 4.dp, vertical = 2.dp),
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { Text(note, lineHeight = 20.sp) },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

/**
 * The note in full on a binder or deck page, and with canEdit a button to add
 * or change it. onSaved gets the stored note.
 */
@Composable
fun NoteBlock(binder: String, note: String, canEdit: Boolean, modifier: Modifier = Modifier, onSaved: (String) -> Unit = {}) {
    var editing by remember { mutableStateOf(false) }
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    if (note.isEmpty() && !canEdit) return
    Column(modifier) {
        if (note.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(note, fontSize = 14.sp, lineHeight = 20.sp)
        }
        if (canEdit) {
            TextButton(onClick = { editing = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(
                    stringResource(if (note.isEmpty()) R.string.binder_note_add else R.string.binder_note_edit),
                    color = PortalColors.TextDim, fontSize = 13.sp,
                )
            }
        }
    }
    if (editing) {
        NoteDialog(note, onDismiss = { editing = false }) { text ->
            scope.launch {
                val failed = when (val r = c.collectionWrite.setBinderNote(binder, text)) {
                    is ApiResult.Success -> { editing = false; onSaved(r.data.note); null }
                    is ApiResult.HttpError -> r.message
                    is ApiResult.NetworkError -> r.cause.message.orEmpty()
                }
                failed?.let { Toast.makeText(context, res.getString(R.string.binder_note_failed, it), Toast.LENGTH_LONG).show() }
            }
        }
    }
}

/** Free text on what the binder holds; an empty one removes it. */
@Composable
private fun NoteDialog(note: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.binder_note_title)) },
        text = {
            OutlinedTextField(
                text, { if (it.length <= 4000) text = it },
                placeholder = { Text(stringResource(R.string.binder_note_hint)) },
                minLines = 4, maxLines = 12, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.trim() != note) { Text(stringResource(R.string.binder_note_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
