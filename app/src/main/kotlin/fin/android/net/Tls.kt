package fin.android.net

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The one TLS adjustment this app makes: it stops offering the legacy finite-field Diffie-Hellman
 * and DSS cipher suites, so its TLS handshake looks like every other modern client's.
 *
 * **Why this exists.** Some providers run anti-bot edges that fingerprint the TLS ClientHello
 * (JA3/JA4: the exact list of cipher suites, extensions and groups a client offers) and answer
 * `429 Too Many Requests` to stacks they do not recognise, from a network that is not throttled at
 * all and while a browser on the same machine works. Measured on 2026-09-20 against
 * `query2.finance.yahoo.com`, same machine, same IP, same User-Agent, one request each:
 *
 * | ClientHello | Answer |
 * |---|---|
 * | the JDK's default 31 suites | HTTP 429 |
 * | the same 31, reordered so DHE/DSS come last | HTTP 429 |
 * | the same list minus the DHE/DSS suites (18) | HTTP 200 |
 * | TLS 1.3 suites plus the ECDHE ones only (9) | HTTP 200 |
 *
 * The negotiated suite was `TLS_AES_128_GCM_SHA256` in all four cases, so nothing about the
 * connection changed: only what was OFFERED. Restricting the protocol list (TLS 1.3 + 1.2, which
 * is already the default) changed nothing, and offering ALPN `h2` broke the exchange outright,
 * since `HttpURLConnection` then speaks HTTP/1.1 down a connection the server negotiated for
 * HTTP/2. The Go reference implementation reaches the same endpoint without trouble because Go's
 * TLS stack has never offered finite-field DH at all.
 *
 * **Why a deny-list and not a pinned suite list.** Naming the suites to offer would freeze this
 * app's handshake at what 2026 considered modern and silently exclude whatever the platform adds
 * next. Removing two obsolete families instead keeps the platform in charge of the rest, cannot
 * become a downgrade (what is left is strictly ECDHE and TLS 1.3), and needs no upkeep. On Android
 * the platform provider (Conscrypt) does not enable those families in the first place, so [filter]
 * returns the list unchanged and [socketFactory] then touches nothing.
 *
 * **If a provider starts answering 429 again**, from a network that plainly works: that is the
 * symptom of a fingerprint rule that moved, not of a bug in this repository. The diagnosis, in
 * order: `curl` the same URL (a `curl` built on LibreSSL/SecureTransport is refused too, which
 * confirms the edge and not the address), fetch the same URL from a Go program or from the
 * `../finador` CLI (accepted, which rules the IP out), then `make probe`. What can be done here is
 * narrow, and the honest options are to change nothing and let the fallback providers carry the
 * symbol, or to move the call to another endpoint.
 */
internal object Tls {

    /**
     * A factory that hands back sockets with [filter] applied, or the platform's own factory when
     * the platform offers nothing to remove. One instance, reused: `HttpURLConnection` keeps its
     * keep-alive pool per factory, so a fresh wrapper per request would cost a handshake each time.
     */
    val socketFactory: SSLSocketFactory by lazy {
        val platform = SSLSocketFactory.getDefault() as SSLSocketFactory
        if (touchesAnything(platform)) FilteringSocketFactory(platform) else platform
    }

    /**
     * [enabled] without the finite-field Diffie-Hellman (`TLS_DHE_*`, `TLS_DH_anon_*`) and DSS
     * (`*_DSS_*`) suites. Elliptic-curve `ECDHE` suites are untouched, and so is the
     * `TLS_EMPTY_RENEGOTIATION_INFO_SCSV` signalling entry. The order of what survives is kept,
     * because it is the platform's preference and the measurement above showed order is not what
     * the fingerprint reads.
     *
     * Returns [enabled] itself when there is nothing to remove, which is how the caller knows it
     * can leave the socket alone.
     */
    fun filter(enabled: Array<String>): Array<String> {
        val kept = enabled.filterNot { legacy(it) }
        // An empty result would mean the platform offers nothing but legacy suites, which no
        // supported Android or JDK does; refusing to act is still better than an unusable socket.
        if (kept.isEmpty() || kept.size == enabled.size) return enabled
        return kept.toTypedArray()
    }

    /** True for a suite whose key exchange is finite-field DH or whose authentication is DSS. */
    private fun legacy(suite: String): Boolean {
        val body = suite.removePrefix("TLS_").removePrefix("SSL_")
        return body.startsWith("DH") || body.contains("_DSS_")
    }

    private fun touchesAnything(platform: SSLSocketFactory): Boolean = try {
        (platform.createSocket() as SSLSocket).use { s ->
            val enabled = s.enabledCipherSuites
            filter(enabled).size != enabled.size
        }
    } catch (_: IOException) {
        // No socket, no information: assume the filter is worth installing. It is a no-op on a
        // platform that enables nothing to remove.
        true
    }

    /**
     * Delegates every socket to the platform factory and narrows the offered suites on the way
     * out. Every `createSocket` overload is final in [SSLSocketFactory]'s contract sense: they must
     * all be forwarded, or one code path escapes the filter.
     */
    private class FilteringSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

        private fun tune(socket: Socket): Socket {
            if (socket is SSLSocket) {
                val narrowed = filter(socket.enabledCipherSuites)
                if (narrowed.size != socket.enabledCipherSuites.size) {
                    socket.enabledCipherSuites = narrowed
                }
            }
            return socket
        }

        override fun getDefaultCipherSuites(): Array<String> = filter(delegate.defaultCipherSuites)

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(): Socket = tune(delegate.createSocket())

        override fun createSocket(host: String?, port: Int): Socket =
            tune(delegate.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            tune(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            tune(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            tune(delegate.createSocket(address, port, localAddress, localPort))

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            tune(delegate.createSocket(s, host, port, autoClose))
    }
}
