package net.kirkanos.mtgportal.core.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class UpdateRepositoryTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.close()

    private fun repo(current: Int) =
        UpdateRepository(OkHttpClient(), json, { server.url("/").toString() }, current)

    private fun release(code: Int) = MockResponse.Builder().code(200)
        .body("""{"versionCode":$code,"versionName":"0.1.$code","sha256":"x"}""").build()

    @Test
    fun newerReleaseIsOffered() = runTest {
        server.enqueue(release(161))
        val r = repo(160)
        assertEquals(UpdateCheck.Available(AppRelease(161, "0.1.161", "x")), r.check())
        assertEquals("/download/magic-portal.json", server.takeRequest().target)
        assertEquals(server.url("/download/magic-portal.apk").toString(), r.apkUrl)
    }

    @Test
    fun sameReleaseIsUpToDate() = runTest {
        server.enqueue(release(160))
        assertEquals(UpdateCheck.UpToDate, repo(160).check())
    }

    @Test
    fun portalWithoutApkIsUnavailable() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(UpdateCheck.Unavailable, repo(160).check())
        server.enqueue(MockResponse.Builder().code(200).body("<html>").build())
        assertEquals(UpdateCheck.Unavailable, repo(160).check())
    }
}
