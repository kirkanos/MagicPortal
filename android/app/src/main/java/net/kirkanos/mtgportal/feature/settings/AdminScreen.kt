package net.kirkanos.mtgportal.feature.settings

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.ui.components.HelpButton
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.StatusDto
import net.kirkanos.mtgportal.core.network.dto.UiConfigInputDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.network.safeStatus
import net.kirkanos.mtgportal.core.ui.Format
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.di.AppContainer
import net.kirkanos.mtgportal.navigation.NavItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.OffsetDateTime

data class AdminState(
    val status: StatusDto? = null,
    val busy: Boolean = false,
    /** One-line feedback after an action, already a localised text or a raw server message. */
    val message: String? = null,
)

class AdminViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AdminState())
    val state: StateFlow<AdminState> = _state.asStateFlow()
    val config = c.config.config

    init { refreshStatus() }

    fun refreshStatus() = viewModelScope.launch {
        (safeCall { c.api.status() } as? ApiResult.Success)?.let { r -> _state.update { it.copy(status = r.data) } }
    }

    fun saveConfig(enabled: Boolean, count: Int, hidden: List<String>, ok: String) = run {
        val r = safeCall { c.api.setConfig(UiConfigInputDto(enabled, count.coerceIn(1, 30), hidden)) }
        if (r is ApiResult.Success) c.config.refresh()
        r.toMessage(ok)
    }

    /**
     * Starts the Scryfall sync and follows /api/status every 3 s until it is
     * done - the web's refreshSyncStatus() does the same.
     */
    fun sync(started: String) = run {
        val r = safeStatus { c.api.sync() }
        if (r !is ApiResult.Success) return@run r.toMessage("")
        _state.update { it.copy(message = started) }
        do {
            delay(3000)
            val s = (safeCall { c.api.status() } as? ApiResult.Success)?.data
            if (s != null) _state.update { it.copy(status = s) }
        } while (s?.running == true)
        null
    }

    fun imagesSync(ok: String) = run { safeStatus { c.api.imagesSync() }.toMessage(ok) }
    fun backup(ok: String) = run { safeStatus { c.api.backup() }.toMessage(ok) }
    fun restoreLatest(ok: String) = run { safeStatus { c.api.restoreLatest() }.toMessage(ok) }
    fun reset(ok: String) = run { safeStatus { c.api.reset() }.toMessage(ok) }

    fun upload(bytes: ByteArray, format: (Int, Int, Int) -> String) = run {
        when (val r = safeCall { c.api.upload(bytes.toRequestBody("text/csv".toMediaType())) }) {
            is ApiResult.Success -> format(r.data.total, r.data.added, r.data.updated)
            else -> r.toMessage("")
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun run(block: suspend () -> String?) = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = null) }
        val msg = block()
        _state.update { it.copy(busy = false, message = msg ?: it.message) }
        refreshStatus()
    }

    private suspend fun ApiResult<*>.toMessage(ok: String): String = when (this) {
        is ApiResult.Success -> ok
        is ApiResult.HttpError -> { if (code == 403) c.auth.onForbidden(); "HTTP $code: $message" }
        is ApiResult.NetworkError -> cause.message ?: cause.javaClass.simpleName
    }
}

/** relTime() of the web: "vor 5 Minuten", localised by Android. */
private fun relTime(iso: String): String = runCatching {
    DateUtils.getRelativeTimeSpanString(OffsetDateTime.parse(iso).toInstant().toEpochMilli()).toString()
}.getOrDefault("–")

/**
 * The web's admin overlay plus the controls of its nav bar (upload, sync):
 * UI settings, data and maintenance. Reached from the settings, only with
 * write access.
 */
@Composable
fun AdminScreen() {
    val vm = containerViewModel { AdminViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val res = LocalResources.current
    var enabled by remember(cfg) { mutableStateOf(cfg.randomEnabled) }
    var count by remember(cfg) { mutableStateOf(cfg.randomCount.toString()) }
    var hidden by remember(cfg) { mutableStateOf(cfg.hiddenNav.toSet()) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var pendingUpload by remember { mutableStateOf<Uri?>(null) }

    val saved = stringResource(R.string.admin_saved)
    val started = stringResource(R.string.admin_started)
    val done = stringResource(R.string.admin_done)
    val uploadConfirm = stringResource(R.string.admin_upload_confirm)
    val resetConfirm = stringResource(R.string.admin_reset_confirm)
    val restoreConfirm = stringResource(R.string.admin_restore_confirm)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingUpload = uri }
    LaunchedEffect(pendingUpload) {
        val uri = pendingUpload ?: return@LaunchedEffect
        pendingUpload = null
        confirm = uploadConfirm to {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) vm.upload(bytes) { t, a, u -> res.getQuantityString(R.plurals.admin_upload_done, t, t, a, u) }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        st.message?.let { Text(it, color = PortalColors.Accent) }

        Text(stringResource(R.string.admin_data), style = MaterialTheme.typography.titleMedium)
        st.status?.let { s ->
            Text(
                if (s.running) stringResource(R.string.admin_syncing) else stringResource(R.string.admin_card_data, relTime(s.cardsSyncedAt)),
                fontSize = 13.sp,
            )
            Text(stringResource(R.string.admin_counts, Format.count(s.collectionCount), Format.count(s.cardCount), Format.count(s.setCount)), fontSize = 12.sp, color = PortalColors.TextDim)
            if (s.lastError.isNotEmpty()) Text(stringResource(R.string.admin_sync_error, s.lastError), fontSize = 12.sp, color = PortalColors.Red)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !st.busy, onClick = { vm.sync(started) }) { Text(stringResource(R.string.admin_sync)) }
            HelpButton(stringResource(R.string.admin_sync_hint))
        }
        OutlinedButton(enabled = !st.busy, onClick = { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) }) {
            Text(stringResource(R.string.admin_upload))
        }
        if (st.status?.imageMirror?.enabled == true) {
            OutlinedButton(enabled = !st.busy, onClick = { vm.imagesSync(started) }) {
                Text(stringResource(R.string.admin_images))
            }
        }

        HorizontalDivider()
        Text(stringResource(R.string.admin_random), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.admin_random_enabled), modifier = Modifier.weight(1f))
            Switch(enabled, { enabled = it })
        }
        OutlinedTextField(
            count, { count = it.filter(Char::isDigit).take(2) },
            label = { Text(stringResource(R.string.admin_random_count)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(140.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.admin_menu), style = MaterialTheme.typography.titleMedium)
            HelpButton(stringResource(R.string.admin_menu_hint))
        }
        NavItem.entries.forEach { item ->
            Row(
                Modifier.fillMaxWidth().clickable { hidden = if (item.navKey in hidden) hidden - item.navKey else hidden + item.navKey },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = item.navKey !in hidden, onCheckedChange = null)
                Text(stringResource(item.label), modifier = Modifier.padding(start = 6.dp))
            }
        }
        Button(enabled = !st.busy, onClick = {
            // The web keeps every key it knows; unknown keys from a newer web UI survive.
            val keep = cfg.hiddenNav.filter { k -> NavItem.entries.none { it.navKey == k } }
            vm.saveConfig(enabled, count.toIntOrNull() ?: 6, (hidden + keep).toList(), saved)
        }) { Text(stringResource(R.string.admin_save)) }

        HorizontalDivider()
        Text(stringResource(R.string.admin_maintenance), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(enabled = !st.busy, onClick = { vm.backup(started) }) { Text(stringResource(R.string.admin_backup)) }
        OutlinedButton(enabled = !st.busy, onClick = { confirm = restoreConfirm to { vm.restoreLatest(done) } }) { Text(stringResource(R.string.admin_restore)) }
        OutlinedButton(enabled = !st.busy, onClick = { confirm = resetConfirm to { vm.reset(done) } }) {
            Text(stringResource(R.string.admin_reset), color = PortalColors.Red)
        }
    }

    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
