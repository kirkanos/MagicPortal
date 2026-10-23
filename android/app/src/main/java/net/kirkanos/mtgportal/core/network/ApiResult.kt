package net.kirkanos.mtgportal.core.network

import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/** Outcome of a backend call, so screens can tell "server said no" from "no server". */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>

    /** The server answered with an error. 403 = wrong or missing password. */
    data class HttpError(val code: Int, val message: String) : ApiResult<Nothing>

    /** No answer at all: offline, wrong address, adb reverse missing. */
    data class NetworkError(val cause: Throwable) : ApiResult<Nothing>
}

suspend fun <T> safeCall(block: suspend () -> T): ApiResult<T> =
    try {
        ApiResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        // The backend answers errors with a plain-text body (http.Error).
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()?.trim()
        ApiResult.HttpError(e.code(), body?.takeIf { it.isNotEmpty() } ?: e.message())
    } catch (e: IOException) {
        ApiResult.NetworkError(e)
    } catch (e: IllegalStateException) {
        // An unparsable server address in the settings.
        ApiResult.NetworkError(e)
    }

inline fun <T, R> ApiResult<T>.map(f: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(f(data))
    is ApiResult.HttpError -> this
    is ApiResult.NetworkError -> this
}
