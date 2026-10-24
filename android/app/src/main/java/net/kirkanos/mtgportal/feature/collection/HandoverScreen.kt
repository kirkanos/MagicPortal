package net.kirkanos.mtgportal.feature.collection

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.di.AppContainer
import java.io.File

/** An export waiting for its confirmation, stored as "id|cards|file name". */
data class PendingExport(val id: Long, val cards: Int, val fileName: String) {
    fun encode() = "$id|$cards|$fileName"

    companion object {
        fun decode(s: String?): PendingExport? {
            val p = s?.split('|', limit = 3)?.takeIf { it.size == 3 } ?: return null
            return PendingExport(p[0].toLongOrNull() ?: return null, p[1].toIntOrNull() ?: 0, p[2])
        }
    }
}

data class HandoverState(
    val pending: PendingExport? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class HandoverViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HandoverState(pending = PendingExport.decode(c.settings.pendingExport.value)))
    val state: StateFlow<HandoverState> = _state.asStateFlow()

    fun file(context: Context, p: PendingExport) = File(File(context.cacheDir, EXPORT_DIR), p.fileName)

    fun export(context: Context, noneText: String) = run {
        _state.update { it.copy(busy = true, message = null) }
        when (val r = c.collectionWrite.exportForManabox()) {
            is ApiResult.Success -> {
                val e = r.data
                if (e.cards == 0) {
                    _state.update { it.copy(busy = false, message = noneText) }
                    return@run
                }
                val name = "magic-portal-app-${e.id}.csv"
                withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    File(dir, name).writeBytes(e.csv)
                }
                val p = PendingExport(e.id, e.cards, name)
                c.settings.setPendingExport(p.encode())
                _state.update { it.copy(busy = false, pending = p) }
            }
            else -> _state.update { it.copy(busy = false, message = describe(r)) }
        }
    }

    fun confirm(doneText: (Int, Int) -> String) = run {
        val p = _state.value.pending ?: return@run
        _state.update { it.copy(busy = true, message = null) }
        when (val r = c.collectionWrite.confirmExport(p.id)) {
            is ApiResult.Success -> {
                c.settings.setPendingExport("")
                _state.update { it.copy(busy = false, pending = null, message = doneText(r.data.moved, r.data.missing)) }
            }
            else -> _state.update { it.copy(busy = false, message = describe(r)) }
        }
    }

    fun discard() = viewModelScope.launch {
        c.settings.setPendingExport("")
        _state.update { it.copy(pending = null, message = null) }
    }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch { block() }
    }

    private fun describe(r: ApiResult<*>) = when (r) {
        is ApiResult.HttpError -> r.message
        is ApiResult.NetworkError -> r.cause.message ?: r.cause.javaClass.simpleName
        is ApiResult.Success -> null
    }

    companion object {
        const val EXPORT_DIR = "exports"
    }
}

/**
 * Handing the app's cards over to ManaBox, which stays the master: export
 * them as a ManaBox CSV, import that into ManaBox, confirm. The confirmation
 * turns exactly the exported cards into ManaBox rows, so the next ManaBox
 * export counts them once; whatever was scanned in between stays the app's.
 */
@Composable
fun HandoverScreen() {
    val vm = containerViewModel { HandoverViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val none = stringResource(R.string.handover_none)
    val res = LocalResources.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        val p = st.pending ?: return@rememberLauncherForActivityResult
        if (uri != null) context.contentResolver.openOutputStream(uri)?.use { out -> vm.file(context, p).inputStream().use { it.copyTo(out) } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.handover_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.handover_intro), style = MaterialTheme.typography.bodyMedium)

        when (val p = st.pending) {
            null -> Button(onClick = { vm.export(context, none) }, enabled = !st.busy) { Text(stringResource(R.string.handover_export)) }
            else -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pluralStringResource(R.plurals.handover_pending, p.cards, p.cards), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.handover_step_import), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { share(context, vm.file(context, p)) }) { Text(stringResource(R.string.handover_share)) }
                        OutlinedButton(onClick = { save.launch(p.fileName) }) { Text(stringResource(R.string.handover_save)) }
                    }
                    Text(stringResource(R.string.handover_step_confirm), style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { vm.confirm { moved, missing -> res.getQuantityString(R.plurals.handover_done, moved, moved, missing) } },
                        enabled = !st.busy,
                    ) { Text(stringResource(R.string.handover_confirm)) }
                    TextButton(onClick = vm::discard) { Text(stringResource(R.string.handover_discard)) }
                }
            }
        }
        st.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}
