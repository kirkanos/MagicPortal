package net.kirkanos.mtgportal.feature.collection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.data.AuthState
import net.kirkanos.mtgportal.core.data.BinderTarget
import net.kirkanos.mtgportal.core.model.Condition
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.AddCardsResultDto
import net.kirkanos.mtgportal.core.network.dto.BinderDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.di.AppContainer
import net.kirkanos.mtgportal.scanner.ScanSessionResult
import java.util.UUID

/** "type|name" in the settings; an empty name is the collection without a binder. */
internal fun encodeTarget(t: BinderTarget) = "${t.type}|${t.name}"

internal fun decodeTarget(s: String?): BinderTarget? {
    val (type, name) = s?.split('|', limit = 2)?.takeIf { it.size == 2 } ?: return null
    return BinderTarget(name, type)
}

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data class Done(val result: AddCardsResultDto, val target: BinderTarget) : SaveState
    data class Failed(val message: String) : SaveState
}

data class AddUiState(
    val binders: List<BinderDto> = emptyList(),
    val target: BinderTarget = BinderTarget(""),
    val condition: String = "near_mint",
    val save: SaveState = SaveState.Idle,
)

class AddToCollectionViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AddUiState(target = decodeTarget(c.settings.lastBinder.value) ?: BinderTarget("")))
    val state: StateFlow<AddUiState> = _state.asStateFlow()

    /** One key per session: a retry of the same session can never add twice. */
    private val sessionKey = UUID.randomUUID().toString()

    init {
        viewModelScope.launch {
            (safeCall { c.api.binders() } as? ApiResult.Success)?.let { r -> _state.update { it.copy(binders = r.data) } }
        }
    }

    fun setTarget(t: BinderTarget) = _state.update { it.copy(target = t) }
    fun setCondition(cond: String) = _state.update { it.copy(condition = cond) }

    fun save(result: ScanSessionResult) {
        val s = _state.value
        if (s.save is SaveState.Saving || s.save is SaveState.Done) return
        _state.update { it.copy(save = SaveState.Saving) }
        viewModelScope.launch {
            val r = c.collectionWrite.addScan(result, s.target, s.condition, sessionKey)
            _state.update {
                it.copy(
                    save = when (r) {
                        is ApiResult.Success -> SaveState.Done(r.data, s.target)
                        is ApiResult.HttpError -> SaveState.Failed(r.message)
                        is ApiResult.NetworkError -> SaveState.Failed(r.cause.message ?: r.cause.javaClass.simpleName)
                    },
                )
            }
            if (r is ApiResult.Success) c.settings.setLastBinder(encodeTarget(s.target))
        }
    }
}

/**
 * What a scan session produced, and taking it into the collection: pick a
 * binder (the last one is offered first) and a condition, one request for
 * the whole session. The list can also be copied in the usual import format.
 */
@Composable
fun ScanResultScreen(result: ScanSessionResult?, onScanAgain: () -> Unit, onOpenBinder: (String) -> Unit) {
    val vm = containerViewModel { AddToCollectionViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val auth by appContainer().auth.state.collectAsStateWithLifecycle()
    val unlocked = auth == AuthState.Open || auth == AuthState.Unlocked
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val items = result?.items.orEmpty()
    val total = items.sumOf { it.quantity }
    val container = appContainer()
    val wanted = { name: String -> container.wishlist.contains(name) }
    val wantedCount = items.count { wanted(it.name) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text(
                pluralStringResource(R.plurals.scan_result_title, total, total),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            if (wantedCount > 0) {
                Text(pluralStringResource(R.plurals.scan_wanted, wantedCount, wantedCount), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
            }
            when (val save = state.save) {
                is SaveState.Done -> DoneCard(save, onOpenBinder, onScanAgain)
                else -> if (unlocked && items.isNotEmpty()) {
                    TargetPicker(state.target) { picking = true }
                    Text(stringResource(R.string.scan_add_condition), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Condition.ORDER.forEach { k ->
                            FilterChip(selected = state.condition == k, onClick = { vm.setCondition(k) }, label = { Text(Condition.label(k)) })
                        }
                    }
                    if (save is SaveState.Failed) {
                        Text(stringResource(R.string.scan_add_failed, save.message), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.save(result ?: return@Button) }, enabled = save !is SaveState.Saving) {
                            if (save is SaveState.Saving) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.scan_add_button))
                        }
                        OutlinedButton(onClick = onScanAgain) { Text(stringResource(R.string.scan_result_again)) }
                    }
                } else if (!unlocked) {
                    Text(stringResource(R.string.scan_add_locked), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            TextButton(onClick = { copyList(context, scanAsList(result ?: return@TextButton)); copied = true }, enabled = items.isNotEmpty()) {
                Text(stringResource(if (copied) R.string.scan_result_copied else R.string.scan_result_copy))
            }
            HorizontalDivider()
        }
        items(items) { c ->
            ListItem(
                leadingContent = { Text("${c.quantity}×") },
                headlineContent = { Text((if (wanted(c.name)) "★ " else "") + c.name) },
                supportingContent = {
                    Text("${c.setCode.uppercase()} #${c.collectorNumber} · ${c.language.uppercase()}" + if (c.foil != "normal") " · ${c.foil}" else "")
                },
            )
            HorizontalDivider()
        }
    }

    if (picking) {
        BinderPickerDialog(state.binders, state.target, onPick = { vm.setTarget(it); picking = false }, onDismiss = { picking = false })
    }
}

@Composable
private fun TargetPicker(target: BinderTarget, onClick: () -> Unit) {
    Text(stringResource(R.string.scan_add_target), style = MaterialTheme.typography.labelLarge)
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(target.name.ifEmpty { stringResource(R.string.scan_add_no_binder) })
    }
}

@Composable
private fun DoneCard(done: SaveState.Done, onOpenBinder: (String) -> Unit, onScanAgain: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.scan_add_done, done.result.added, done.result.merged),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(done.target.name.ifEmpty { stringResource(R.string.scan_add_no_binder) }, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done.target.name.isNotEmpty()) Button(onClick = { onOpenBinder(done.target.name) }) { Text(stringResource(R.string.scan_add_open_binder)) }
                OutlinedButton(onClick = onScanAgain) { Text(stringResource(R.string.scan_result_again)) }
            }
        }
    }
}

private fun copyList(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Scan", text))
}
