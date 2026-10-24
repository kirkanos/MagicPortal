package net.kirkanos.mtgportal.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.BuildConfig
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.data.AuthRepository
import net.kirkanos.mtgportal.core.data.AuthState
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.di.AppContainer

sealed interface ServerCheck {
    data object Idle : ServerCheck
    data object Running : ServerCheck
    data class Ok(val millis: Long) : ServerCheck
    data class HttpError(val code: Int) : ServerCheck
    data class NetworkError(val message: String) : ServerCheck
}

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val baseUrl = c.settings.baseUrl
    val auth = c.auth.state

    private val _check = MutableStateFlow<ServerCheck>(ServerCheck.Idle)
    val check: StateFlow<ServerCheck> = _check.asStateFlow()

    fun saveAndTest(url: String) = viewModelScope.launch {
        _check.value = ServerCheck.Running
        c.settings.setBaseUrl(url)
        val started = System.nanoTime()
        _check.value = when (val r = safeCall { c.api.health() }) {
            is ApiResult.Success ->
                if (r.data.code() == 204) ServerCheck.Ok((System.nanoTime() - started) / 1_000_000)
                else ServerCheck.HttpError(r.data.code())
            is ApiResult.HttpError -> ServerCheck.HttpError(r.code)
            is ApiResult.NetworkError -> ServerCheck.NetworkError(r.cause.message ?: r.cause.javaClass.simpleName)
        }
        c.refreshServerState()
    }

    suspend fun unlock(pw: String) = c.auth.unlock(pw)

    fun lock() = viewModelScope.launch { c.auth.lock() }
}

@Composable
fun SettingsScreen(onOpenAdmin: () -> Unit, onOpenExport: () -> Unit = {}) {
    val vm = containerViewModel { SettingsViewModel(it) }
    val baseUrl by vm.baseUrl.collectAsStateWithLifecycle()
    val auth by vm.auth.collectAsStateWithLifecycle()
    val check by vm.check.collectAsStateWithLifecycle()
    var url by remember(baseUrl) { mutableStateOf(baseUrl) }
    var unlockOpen by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.settings_server), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            supportingText = { Text(stringResource(R.string.settings_server_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = check != ServerCheck.Running, onClick = { vm.saveAndTest(url) }) {
            Text(stringResource(R.string.settings_save_test))
        }
        when (val r = check) {
            ServerCheck.Idle -> Unit
            ServerCheck.Running -> Text(stringResource(R.string.settings_testing))
            is ServerCheck.Ok -> Text(stringResource(R.string.settings_ok, r.millis), color = PortalColors.Green)
            is ServerCheck.HttpError -> Text(stringResource(R.string.settings_http_error, r.code), color = PortalColors.Red)
            is ServerCheck.NetworkError -> Text(stringResource(R.string.settings_network_error, r.message), color = PortalColors.Red)
        }

        Text(stringResource(R.string.settings_access), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(
                when (auth) {
                    AuthState.Unknown -> R.string.settings_state_unknown
                    AuthState.Open -> R.string.settings_state_open
                    AuthState.Locked -> R.string.settings_state_locked
                    AuthState.Unlocked -> R.string.settings_state_unlocked
                },
            ),
            color = PortalColors.TextDim,
        )
        when (auth) {
            AuthState.Locked, AuthState.Unknown ->
                Button(onClick = { unlockOpen = true }) { Text(stringResource(R.string.settings_unlock)) }
            AuthState.Unlocked ->
                OutlinedButton(onClick = { vm.lock() }) { Text(stringResource(R.string.settings_lock)) }
            AuthState.Open -> Unit
        }
        // Like the gear in the web's nav bar: only with write access.
        if (auth == AuthState.Open || auth == AuthState.Unlocked) {
            Button(onClick = onOpenAdmin) { Text(stringResource(R.string.admin_open)) }
        }

        // Handing app cards over to ManaBox, which stays the master.
        if (auth == AuthState.Open || auth == AuthState.Unlocked) {
            Text(stringResource(R.string.settings_export), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_export_hint), color = PortalColors.TextDim)
            OutlinedButton(onClick = onOpenExport) { Text(stringResource(R.string.settings_export_open)) }
        }

        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
        LanguagePicker()

        Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), color = PortalColors.TextDim)
    }

    // The dialog is its own window: hiding the keyboard from inside it does
    // not stick, the activity window takes it over when the dialog closes and
    // it covers the bottom bar. Hide it from here, after the dialog is gone.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(unlockOpen) {
        if (!unlockOpen) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    if (unlockOpen) UnlockDialog(onUnlock = vm::unlock, onDismiss = { unlockOpen = false })
}

/**
 * Switches the app language without a restart: AppCompat recreates the
 * activity, ViewModels keep their state. Stored by AppCompat itself.
 */
@Composable
private fun LanguagePicker() {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val active = if (current.startsWith("en")) "en" else "de"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("de" to "Deutsch", "en" to "English").forEach { (tag, label) ->
            FilterChip(
                selected = active == tag,
                onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) },
                label = { Text(label) },
            )
        }
    }
}

/** Replaces the browser's prompt() of the web UI's login button. */
@Composable
fun UnlockDialog(onUnlock: suspend (String) -> AuthRepository.UnlockResult, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val wrong = stringResource(R.string.unlock_wrong)
    val failed = stringResource(R.string.unlock_failed, "%s")
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unlock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it; error = null },
                    label = { Text(stringResource(R.string.unlock_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                error?.let { Text(it, color = PortalColors.Red) }
            }
        },
        confirmButton = {
            TextButton(enabled = pw.isNotEmpty() && !busy, onClick = {
                busy = true
                scope.launch {
                    when (val r = onUnlock(pw)) {
                        AuthRepository.UnlockResult.Ok -> onDismiss()
                        AuthRepository.UnlockResult.WrongPassword -> error = wrong
                        is AuthRepository.UnlockResult.Failed -> error = failed.replace("%s", r.message)
                    }
                    busy = false
                }
            }) { Text(stringResource(R.string.unlock_submit)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
