package fin.android.remote

import fin.android.crypto.B64
import fin.android.net.FakeHttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubBackendTest {
    private lateinit var server: FakeHttpServer

    @Before fun setUp() { server = FakeHttpServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun backend(token: String = "tok") =
        GitHubBackend("o", "r", "portfolio.fin", "main", token, baseUrl = server.url("/").trimEnd('/'))

    @Test
    fun fetchDecodesWhitespaceWrappedBase64() {
        val b64 = B64.encode("hello world".toByteArray())
        val wrapped = b64.substring(0, 4) + "\\n" + b64.substring(4) // literal backslash-n -> JSON newline
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody("""{"content":"$wrapped","sha":"abc123"}"""))

        val fetched = backend().fetch()
        assertEquals("hello world", String(fetched.data))
        assertEquals("abc123", fetched.version)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertNull(req.getHeader("X-GitHub-Api-Version"))
    }

    @Test
    fun largeLedgerReadsTheExactBlobNamedByTheContentsResponse() {
        val bytes = ByteArray(1_048_577) { 'x'.code.toByte() }
        val sha = "f".repeat(40)
        server.enqueue(FakeHttpServer.FakeResponse().setBody("""{"encoding":"none","content":"","sha":"$sha"}"""))
        server.enqueue(FakeHttpServer.FakeResponse().setBody(
            """{"encoding":"base64","content":"${B64.encode(bytes)}","sha":"$sha"}"""))
        val fetched = backend().fetch()
        assertArrayEquals(bytes, fetched.data)
        assertEquals(sha, fetched.version)
        assertEquals("application/vnd.github.object+json", server.takeRequest().getHeader("Accept"))
        assertEquals("/repos/o/r/git/blobs/$sha", server.takeRequest().path)
    }

    @Test
    fun fetchAndPushAddressTheSameEscapedLedgerPath() {
        val remote = GitHubBackend("o", "r", "archive/a #1?.fin", "topic/nested", "tok", server.url("").trimEnd('/'))
        server.enqueue(FakeHttpServer.FakeResponse().setBody("""{"content":"YQ==","sha":"s"}"""))
        remote.fetch()
        server.enqueue(FakeHttpServer.FakeResponse().setBody("""{"content":{"sha":"s2"}}"""))
        remote.push(byteArrayOf(1), "s", "edit")
        val path = "/repos/o/r/contents/archive/a%20%231%3F.fin"
        assertEquals("$path?ref=topic%2Fnested", server.takeRequest().path)
        assertEquals(path, server.takeRequest().path)
    }

    @Test
    fun fetchMissingThrowsMissing() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(404).setBody("{}"))
        assertThrows(RemoteError.Missing::class.java) { backend().fetch() }
    }

    @Test
    fun fetchUnauthorizedThrowsAuth() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(401).setBody("{}"))
        assertThrows(RemoteError.Auth::class.java) { backend().fetch() }
    }

    @Test
    fun pushReturnsNewSha() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody("""{"content":{"sha":"newsha"}}"""))
        val v = backend().push("data".toByteArray(), "oldsha", "msg")
        assertEquals("newsha", v)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(req.body.contains(B64.encode("data".toByteArray())))
    }

    @Test
    fun pushStaleShaThrowsConflict() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(409).setBody("{}"))
        assertThrows(RemoteError.Conflict::class.java) { backend().push("d".toByteArray(), "stale", "m") }
    }

    @Test
    fun serverErrorRetriesThenOffline() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(503))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(503))
        assertThrows(RemoteError.Offline::class.java) { backend().fetch() }
        assertEquals(2, server.requestCount) // initial + one retry
    }

    @Test
    fun checkAccessReadsTheRepositoryMetadata() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody("{}"))
        backend().checkAccess()
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/repos/o/r", req.path)
    }

    @Test
    fun checkAccessTreatsAnInvisibleRepositoryAsAuth() {
        for (code in listOf(401, 403, 404)) {
            server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(code).setBody("{}"))
            assertThrows(RemoteError.Auth::class.java) { backend().checkAccess() }
        }
    }

    @Test
    fun pushToMissingRepositoryOrBranchIsAuthNotOffline() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(404).setBody("{}"))
        assertThrows(RemoteError.Auth::class.java) { backend().push("d".toByteArray(), null, "m") }
    }
}
