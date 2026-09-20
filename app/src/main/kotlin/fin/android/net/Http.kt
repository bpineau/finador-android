// Package net is the app's HTTP transport: one small object over the platform's
// HttpURLConnection, shared by the market providers and by the GitHub sync backend. It knows
// nothing about either: no Android import, no domain type, so it unit-tests on the host JVM.
package fin.android.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException

/**
 * The app's whole HTTP stack, on top of the platform's [HttpURLConnection].
 *
 * There is no third-party HTTP client here on purpose. Every call this app makes is a GET or a
 * JSON POST/PUT that wants a status code and a body as a String, with a browser-looking
 * User-Agent, a timeout and one polite retry; `java.net` has done exactly that since API 1 and
 * will still do it when today's client libraries have had another two incompatible major
 * versions. (The JDK 11 `java.net.http.HttpClient` is NOT part of the Android API, so it is not
 * an option: this is the platform's HTTP client.)
 *
 * What the callers get, and nothing more:
 *
 * - [url], which builds `base?a=1&b=2` with the values escaped;
 * - [send], which performs one request with headers, an optional body, timeouts, and ONE retry
 *   when the host throttles (429), fails (5xx) or does not answer at all;
 * - [Response], which always reports a status and a body, including for a 4xx/5xx - the platform
 *   throws on those and hands the body over on a separate stream, which is the one sharp edge of
 *   this API and is handled here so no caller has to know about it.
 *
 * Gzip is not configured and must not be: the platform adds `Accept-Encoding: gzip` itself and
 * decodes the answer transparently, but only as long as the caller does not set that header by
 * hand. Redirects are followed by default, within the same protocol; every base URL here is
 * https, so a redirect can only lead to https.
 *
 * Everything here is blocking. Android forbids network calls on the main thread, and every caller
 * already runs on a background dispatcher.
 */
internal object Http {

    /** Copied from the Go implementation (yahoo.go) so the providers look like a real browser. */
    const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
    const val DEFAULT_READ_TIMEOUT_MS = 15_000

    // Spelled exactly as it was on the wire before, charset included, so the providers see
    // byte-for-byte the request they used to get.
    private const val MEDIA_JSON = "application/json; charset=utf-8"

    /**
     * One HTTP exchange. [code] is the status the host answered with, or **0 when it never
     * answered** (DNS failure, no route, timeout, TLS failure), in which case [failure] holds the
     * exception. [body] is the response body decoded as text - for an error status too - and is
     * empty when there was none.
     */
    data class Response(
        val code: Int,
        val body: String,
        val headers: Map<String, List<String>> = emptyMap(),
        val failure: IOException? = null,
    ) {
        /** True when the host answered anything at all, whatever the status. */
        val answered: Boolean get() = code != 0

        /** The body when the status satisfies [ok], else null. The shape every provider wants. */
        fun bodyIf(ok: (Int) -> Boolean): String? = if (answered && ok(code)) body else null

        /** Every value of [name], case-insensitively; empty when the header was not sent. */
        fun header(name: String): List<String> =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()
    }

    /**
     * Builds `base?name=value&...`, percent-escaping each name and value.
     *
     * The escape set keeps the RFC 3986 unreserved characters plus `,`, which is legal unescaped
     * in a query and is how the providers' own documentation writes a symbol list. Everything
     * else - including `+`, which too many servers still read as a space - is percent-escaped
     * from its UTF-8 bytes.
     */
    fun url(base: String, vararg params: Pair<String, String>): String {
        if (params.isEmpty()) return base
        val sb = StringBuilder(base)
        sb.append(if (base.contains('?')) '&' else '?')
        params.forEachIndexed { i, (name, value) ->
            if (i > 0) sb.append('&')
            sb.append(escape(name)).append('=').append(escape(value))
        }
        return sb.toString()
    }

    /**
     * Percent-escapes one PATH segment, keeping every character RFC 3986 allows there (`pchar`:
     * unreserved, sub-delims, `:` and `@`). A ticker is interpolated into a path, and tickers
     * carry both characters that must stay literal (`EURUSD=X`) and characters that must not
     * (`^IRX`, whose caret is illegal in a URL and becomes `%5E`).
     */
    fun escapePath(s: String): String = escapeKeeping(s, "-._~!$&'()*+,;=:@")

    /** Percent-escapes one query component. See [url] for the kept set. */
    fun escape(s: String): String = escapeKeeping(s, "-._~,")

    private fun escapeKeeping(s: String, keep: String): String {
        val sb = StringBuilder(s.length)
        for (b in s.toByteArray(StandardCharsets.UTF_8)) {
            val c = Char(b.toInt() and 0xFF)
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in keep) {
                sb.append(c)
            } else {
                sb.append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            }
        }
        return sb.toString()
    }

    /**
     * Sends one request and returns what came back, retrying ONCE when the host throttles (429),
     * fails (5xx) or does not answer. Never throws: a transport failure is a [Response] with
     * `code == 0`.
     *
     * [body], when given, is sent as UTF-8 with a `Content-Type: application/json` header unless
     * [headers] already names one. Every provider in this app posts JSON.
     */
    fun send(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    ): Response {
        var last = Response(0, "")
        repeat(ATTEMPTS) { attempt ->
            last = attempt(url, method, headers, body, connectTimeoutMs, readTimeoutMs)
            val retriable = !last.answered || last.code == 429 || last.code >= 500
            if (!retriable || attempt == ATTEMPTS - 1) return last
        }
        return last
    }

    /**
     * One attempt. A [HttpURLConnection] is single-use, so the retry in [send] builds a fresh one
     * rather than reusing anything; the underlying TCP connection is pooled by the platform, so
     * this costs nothing but the object.
     */
    private fun attempt(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Response {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                for ((k, v) in headers) setRequestProperty(k, v)
                if (body != null) {
                    if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                        setRequestProperty("Content-Type", MEDIA_JSON)
                    }
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                }
            }
            // Reading the status is what actually performs the exchange.
            val code = conn.responseCode
            // The platform THROWS from getInputStream() on a 4xx/5xx and serves the body on
            // getErrorStream() instead; a provider that explains itself in an error body would
            // otherwise be silent. Either stream may legitimately be null (a 204, a HEAD).
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(charsetOf(conn.contentType)) }.orEmpty()
            Response(code, text, conn.headerFields.filterKeys { it != null }.mapKeys { it.key!! })
        } catch (e: IOException) {
            Response(0, "", failure = e)
        } finally {
            conn?.disconnect()
        }
    }

    /** The charset a `Content-Type` names, or UTF-8 - which is what every endpoint here serves. */
    private fun charsetOf(contentType: String?): Charset {
        val name = contentType?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"', ' ')
            ?: return StandardCharsets.UTF_8
        return try {
            Charset.forName(name)
        } catch (_: IllegalCharsetNameException) {
            StandardCharsets.UTF_8
        } catch (_: UnsupportedCharsetException) {
            StandardCharsets.UTF_8
        }
    }

    /** The original try plus one retry: the cadence every provider used before. */
    private const val ATTEMPTS = 2

    private val HEX = "0123456789ABCDEF".toCharArray()
}
