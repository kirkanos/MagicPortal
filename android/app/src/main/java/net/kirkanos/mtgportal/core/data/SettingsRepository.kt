package net.kirkanos.mtgportal.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.settingsStore by preferencesDataStore(name = "settings")

/**
 * Persistent settings: the server address and the encrypted upload password.
 *
 * The values are mirrored into StateFlows because the HTTP interceptors read
 * them synchronously on every request. The file is read once at start - a few
 * bytes, before the first screen exists.
 */
class SettingsRepository(
    private val context: Context,
    private val defaultBaseUrl: String,
    private val secrets: SecretStore,
) {
    private val baseUrlKey = stringPreferencesKey("base_url")
    private val secretKey = stringPreferencesKey("upload_password")

    private val _baseUrl: MutableStateFlow<String>
    private val _secret: MutableStateFlow<String?>

    init {
        val prefs = runBlocking { context.settingsStore.data.first() }
        _baseUrl = MutableStateFlow(prefs[baseUrlKey] ?: defaultBaseUrl)
        _secret = MutableStateFlow(prefs[secretKey]?.let(secrets::decrypt))
    }

    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    /** The upload password in clear, only in memory. */
    val secret: StateFlow<String?> = _secret.asStateFlow()

    suspend fun setBaseUrl(url: String) {
        val clean = url.trim().trimEnd('/').ifEmpty { defaultBaseUrl }
        context.settingsStore.edit { it[baseUrlKey] = clean }
        _baseUrl.value = clean
    }

    suspend fun setSecret(pw: String?) {
        context.settingsStore.edit {
            if (pw.isNullOrEmpty()) it.remove(secretKey) else it[secretKey] = secrets.encrypt(pw)
        }
        _secret.value = pw?.takeIf { it.isNotEmpty() }
    }
}
