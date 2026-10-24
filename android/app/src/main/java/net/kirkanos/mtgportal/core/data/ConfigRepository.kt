package net.kirkanos.mtgportal.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.kirkanos.mtgportal.core.model.ImageUrls
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.PortalApi
import net.kirkanos.mtgportal.core.network.dto.UiConfigDto
import net.kirkanos.mtgportal.core.network.safeCall

/**
 * The admin's UI settings (/api/config): random cards, hidden navigation
 * entries, and whether images go through the portal's mirror.
 *
 * Falls back to the web UI's defaults when the server can't be reached
 * (loadUIConfig in shared.js).
 */
class ConfigRepository(
    private val api: PortalApi,
    settings: SettingsRepository,
    scope: CoroutineScope,
) {
    private val _config = MutableStateFlow(UiConfigDto(randomEnabled = true, randomCount = 6))
    val config: StateFlow<UiConfigDto> = _config.asStateFlow()

    /** Rebuilt whenever the server address or the mirror setting changes. */
    val imageUrls: StateFlow<ImageUrls> = combine(settings.baseUrl, _config) { base, cfg ->
        ImageUrls(base, cfg.imageMirror)
    }.stateIn(scope, SharingStarted.Eagerly, ImageUrls(settings.baseUrl.value, false))

    suspend fun refresh(): ApiResult<UiConfigDto> {
        val r = safeCall { api.config() }
        if (r is ApiResult.Success) _config.value = r.data
        return r
    }
}
