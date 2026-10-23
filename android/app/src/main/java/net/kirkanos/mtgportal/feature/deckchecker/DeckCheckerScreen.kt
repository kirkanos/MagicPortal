package net.kirkanos.mtgportal.feature.deckchecker

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.OwnedByNameRequest
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.components.ErrorRetry
import net.kirkanos.mtgportal.core.ui.components.StatGrid
import net.kirkanos.mtgportal.core.ui.components.WishStar
import net.kirkanos.mtgportal.core.ui.components.isUnlocked
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.di.AppContainer

data class DeckLine(val entry: DeckEntry, val owned: Int) {
    val complete get() = owned >= entry.qty
}

data class DeckState(
    val checking: Boolean = false,
    val lines: List<DeckLine>? = null,
    val error: ApiResult<Nothing>? = null,
    val bulkRunning: Boolean = false,
    val bulkResult: Pair<Int, Int>? = null, // saved, failed
)

class DeckCheckerViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DeckState())
    val state: StateFlow<DeckState> = _state.asStateFlow()

    /**
     * The web loads the whole collection to build a name -> quantity map; here
     * the backend answers exactly the asked names (POST /api/collection/owned,
     * at most 500 per request).
     */
    fun check(text: String) = viewModelScope.launch {
        val entries = DecklistParser.parse(text)
        if (entries.isEmpty()) return@launch _state.update { DeckState(lines = emptyList()) }
        _state.update { it.copy(checking = true, error = null) }
        val owned = mutableMapOf<String, Int>()
        for (chunk in entries.map { it.name }.distinct().chunked(500)) {
            when (val r = safeCall { c.api.ownedByName(OwnedByNameRequest(chunk)) }) {
                is ApiResult.Success -> owned += r.data
                is ApiResult.HttpError -> return@launch _state.update { it.copy(checking = false, error = r) }
                is ApiResult.NetworkError -> return@launch _state.update { it.copy(checking = false, error = r) }
            }
        }
        _state.update { DeckState(lines = entries.map { DeckLine(it, owned[it.name.lowercase()] ?: 0) }) }
    }

    /**
     * Everything the deck is short of, onto the wishlist. Sequential on
     * purpose, like the web: the backend has one database connection, so
     * parallel requests would only queue behind each other.
     */
    fun addMissing(names: List<String>) = viewModelScope.launch {
        _state.update { it.copy(bulkRunning = true) }
        var ok = 0
        var failed = 0
        for (n in names) if (c.wishlist.set(n, true) is ApiResult.Success) ok++ else failed++
        _state.update { it.copy(bulkRunning = false, bulkResult = ok to failed) }
    }

    fun clearBulkResult() = _state.update { it.copy(bulkResult = null) }
}

@Composable
fun DeckCheckerScreen() {
    val vm = containerViewModel { DeckCheckerViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val c = net.kirkanos.mtgportal.core.ui.appContainer()
    val wished by c.wishlist.names.collectAsStateWithLifecycle()
    val unlocked = isUnlocked()
    val context = LocalContext.current
    val res = LocalResources.current
    var text by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<List<String>?>(null) }
    val allThere = stringResource(R.string.dc_bulk_all_there)

    st.bulkResult?.let { (ok, failed) ->
        LaunchedEffect(ok, failed) {
            val msg = if (failed > 0) res.getString(R.string.dc_bulk_failed, failed) else res.getString(R.string.dc_bulk_done, ok)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            vm.clearBulkResult()
        }
    }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dc_label), fontSize = 13.sp, color = PortalColors.TextDim)
                OutlinedTextField(
                    text, { text = it },
                    placeholder = { Text(stringResource(R.string.dc_placeholder)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
                Button(enabled = !st.checking, onClick = { vm.check(text) }) { Text(stringResource(R.string.dc_check)) }
            }
        }
        st.error?.let { e -> item { ErrorRetry(e, onRetry = { vm.check(text) }) } }
        if (st.checking) item { CircularProgressIndicator() }

        st.lines?.let { lines ->
            if (lines.isEmpty()) {
                item { Text(stringResource(R.string.dc_no_lines), color = PortalColors.TextDim) }
            } else {
                item {
                    StatGrid(
                        listOf(
                            lines.size.toString() to stringResource(R.string.dc_lines),
                            lines.count { it.complete }.toString() to stringResource(R.string.dc_complete),
                            lines.count { !it.complete && it.owned > 0 }.toString() to stringResource(R.string.dc_partial),
                            lines.count { it.owned == 0 }.toString() to stringResource(R.string.dc_missing),
                        ),
                    )
                }
                val missing = lines.filter { !it.complete }.map { it.entry.name }
                if (unlocked && missing.isNotEmpty()) {
                    item {
                        OutlinedButton(enabled = !st.bulkRunning, onClick = {
                            val todo = missing.filter { it.lowercase() !in wished }
                            if (todo.isEmpty()) Toast.makeText(context, allThere, Toast.LENGTH_SHORT).show() else confirm = todo
                        }) { Text(stringResource(R.string.dc_bulk, missing.size)) }
                    }
                }
                items(lines) { line -> DeckRow(line) }
            }
        }
    }

    confirm?.let { todo ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(stringResource(R.string.dc_bulk_confirm, todo.size)) },
            confirmButton = { TextButton(onClick = { confirm = null; vm.addMissing(todo) }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun DeckRow(line: DeckLine) {
    val (status, color) = when {
        line.complete -> stringResource(R.string.dc_complete) to PortalColors.Green
        line.owned > 0 -> stringResource(R.string.dc_partial) to PortalColors.Accent
        else -> stringResource(R.string.dc_missing) to PortalColors.Red
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PortalColors.BgCard).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(line.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.dc_needed_owned, line.entry.qty, line.owned), fontSize = 12.sp, color = PortalColors.TextDim)
        }
        Text(status, color = color, fontSize = 13.sp)
        // A star only where something is missing, like the web table.
        if (!line.complete) WishStar(line.entry.name)
    }
}
