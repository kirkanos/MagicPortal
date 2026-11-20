package net.kirkanos.mtgportal.feature.binders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import net.kirkanos.mtgportal.feature.cards.NO_BINDER
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.ui.components.HelpButton
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.BinderDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.Format
import net.kirkanos.mtgportal.core.ui.components.ErrorRetry
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.di.AppContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import net.kirkanos.mtgportal.core.data.AuthState
import net.kirkanos.mtgportal.core.network.dto.BinderPatchDto
import net.kirkanos.mtgportal.core.ui.appContainer

class BindersViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<ApiResult<List<BinderDto>>?>(null)
    val state: StateFlow<ApiResult<List<BinderDto>>?> = _state.asStateFlow()

    init {
        load()
        // Reload after writes made elsewhere (see CollectionWriteRepository.changes).
        viewModelScope.launch { c.collectionWrite.changes.drop(1).collect { load() } }
    }

    fun load() = viewModelScope.launch {
        _state.value = null
        _state.value = safeCall { c.api.binders() }
    }

    /** Message for the screen: what happened, or why not. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private fun report(r: ApiResult<*>, ok: String?) {
        _message.value = when (r) {
            is ApiResult.Success -> ok
            is ApiResult.HttpError -> r.message
            is ApiResult.NetworkError -> r.cause.message
        }
    }

    /** A new, empty binder of the page's type; the list reloads through changes. */
    fun create(name: String, type: String, okText: String) = viewModelScope.launch {
        report(c.collectionWrite.createBinder(name, type), okText)
    }

    fun delete(name: String, okText: String) = viewModelScope.launch {
        report(c.collectionWrite.deleteBinder(name), okText)
    }

    /** Rename (into an existing binder merges) and/or change the type. */
    fun rename(from: String, to: String, type: String, volatileText: String) = viewModelScope.launch {
        when (val r = c.collectionWrite.renameBinder(from, BinderPatchDto(name = to.trim(), type = type))) {
            is ApiResult.Success -> {
                _message.value = if (r.data.volatile) volatileText else null
                load()
            }
            is ApiResult.HttpError -> _message.value = r.message
            is ApiResult.NetworkError -> _message.value = r.cause.message
        }
    }
}

/**
 * Folders or lists (binders.js; one page per type, same data). Tapping one
 * opens its own page, like binder.html?name= on the web.
 */
@Composable
fun BindersScreen(type: String, onOpen: (String) -> Unit) {
    val vm = containerViewModel { BindersViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val auth by appContainer().auth.state.collectAsStateWithLifecycle()
    val unlocked = auth == AuthState.Open || auth == AuthState.Unlocked
    var renaming by remember { mutableStateOf<BinderDto?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showingNote by remember { mutableStateOf<BinderDto?>(null) }
    val createdText = stringResource(R.string.binders_created)
    val deletedText = stringResource(R.string.binders_deleted)
    val volatileText = stringResource(R.string.binders_rename_volatile)

    Box(Modifier.fillMaxSize()) {
    when (val r = st) {
        null -> Column(Modifier.fillMaxSize().padding(24.dp)) { CircularProgressIndicator() }
        is ApiResult.HttpError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.NetworkError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.Success -> {
            val list = r.data.filter { it.type.equals(type, ignoreCase = true) && it.name.isNotEmpty() }
            // The cards without a binder get a tile of their own on the folder page.
            val loose = if (type == "binder") r.data.firstOrNull { it.name.isEmpty() && it.total > 0 } else null
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        pluralStringResource(
                            if (type == "list") R.plurals.binders_count_list else R.plurals.binders_count_binder,
                            list.size,
                            list.size,
                        ),
                        color = PortalColors.TextDim,
                    )
                }
                message?.let { m -> item { Text(m, color = PortalColors.Accent, fontSize = 13.sp) } }
                if (list.isEmpty() && loose == null) item { Text(stringResource(R.string.binders_empty), color = PortalColors.TextDim) }
                items(list, key = { it.name }) { b ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PortalColors.BgCard)
                            .clickable { onOpen(b.name) }.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(b.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                            // The note behind a symbol, like the web's tooltip; a tap shows it.
                            if (b.note.isNotEmpty()) {
                                Text(
                                    "📝", fontSize = 16.sp,
                                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp))
                                        .clickable { showingNote = b }.padding(4.dp),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (unlocked && b.total == 0) {
                                TextButton(onClick = { vm.delete(b.name, deletedText.format(b.name)) }) { Text(stringResource(R.string.binders_delete)) }
                            }
                            if (unlocked) TextButton(onClick = { renaming = b }) { Text(stringResource(R.string.binders_rename)) }
                        }
                        Row {
                            Text(
                                stringResource(R.string.binders_cards, Format.count(b.total)),
                                color = PortalColors.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f),
                            )
                            Text(Format.eur(b.marketValue), color = PortalColors.Accent, fontSize = 13.sp)
                        }
                    }
                }
                loose?.let { b ->
                    item(key = "\u0000loose") {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .border(1.dp, PortalColors.Border, RoundedCornerShape(10.dp))
                                .clickable { onOpen(NO_BINDER) }.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(stringResource(R.string.binder_none), style = MaterialTheme.typography.titleMedium)
                            Row {
                                Text(
                                    stringResource(R.string.binders_cards, Format.count(b.total)),
                                    color = PortalColors.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f),
                                )
                                Text(Format.eur(b.marketValue), color = PortalColors.Accent, fontSize = 13.sp)
                            }
                        }
                    }
                }
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

    if (creating) {
        val existing = (st as? ApiResult.Success)?.data.orEmpty()
        CreateDialog(type, existing, onDismiss = { creating = false }, onCreate = { name ->
            creating = false
            vm.create(name, type, createdText.format(name))
        })
    }

    showingNote?.let { b ->
        AlertDialog(
            onDismissRequest = { showingNote = null },
            title = { Text(b.name) },
            text = { Text(b.note, lineHeight = 20.sp) },
            confirmButton = { TextButton(onClick = { showingNote = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }

    renaming?.let { b ->
        RenameDialog(b, onDismiss = { renaming = null }, onRename = { to, type ->
            renaming = null
            vm.rename(b.name, to, type, volatileText)
        })
    }
}

/** New name (an existing one merges the binders) and type. */
@Composable
private fun RenameDialog(b: BinderDto, onDismiss: () -> Unit, onRename: (String, String) -> Unit) {
    var name by remember { mutableStateOf(b.name) }
    var type by remember { mutableStateOf(b.type.ifEmpty { "binder" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.binders_rename_title))
                HelpButton(stringResource(R.string.binders_rename_hint))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((t, label) in listOf("binder" to R.string.nav_binders, "list" to R.string.nav_lists, "deck" to R.string.nav_decks)) {
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(stringResource(label)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(name, type) }, enabled = name.isNotBlank() && (name.trim() != b.name || type != b.type)) {
                Text(stringResource(R.string.binders_rename_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Name of a new folder or list; a name in use (any type) is refused. */
@Composable
private fun CreateDialog(type: String, existing: List<BinderDto>, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val taken = existing.any { it.name == name.trim() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (type == "list") R.string.binders_new_list else R.string.binders_new_binder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (taken) Text(stringResource(R.string.binders_new_taken), fontSize = 12.sp, color = PortalColors.Red)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank() && !taken) { Text(stringResource(R.string.decks_new_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
