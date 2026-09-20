package fin.android.net

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A real HTTP server on loopback, for the tests of everything that fetches.
 *
 * It replaces okhttp's MockWebServer and is deliberately built on `com.sun.net.httpserver`, which
 * ships with the JDK: the unit tests run on the host JVM, so this costs the project no
 * dependency at all. The API is the small subset the tests were already using - enqueue canned
 * answers or install a dispatcher, then read back what was actually sent - so the assertions did
 * not have to change when the app's HTTP client did.
 *
 * It serves plain `http` on 127.0.0.1 with an ephemeral port. That is what MockWebServer did too,
 * and it is the right choice: a test that had to trust a self-signed certificate would be
 * testing the certificate, not the code.
 *
 * One deliberate difference: when the queue runs dry, this answers **404** rather than blocking
 * for ever as MockWebServer does. A test that under-enqueues then fails on its assertion instead
 * of hanging the suite.
 */
class FakeHttpServer {

    /** What a handler answers with. Mutable and fluent, so a test reads as one expression. */
    class FakeResponse {
        internal var code: Int = 200
        internal var body: String = ""
        internal val headers = mutableListOf<Pair<String, String>>()

        fun setResponseCode(code: Int): FakeResponse = apply { this.code = code }
        fun setBody(body: String): FakeResponse = apply { this.body = body }
        fun addHeader(name: String, value: String): FakeResponse = apply { headers += name to value }
    }

    /** One request as it actually arrived. [path] carries the query string, as sent. */
    class FakeRequest(
        val method: String,
        val path: String,
        val body: String,
        private val headers: Map<String, List<String>>,
    ) {
        /** The first value of [name], case-insensitively, or null. */
        fun getHeader(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

        override fun toString(): String = "$method $path"
    }

    /** Answers every request from the request itself, instead of from the queue. */
    fun interface Dispatcher {
        fun dispatch(request: FakeRequest): FakeResponse
    }

    var dispatcher: Dispatcher? = null

    private val queued = LinkedBlockingQueue<FakeResponse>()
    private val received = LinkedBlockingQueue<FakeRequest>()
    private var server: HttpServer? = null

    /** How many requests have arrived since [start]. */
    val requestCount: Int get() = count

    @Volatile private var count = 0

    fun start() {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        // One thread: requests are then recorded in the order they arrived, which is what the
        // tests that call takeRequest() several times in a row assert on.
        s.executor = Executors.newSingleThreadExecutor()
        s.createContext("/") { exchange ->
            exchange.use {
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                val query = exchange.requestURI.rawQuery
                val path = exchange.requestURI.rawPath + if (query != null) "?$query" else ""
                val request = FakeRequest(exchange.requestMethod, path, body, exchange.requestHeaders)
                count++
                received.put(request)

                val answer = dispatcher?.dispatch(request)
                    ?: queued.poll()
                    ?: FakeResponse().setResponseCode(404)
                for ((name, value) in answer.headers) exchange.responseHeaders.add(name, value)
                val bytes = answer.body.toByteArray(StandardCharsets.UTF_8)
                // -1 means "no body at all"; 0 would announce a chunked body of unknown length.
                exchange.sendResponseHeaders(answer.code, if (bytes.isEmpty()) -1L else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
            }
        }
        s.start()
        server = s
    }

    fun shutdown() {
        server?.let { s ->
            s.stop(0)
            (s.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
        server = null
    }

    /** The absolute URL of [path] on this server, e.g. `http://127.0.0.1:53124/v8/finance`. */
    fun url(path: String): String {
        val addr = checkNotNull(server) { "FakeHttpServer.url() before start()" }.address
        return "http://${addr.hostString}:${addr.port}${if (path.startsWith("/")) path else "/$path"}"
    }

    fun enqueue(response: FakeResponse) {
        queued.put(response)
    }

    /** The next request that arrived, waiting up to 10 s for it. */
    fun takeRequest(): FakeRequest =
        checkNotNull(takeRequest(10, TimeUnit.SECONDS)) { "no request arrived within 10 s" }

    /** The next request that arrived, or null if none did within the timeout. */
    fun takeRequest(timeout: Long, unit: TimeUnit): FakeRequest? = received.poll(timeout, unit)
}
