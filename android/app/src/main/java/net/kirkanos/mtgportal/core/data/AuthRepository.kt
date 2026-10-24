package net.kirkanos.mtgportal.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.kirkanos.mtgportal.core.network.PortalApi

sealed interface AuthState {
    /** Not checked yet, or the server was unreachable. */
    data object Unknown : AuthState

    /** The server has no password configured: everything is writable. */
    data object Open : AuthState

    data object Locked : AuthState

    data object Unlocked : AuthState
}

/**
 * The unlock logic of the web UI (initCollectionUpload in shared.js), unchanged:
 *
 * 1. auth-check without a password: 204 means the server has none - open.
 * 2. Otherwise auth-check with the stored password: 204 - unlocked.
 * 3. Otherwise forget the stored password - locked.
 *
 * A network error leaves the stored password alone, exactly like the browser:
 * a dead spot must not log you out.
 */
class AuthRepository(
    private val api: PortalApi,
    private val settings: SettingsRepository,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val isUnlocked: Boolean
        get() = state.value == AuthState.Open || state.value == AuthState.Unlocked

    suspend fun probe() {
        _state.value = try {
            when {
                api.authCheck(null).code() == 204 -> {
                    settings.setSecret(null)
                    AuthState.Open
                }
                settings.secret.value.let { it != null && api.authCheck(it).code() == 204 } -> AuthState.Unlocked
                else -> {
                    settings.setSecret(null)
                    AuthState.Locked
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthState.Unknown
        }
    }

    sealed interface UnlockResult {
        data object Ok : UnlockResult
        data object WrongPassword : UnlockResult
        data class Failed(val message: String) : UnlockResult
    }

    suspend fun unlock(password: String): UnlockResult = try {
        if (api.authCheck(password).code() == 204) {
            settings.setSecret(password)
            _state.value = AuthState.Unlocked
            UnlockResult.Ok
        } else {
            UnlockResult.WrongPassword
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UnlockResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    /** The web UI has no logout; the app gets one because a phone gets lent out. */
    suspend fun lock() {
        settings.setSecret(null)
        if (_state.value == AuthState.Unlocked) _state.value = AuthState.Locked
    }

    /** A write came back 403: the password changed on the server. */
    suspend fun onForbidden() {
        settings.setSecret(null)
        _state.value = AuthState.Locked
    }
}
