package net.kirkanos.mtgportal.scanner.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Keeps the catalog index on the device and up to date.
 *
 * Every scanner start asks the server with If-None-Match; an unchanged
 * catalog costs a 304, a changed one ~4 MB gzipped. Without network the last
 * copy is used - scanning at a flea market must not depend on reception. The
 * file goes to noBackupFilesDir rather than the cache: Android may clear the
 * cache exactly when there is no network to fetch it again.
 *
 * The parsed index stays in memory for the life of the process, keyed by its
 * ETag, so reopening the scanner is instant.
 */
class CatalogStore(
    private val dir: File,
    private val client: OkHttpClient,
    private val url: String,
) {
    sealed interface Result {
        /** [current] is false when the server could not be asked and an older copy is used. */
        data class Ready(val index: CatalogIndex, val current: Boolean) : Result
        data class Failed(val error: Throwable) : Result
    }

    private val mutex = Mutex()
    private var parsed: Pair<String, CatalogIndex>? = null

    private val file get() = File(dir, "index.json")
    private val etagFile get() = File(dir, "index.etag")

    suspend fun load(): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            val online = runCatching { refresh() }
            if (!file.exists()) return@withContext Result.Failed(online.exceptionOrNull() ?: IOException("no catalog"))
            val etag = etagFile.takeIf { it.exists() }?.readText().orEmpty()
            val index = parsed?.takeIf { it.first == etag }?.second
                ?: try {
                    CatalogIndex.parse(file.readText()).also { parsed = etag to it }
                } catch (e: Exception) {
                    // A truncated or corrupt file: drop it so the next start downloads again.
                    file.delete(); etagFile.delete()
                    return@withContext Result.Failed(e)
                }
            Result.Ready(index, online.isSuccess)
        }
    }

    /** Downloads the index if it changed. Throws when the server cannot be asked. */
    private fun refresh() {
        val etag = etagFile.takeIf { file.exists() && it.exists() }?.readText()
        val request = Request.Builder()
            .url(url)
            // The file below is the cache; OkHttp's HTTP cache would hold a second copy.
            .cacheControl(CacheControl.Builder().noStore().build())
            .apply { if (!etag.isNullOrEmpty()) header("If-None-Match", etag) }
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code == 304) return
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            dir.mkdirs()
            val tmp = File(dir, "index.json.part")
            tmp.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
            // Rename last: a download cut short never replaces a good copy.
            if (!tmp.renameTo(file)) throw IOException("cannot store catalog")
            etagFile.writeText(resp.header("ETag").orEmpty())
        }
    }
}
