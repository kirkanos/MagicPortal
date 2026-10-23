package net.kirkanos.mtgportal.core.network

import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The two HTTP clients of the app, deliberately separate:
 *
 * - [api] talks to the portal. Its disk cache is what makes the backend's
 *   ETags pay off: /api/cards/facets, /api/sets, /api/editions and the card
 *   pages answer "Cache-Control: no-cache" with a weak ETag, OkHttp stores the
 *   body and revalidates with If-None-Match, and an unchanged collection costs
 *   a 304 with no body - the same thing the browser does.
 * - [images] fetches card images and symbols, from the portal's mirror or
 *   straight from Scryfall. It carries no auth interceptor at all, so the
 *   upload password can never leave towards a third party.
 */
class HttpClients(
    cacheDir: File,
    baseUrl: () -> String,
    secret: () -> String?,
    debug: Boolean,
) {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    val api: OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(cacheDir, "http-api"), 50L * 1024 * 1024))
        .addInterceptor(BaseUrlInterceptor(baseUrl))
        .addInterceptor(AuthInterceptor(baseUrl, secret))
        .apply {
            if (debug) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader(AuthInterceptor.HEADER)
                    },
                )
            }
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val images: OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(cacheDir, "http-images"), 250L * 1024 * 1024))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val portalApi: PortalApi = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE)
        .client(api)
        // Scalars first: text/plain answers (the wishlist export) as String.
        .addConverterFactory(ScalarsConverterFactory.create())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(PortalApi::class.java)
}
