package net.kirkanos.mtgportal.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/** Placeholder Retrofit is built with; every request is rewritten to the real server. */
const val PLACEHOLDER_BASE = "http://portal.invalid/api/"

/**
 * Points every API request at the server currently configured in the
 * settings, so changing the base URL needs no new Retrofit instance.
 *
 * The configured URL may carry a path prefix (a portal behind
 * "https://host/mtg"); it is kept in front of "/api/...".
 */
class BaseUrlInterceptor(private val baseUrl: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = rewrite(request.url, baseUrl())
        return chain.proceed(request.newBuilder().url(target).build())
    }

    companion object {
        fun rewrite(url: HttpUrl, base: String): HttpUrl {
            val b = base.trim().trimEnd('/').toHttpUrlOrNull()
                ?: throw IllegalStateException("Invalid server address: $base")
            val prefix = b.encodedPath.trimEnd('/')
            return url.newBuilder()
                .scheme(b.scheme)
                .host(b.host)
                .port(b.port)
                .encodedPath(prefix + url.encodedPath)
                .build()
        }
    }
}

/** How a request deals with the upload password. */
enum class AuthMode {
    /** Default: the interceptor adds the stored password. */
    AUTO,

    /** The caller set (or deliberately omitted) the header itself. */
    EXPLICIT,
}

/**
 * Adds X-Upload-Password - the backend's single shared secret - to requests
 * that go to the configured server, and to nothing else. Runs after the
 * [BaseUrlInterceptor], so it sees the real host.
 *
 * The backend only checks the header on writes (and /api/activity), but
 * sending it on every request to the own server costs nothing and keeps the
 * rule simple. Images use a separate client without this interceptor, so the
 * password can never travel to Scryfall.
 */
class AuthInterceptor(
    private val baseUrl: () -> String,
    private val secret: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(AuthMode::class.java) == AuthMode.EXPLICIT) return chain.proceed(request)
        val pw = secret()
        val host = baseUrl().trim().toHttpUrlOrNull()
        if (pw.isNullOrEmpty() || host == null || !sameOrigin(request.url, host)) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(HEADER, pw).build())
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port

    companion object {
        const val HEADER = "X-Upload-Password"
    }
}
