package net.kirkanos.mtgportal.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.ActivityDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.network.safeStatus
import net.kirkanos.mtgportal.core.ui.components.ErrorRetry
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.di.AppContainer
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class ActivityViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<ApiResult<List<ActivityDto>>?>(null)
    val state: StateFlow<ApiResult<List<ActivityDto>>?> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = null
        val r = safeCall { c.api.activity() }
        if (r is ApiResult.HttpError && r.code == 403) c.auth.onForbidden()
        _state.value = r
    }

    fun clear() = viewModelScope.launch {
        safeStatus { c.api.clearActivity() }
        load()
    }
}

/** fmtTs() in activity.js: the server's RFC 3339 time in the device's zone. */
internal fun formatTs(iso: String, locale: Locale): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale))
}.getOrDefault(iso)

/** The server's activity log (activity.html), newest first; only with the password. */
@Composable
fun ActivityScreen() {
    val vm = containerViewModel { ActivityViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

    when (val r = st) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ApiResult.HttpError ->
            if (r.code == 403) Text(stringResource(R.string.act_login_needed), color = PortalColors.TextDim, modifier = Modifier.padding(24.dp))
            else ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.NetworkError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.Success -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.load() }) { Text(stringResource(R.string.act_refresh)) }
                    OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.act_clear)) }
                }
            }
            if (r.data.isEmpty()) item { Text(stringResource(R.string.act_empty), color = PortalColors.TextDim) }
            items(r.data) { a -> ActivityRow(a, locale) }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(R.string.act_clear_confirm)) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.clear() }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun ActivityRow(a: ActivityDto, locale: Locale) {
    val error = a.level == "error"
    val source = when (a.source) {
        "Restore" -> stringResource(R.string.act_src_restore)
        "Reset" -> stringResource(R.string.act_src_reset)
        "Aktivität" -> stringResource(R.string.act_src_log)
        else -> a.source
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (error) PortalColors.Red.copy(alpha = 0.12f) else PortalColors.BgCard).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(source, fontSize = 12.sp, color = if (error) PortalColors.Red else PortalColors.Accent)
            Text(formatTs(a.ts, locale), fontSize = 12.sp, color = PortalColors.TextDim)
        }
        Text((if (error) "⚠ " else "") + a.message, fontSize = 13.sp, color = if (error) PortalColors.Red else PortalColors.Text)
    }
}
