package net.kirkanos.mtgportal.scanner.catalog

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val body = """{"sets":[["snc","Streets of New Capenna","2022-04-29",467,"expansion"]],"cards":[["snc","47","Ledger Shredder","abc",1]]}"""

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun store() = CatalogStore(tmp.root, OkHttpClient(), server.url("/api/catalog/index").toString())

    @Test
    fun downloadsThenRevalidates() = runTest {
        server.enqueue(MockResponse.Builder().body(body).addHeader("ETag", "W/\"ci-1\"").build())
        server.enqueue(MockResponse.Builder().code(304).build())
        val s = store()

        val first = s.load() as CatalogStore.Result.Ready
        assertTrue(first.current)
        assertEquals("Ledger Shredder", first.index.find("SNC", "47")?.name)
        assertNull(server.takeRequest().headers["If-None-Match"])

        val second = s.load() as CatalogStore.Result.Ready
        assertEquals("W/\"ci-1\"", server.takeRequest().headers["If-None-Match"])
        // Unchanged: the parsed index is reused, not read again.
        assertSame(first.index, second.index)
    }

    @Test
    fun offlineUsesTheStoredCopy() = runTest {
        server.enqueue(MockResponse.Builder().body(body).addHeader("ETag", "W/\"ci-1\"").build())
        store().load()
        server.close()

        // A fresh store, as after an app restart, with the server gone.
        val r = store().load() as CatalogStore.Result.Ready
        assertFalse(r.current)
        assertEquals(1, r.index.size)
    }

    @Test
    fun nothingStoredAndNoServerFails() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        assertTrue(store().load() is CatalogStore.Result.Failed)
    }

    @Test
    fun aServerErrorKeepsTheOldCopy() = runTest {
        server.enqueue(MockResponse.Builder().body(body).addHeader("ETag", "W/\"ci-1\"").build())
        server.enqueue(MockResponse.Builder().code(502).build())
        val s = store()
        s.load()
        val r = s.load() as CatalogStore.Result.Ready
        assertFalse(r.current)
        assertEquals("Ledger Shredder", r.index.find("snc", "47")?.name)
    }
}
