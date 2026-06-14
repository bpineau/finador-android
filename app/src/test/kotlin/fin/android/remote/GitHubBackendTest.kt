package fin.android.remote

import fin.android.crypto.B64
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubBackendTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun backend(token: String = "tok") =
        GitHubBackend("o", "r", "portfolio.fin", "main", token, baseUrl = server.url("/").toString().trimEnd('/'))

    @Test
    fun fetchDecodesWhitespaceWrappedBase64() {
        val b64 = B64.encode("hello world".toByteArray())
        val wrapped = b64.substring(0, 4) + "\\n" + b64.substring(4) // literal backslash-n -> JSON newline
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":"$wrapped","sha":"abc123"}"""))

        val fetched = backend().fetch()
        assertEquals("hello world", String(fetched.data))
        assertEquals("abc123", fetched.version)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
    }

    @Test
    fun fetchMissingThrowsMissing() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        assertThrows(RemoteError.Missing::class.java) { backend().fetch() }
    }

    @Test
    fun fetchUnauthorizedThrowsAuth() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertThrows(RemoteError.Auth::class.java) { backend().fetch() }
    }

    @Test
    fun pushReturnsNewSha() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":{"sha":"newsha"}}"""))
        val v = backend().push("data".toByteArray(), "oldsha", "msg")
        assertEquals("newsha", v)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(req.body.readUtf8().contains(B64.encode("data".toByteArray())))
    }

    @Test
    fun pushStaleShaThrowsConflict() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("{}"))
        assertThrows(RemoteError.Conflict::class.java) { backend().push("d".toByteArray(), "stale", "m") }
    }

    @Test
    fun serverErrorRetriesThenOffline() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        assertThrows(RemoteError.Offline::class.java) { backend().fetch() }
        assertEquals(2, server.requestCount) // initial + one retry
    }
}
