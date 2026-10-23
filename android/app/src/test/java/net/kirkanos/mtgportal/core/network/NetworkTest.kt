package net.kirkanos.mtgportal.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.kirkanos.mtgportal.core.network.dto.OwnedByNameRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BaseUrlInterceptorTest {
    private val placeholder = "${PLACEHOLDER_BASE}cards/facets?x=1".toHttpUrl()

    @Test
    fun hostPortAndScheme() {
        assertEquals(
            "http://127.0.0.1:8088/api/cards/facets?x=1",
            BaseUrlInterceptor.rewrite(placeholder, "http://127.0.0.1:8088").toString(),
        )
        assertEquals(
            "https://mtg.kirkanos.net/api/cards/facets?x=1",
            BaseUrlInterceptor.rewrite(placeholder, "https://mtg.kirkanos.net/").toString(),
        )
    }

    @Test
    fun keepsAPathPrefix() {
        assertEquals(
            "https://example.org/mtg/api/cards/facets?x=1",
            BaseUrlInterceptor.rewrite(placeholder, "https://example.org/mtg/").toString(),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsGarbage() {
        BaseUrlInterceptor.rewrite(placeholder, "not a url")
    }
}

/** The whole client stack against a fake backend: base URL, auth header, decoding. */
class PortalApiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private var secret: String? = null
    private lateinit var clients: HttpClients

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val base = server.url("/").toString()
        clients = HttpClients(tmp.newFolder(), { base }, { secret }, debug = false)
    }

    @After
    fun tearDown() = server.close()

    private fun fixture(name: String) =
        javaClass.getResource("/fixtures/$name")!!.readText()

    private fun json(name: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(fixture(name))
        .build()

    @Test
    fun passwordGoesOnlyToTheConfiguredServer() = runTest {
        secret = "s3cret"
        server.enqueue(MockResponse.Builder().code(204).build())
        clients.portalApi.health()
        assertEquals("s3cret", server.takeRequest().headers[AuthInterceptor.HEADER])

        // The image client has no interceptor at all.
        server.enqueue(MockResponse.Builder().code(200).body("img").build())
        clients.images.newCall(okhttp3.Request.Builder().url(server.url("/img/cards/x.jpg")).build()).execute().close()
        val img = server.takeRequest()
        assertNull(img.headers[AuthInterceptor.HEADER])
        // Scryfall's CDN answers 400 to a generic okhttp User-Agent.
        assertTrue(img.headers["User-Agent"]!!.startsWith("MagicPortal-Android"))
    }

    @Test
    fun authProbeControlsTheHeaderItself() = runTest {
        secret = "stored"
        server.enqueue(MockResponse.Builder().code(403).build())
        server.enqueue(MockResponse.Builder().code(204).build())

        assertEquals(403, clients.portalApi.authCheck(null).code())
        assertNull("probe without password must not get the stored one", server.takeRequest().headers[AuthInterceptor.HEADER])

        assertEquals(204, clients.portalApi.authCheck("typed").code())
        assertEquals("typed", server.takeRequest().headers[AuthInterceptor.HEADER])
    }

    @Test
    fun decodesRealResponses() = runTest {
        server.enqueue(json("cards_page.json"))
        val page = clients.portalApi.cards(sort = "value-desc", limit = 3)
        val req = server.takeRequest()
        assertEquals("/api/cards", req.url.encodedPath)
        assertEquals("value-desc", req.url.queryParameter("sort"))
        assertNull("unset filters are not sent", req.url.queryParameter("rarity"))
        assertEquals(3, page.groups.size)
        assertTrue(page.groups.all { it.variants.isNotEmpty() && it.name.isNotEmpty() })

        server.enqueue(json("facets.json"))
        val facets = clients.portalApi.facets()
        assertTrue(facets.stats.entries > 0 && facets.sets.isNotEmpty())

        server.enqueue(json("sets.json"))
        val sets = clients.portalApi.sets()
        assertEquals(setOf("lea", "snc", "neo"), sets.keys)

        server.enqueue(json("binders.json"))
        assertTrue(clients.portalApi.binders().all { it.type in setOf("binder", "list", "") })

        server.enqueue(json("status.json"))
        assertTrue(clients.portalApi.status().cardCount > 100_000)

        server.enqueue(json("config.json"))
        clients.portalApi.config()

        server.enqueue(json("prints.json"))
        assertTrue(clients.portalApi.prints("Lightning Bolt").isNotEmpty())

        server.enqueue(json("set_cards.json"))
        assertTrue(clients.portalApi.setCards("snc").all { it.owned == null })
    }

    @Test
    fun decodesTheAggregateShapes() = runTest {
        server.enqueue(json("editions.json"))
        val eds = clients.portalApi.editions()
        assertEquals(467, eds[0].total)
        assertNull("unknown set has no total", eds[1].total)

        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body("[" + fixture("set_card_owned.json") + "]").build())
        val owned = clients.portalApi.setCards("snc").single().owned!!
        assertEquals(3, owned.copies)
        assertEquals(mapOf("de" to 3), owned.byLang)

        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body("""{"island":768,"black lotus":0}""").build())
        val res = clients.portalApi.ownedByName(OwnedByNameRequest(listOf("Island", "Black Lotus")))
        assertEquals(768, res["island"])

        server.takeRequest() // editions
        server.takeRequest() // set cards
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/api/collection/owned", post.url.encodedPath)
        assertEquals("""{"names":["Island","Black Lotus"]}""", post.body!!.utf8())
    }

    @Test
    fun errorsAreClassified() = runTest {
        server.enqueue(MockResponse.Builder().code(403).body("Wrong or missing password\n").build())
        val r = safeCall { clients.portalApi.config() }
        assertEquals(ApiResult.HttpError(403, "Wrong or missing password"), r)

        server.close()
        assertTrue(safeCall { clients.portalApi.config() } is ApiResult.NetworkError)
    }
}
