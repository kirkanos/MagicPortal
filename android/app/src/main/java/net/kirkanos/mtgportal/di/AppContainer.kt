package net.kirkanos.mtgportal.di

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.BuildConfig
import net.kirkanos.mtgportal.core.data.AuthRepository
import net.kirkanos.mtgportal.core.data.ConfigRepository
import net.kirkanos.mtgportal.core.data.SecretStore
import net.kirkanos.mtgportal.core.data.SettingsRepository
import net.kirkanos.mtgportal.core.data.WishlistRepository
import net.kirkanos.mtgportal.core.network.HttpClients

/**
 * Manual dependency injection: everything the app needs, built once.
 * Small enough that a framework would cost more than it saves.
 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(context, BuildConfig.DEFAULT_BASE_URL, SecretStore())

    val http = HttpClients(
        cacheDir = context.cacheDir,
        baseUrl = { settings.baseUrl.value },
        secret = { settings.secret.value },
        debug = BuildConfig.DEBUG,
        userAgent = "MagicPortal-Android/${BuildConfig.VERSION_NAME} (+https://github.com/kirkanos/mtg-portal)",
    )

    val api = http.portalApi
    val auth = AuthRepository(api, settings)
    val config = ConfigRepository(api, settings, appScope)
    val wishlist = WishlistRepository(api, auth)

    /** Coil with SVG support (set and mana symbols) on the image-only client. */
    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { http.images }))
            add(SvgDecoder.Factory())
        }
        // Why an image stays empty is otherwise invisible.
        .apply { if (BuildConfig.DEBUG) logger(coil3.util.DebugLogger()) }
        .build()

    init {
        appScope.launch { refreshServerState() }
        // A different server is a different world: probe and reload config.
        appScope.launch { settings.baseUrl.drop(1).collect { refreshServerState() } }
    }

    suspend fun refreshServerState() {
        auth.probe()
        config.refresh()
        wishlist.load()
    }
}
