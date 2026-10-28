package net.kirkanos.mtgportal.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.ui.components.HelpButton
import net.kirkanos.mtgportal.core.data.AuthRepository
import net.kirkanos.mtgportal.core.data.AuthState
import net.kirkanos.mtgportal.core.data.SettingsRepository
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.di.AppContainer

class SetupViewModel(private val c: AppContainer) : ViewModel() {
    enum class Step { SERVER, PASSWORD, DONE }

    private val _step = MutableStateFlow(Step.SERVER)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val _check = MutableStateFlow<ServerCheck>(ServerCheck.Idle)
    val check: StateFlow<ServerCheck> = _check.asStateFlow()

    /** Stores the address only once the server answered like a portal. */
    fun connect(input: String) = viewModelScope.launch {
        val url = SettingsRepository.normalizeUrl(input)
        _check.value = ServerCheck.Running
        val started = System.nanoTime()
        val code = try {
            withContext(Dispatchers.IO) { c.http.health(url) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _check.value = ServerCheck.NetworkError(e.message ?: e.javaClass.simpleName)
            return@launch
        }
        if (code != 204) {
            _check.value = ServerCheck.HttpError(code)
            return@launch
        }
        _check.value = ServerCheck.Ok((System.nanoTime() - started) / 1_000_000)
        c.settings.setBaseUrl(url)
        c.auth.probe()
        // A server without a password needs nothing more.
        _step.value = if (c.auth.state.value == AuthState.Locked) Step.PASSWORD else Step.DONE
    }

    suspend fun unlock(pw: String): AuthRepository.UnlockResult =
        c.auth.unlock(pw).also { if (it == AuthRepository.UnlockResult.Ok) _step.value = Step.DONE }

    fun skipPassword() {
        _step.value = Step.DONE
    }
}

/**
 * First start: the release build has no server built in, so it asks for the
 * address and, when the server has one, the upload password.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val vm = containerViewModel { SetupViewModel(it) }
    val step by vm.step.collectAsStateWithLifecycle()
    LaunchedEffect(step) { if (step == SetupViewModel.Step.DONE) onDone() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
                HelpButton(stringResource(if (step == SetupViewModel.Step.PASSWORD) R.string.setup_password_intro else R.string.setup_intro))
            }
            when (step) {
                SetupViewModel.Step.SERVER -> ServerStep(vm)
                SetupViewModel.Step.PASSWORD -> PasswordStep(vm)
                SetupViewModel.Step.DONE -> Unit
            }
        }
    }
}

@Composable
private fun ServerStep(vm: SetupViewModel) {
    val check by vm.check.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    val busy = check == ServerCheck.Running
    val connect = { if (url.isNotBlank() && !busy) vm.connect(url) }

    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.setup_server)) },
        placeholder = { Text("https://mtg.example.org") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { connect() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(enabled = url.isNotBlank() && !busy, onClick = connect) { Text(stringResource(R.string.setup_connect)) }
    when (val r = check) {
        ServerCheck.Idle, is ServerCheck.Ok -> Unit
        ServerCheck.Running -> Text(stringResource(R.string.settings_testing))
        is ServerCheck.HttpError -> Text(stringResource(R.string.setup_not_portal, r.code), color = PortalColors.Red)
        is ServerCheck.NetworkError -> Text(stringResource(R.string.settings_network_error, r.message), color = PortalColors.Red)
    }
}

@Composable
private fun PasswordStep(vm: SetupViewModel) {
    var pw by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    val wrong = stringResource(R.string.unlock_wrong)
    val failed = stringResource(R.string.unlock_failed, "%s")
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val submit = {
        if (pw.isNotEmpty() && !busy) {
            busy = true
            scope.launch {
                when (val r = vm.unlock(pw)) {
                    AuthRepository.UnlockResult.Ok -> Unit
                    AuthRepository.UnlockResult.WrongPassword -> error = wrong
                    is AuthRepository.UnlockResult.Failed -> error = failed.replace("%s", r.message)
                }
                busy = false
            }
        }
    }

    OutlinedTextField(
        value = pw,
        onValueChange = { pw = it; error = null },
        label = { Text(stringResource(R.string.unlock_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { submit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, color = PortalColors.Red) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = pw.isNotEmpty() && !busy, onClick = submit) { Text(stringResource(R.string.unlock_submit)) }
        TextButton(enabled = !busy, onClick = vm::skipPassword) { Text(stringResource(R.string.setup_skip)) }
    }
}
