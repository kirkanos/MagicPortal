package net.kirkanos.mtgportal.scanner.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.kirkanos.mtgportal.scanner.hash.HashIndex
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Keeps the catalog index - and the image hash index, when the server has
 * one - on the device and up to date.
 *
 * Every scanner start asks the server with If-None-Match; an unchanged file
 * costs a 304, a changed catalog ~4 MB gzipped. Without network the last copy
 * is used - scanning at a flea market must not depend on reception. The files
 * go to noBackupFilesDir rather than the cache: Android may clear the cache
 * exactly when there is no network to fetch it again.
 *
 * The parsed indexes stay in memory for the life of the process, keyed by
 * their ETags, so reopening the scanner is instant.
 */
class CatalogStore(
    private val dir: File,
    private val client: OkHttpClient,
    private val indexUrl: String,
    private val hashesUrl: String? = null,
) {
    sealed interface Result {
        /**
         * [current] is false when the server could not be asked and an older
         * copy is used. [hashes] is null when the server builds no hash index.
         */
        data class Ready(val index: CatalogIndex, val current: Boolean, val hashes: HashIndex? = null) : Result
        data class Failed(val error: Throwable) : Result
    }

    private val mutex = Mutex()
    private var parsed: Pair<String, CatalogIndex>? = null
    private var parsedHashes: Pair<String, HashIndex>? = null

    suspend fun load(): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            val catalog = Cached("index.json", indexUrl)
            val online = runCatching { catalog.refresh() }
            if (!catalog.file.exists()) return@withContext Result.Failed(online.exceptionOrNull() ?: IOException("no catalog"))
            val index = parsed?.takeIf { it.first == catalog.etag }?.second
                ?: try {
                    CatalogIndex.parse(catalog.file.readText()).also { parsed = catalog.etag to it }
                } catch (e: Exception) {
                    // A truncated or corrupt file: drop it so the next start downloads again.
                    catalog.drop()
                    return@withContext Result.Failed(e)
                }
            Result.Ready(index, online.isSuccess, hashesFor(index))
        }
    }

    /** The hash index is optional: any problem with it leaves the scanner on OCR alone. */
    private fun hashesFor(index: CatalogIndex): HashIndex? {
        val url = hashesUrl ?: return null
        val cached = Cached("hashes.bin", url)
        runCatching { cached.refresh() }
        if (!cached.file.exists()) return null
        // Tied to the catalog's printings, so a new catalog means parsing again.
        val key = cached.etag + "|" + System.identityHashCode(index)
        parsedHashes?.takeIf { it.first == key }?.let { return it.second }
        return runCatching { HashIndex.parse(cached.file.readBytes(), index) }
            .onFailure { cached.drop() }
            .getOrNull()
            ?.also { parsedHashes = key to it }
    }

    /** One file kept in step with the server by its ETag. */
    private inner class Cached(name: String, private val url: String) {
        val file = File(dir, name)
        private val etagFile = File(dir, "$name.etag")

        val etag: String get() = etagFile.takeIf { it.exists() }?.readText().orEmpty()

        fun drop() {
            file.delete(); etagFile.delete()
        }

        /** Downloads the file if it changed. Throws when the server cannot be asked. */
        fun refresh() {
            val known = etag.takeIf { file.exists() && it.isNotEmpty() }
            val request = Request.Builder()
                .url(url)
                // The file is the cache; OkHttp's HTTP cache would hold a second copy.
                .cacheControl(CacheControl.Builder().noStore().build())
                .apply { if (known != null) header("If-None-Match", known) }
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code == 304) return
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                dir.mkdirs()
                val tmp = File(dir, file.name + ".part")
                tmp.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
                // Rename last: a download cut short never replaces a good copy.
                if (!tmp.renameTo(file)) throw IOException("cannot store ${file.name}")
                etagFile.writeText(resp.header("ETag").orEmpty())
            }
        }
    }
}
