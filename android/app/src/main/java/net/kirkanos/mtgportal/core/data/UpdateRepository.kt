package net.kirkanos.mtgportal.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** What CI publishes next to the APK: download/magic-portal.json on the portal. */
@Serializable
data class AppRelease(val versionCode: Int, val versionName: String, val sha256: String? = null)

sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Running : UpdateCheck
    data object UpToDate : UpdateCheck
    data class Available(val release: AppRelease) : UpdateCheck
    /** The server publishes no APK (a portal without the Android CI), or it failed. */
    data object Unavailable : UpdateCheck
}

/**
 * Asks the configured portal whether it has a newer APK than this one. The
 * APK comes from the same server the app talks to, so every self-hosted
 * portal hands out its own build; without one the check just says so.
 */
class UpdateRepository(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: () -> String,
    private val currentVersion: Int,
) {
    private val _state = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
    val state: StateFlow<UpdateCheck> = _state.asStateFlow()

    /** The version whose banner was closed; it does not come back until a newer one. */
    private val _dismissed = MutableStateFlow(0)
    val dismissed: StateFlow<Int> = _dismissed.asStateFlow()

    val apkUrl: String get() = baseUrl().trimEnd('/') + "/download/magic-portal.apk"

    suspend fun check(): UpdateCheck {
        if (baseUrl().isEmpty()) return UpdateCheck.Idle
        _state.value = UpdateCheck.Running
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val url = (baseUrl().trimEnd('/') + "/download/magic-portal.json").toHttpUrlOrNull()
                    ?: return@runCatching UpdateCheck.Unavailable
                client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use UpdateCheck.Unavailable
                    val release = json.decodeFromString(AppRelease.serializer(), r.body.string())
                    if (release.versionCode > currentVersion) UpdateCheck.Available(release) else UpdateCheck.UpToDate
                }
            }.getOrDefault(UpdateCheck.Unavailable)
        }
        _state.value = result
        return result
    }

    fun dismiss(versionCode: Int) {
        _dismissed.value = versionCode
    }
}
