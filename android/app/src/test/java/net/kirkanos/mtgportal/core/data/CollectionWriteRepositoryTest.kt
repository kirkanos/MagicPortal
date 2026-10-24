package net.kirkanos.mtgportal.core.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.HttpClients
import net.kirkanos.mtgportal.scanner.MatchSource
import net.kirkanos.mtgportal.scanner.ScanSessionResult
import net.kirkanos.mtgportal.scanner.ScannedCard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CollectionWriteRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var repo: CollectionWriteRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val base = server.url("/").toString()
        repo = CollectionWriteRepository(HttpClients(tmp.newFolder(), { base }, { "pw" }, debug = false).portalApi)
    }

    @After
    fun tearDown() = server.close()

    private val scan = ScanSessionResult(
        listOf(
            ScannedCard("snc", "47", "id1", "Ledger Shredder", foil = "foil", quantity = 2, source = MatchSource.OCR_SET_NUMBER, confidence = 1f),
            ScannedCard("sth", "131", "id2", "Victual Sliver", language = "de", source = MatchSource.HASH, confidence = 0.8f),
        ),
    )

    private fun ok(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun oneRequestPerSessionWithItsKey() = runTest {
        server.enqueue(ok("""{"items":[{"id":5,"quantity":2,"origin":"app"},{"id":6,"quantity":1,"merged":true}],"added":1,"merged":1}"""))
        val r = repo.addScan(scan, BinderTarget("Scans", "binder"), "excellent", "session-1")
        assertTrue(r is ApiResult.Success)
        assertEquals(1, (r as ApiResult.Success).data.merged)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/collection/cards", req.url.encodedPath)
        assertEquals("session-1", req.headers["Idempotency-Key"])
        val items = Json.parseToJsonElement(req.body!!.utf8()).jsonObject["items"]!!.jsonArray
        val first = items[0].jsonObject
        assertEquals("snc", first["setCode"]!!.jsonPrimitive.content)
        assertEquals(2, first["quantity"]!!.jsonPrimitive.int)
        assertEquals("foil", first["foil"]!!.jsonPrimitive.content)
        assertEquals("excellent", first["condition"]!!.jsonPrimitive.content)
        assertEquals("Scans", first["binderName"]!!.jsonPrimitive.content)
        assertEquals("de", items[1].jsonObject["language"]!!.jsonPrimitive.content)
    }

    @Test
    fun withoutBinderNoBinderType() {
        val req = CollectionWriteRepository.toRequest(scan, BinderTarget(""), "near_mint")
        assertTrue(req.items.all { it.binderName == "" && it.binderType == "" })
    }

    @Test
    fun theServersReasonIsReadable() = runTest {
        server.enqueue(
            MockResponse.Builder().code(404).addHeader("Content-Type", "application/json")
                .body("""{"error":"STH #131 is not in the catalog","index":1}""").build(),
        )
        val r = repo.addScan(scan, BinderTarget("Scans"), "near_mint", "k")
        assertEquals(ApiResult.HttpError(404, "STH #131 is not in the catalog (#2)"), r)
    }
}

class HandoverRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var repo: CollectionWriteRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val base = server.url("/").toString()
        repo = CollectionWriteRepository(HttpClients(tmp.newFolder(), { base }, { "pw" }, debug = false).portalApi)
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun exportCarriesIdCountAndFile() = runTest {
        val csv = "Binder Name,Binder Type,Name\nScans,binder,Victual Sliver\n"
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/csv").addHeader("X-Export-Id", "7").addHeader("X-Export-Cards", "3").body(csv).build())
        val r = repo.exportForManabox() as ApiResult.Success
        assertEquals(7L, r.data.id)
        assertEquals(3, r.data.cards)
        assertEquals(csv, String(r.data.csv))
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun confirmTwiceIsReadable() = runTest {
        server.enqueue(MockResponse.Builder().code(409).addHeader("Content-Type", "application/json").body("""{"error":"export already confirmed at 2026-10-24T15:00:00Z"}""").build())
        assertEquals(ApiResult.HttpError(409, "export already confirmed at 2026-10-24T15:00:00Z"), repo.confirmExport(7))
        assertEquals("/api/collection/export/7/confirm", server.takeRequest().url.encodedPath)
    }

    @Test
    fun binderNamesAreOnePathSegment() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"name":"Box","type":"list","rows":2,"merged":1}""").build())
        repo.renameBinder("Scans 1/2", net.kirkanos.mtgportal.core.network.dto.BinderPatchDto(name = "Box"))
        assertEquals("/api/binders/Scans%201%2F2", server.takeRequest().url.encodedPath)
    }
}
