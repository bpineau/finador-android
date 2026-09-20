package fin.android.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * What [Tls] promises, checked without a network: the suites it removes, the suites it keeps, the
 * order it preserves, and the fact that a socket built through the factory really is narrowed.
 *
 * Whether a given provider then answers 200 is a live question and belongs to `make probe` and
 * `make probe-device`, never here.
 */
class TlsTest {

    @Test
    fun dropsFiniteFieldDiffieHellmanAndDss() {
        val filtered = Tls.filter(
            arrayOf(
                "TLS_AES_128_GCM_SHA256",
                "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_DHE_DSS_WITH_AES_128_GCM_SHA256",
                "TLS_DH_anon_WITH_AES_128_GCM_SHA256",
                "SSL_DHE_RSA_WITH_3DES_EDE_CBC_SHA",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_EMPTY_RENEGOTIATION_INFO_SCSV",
            ),
        )
        assertArrayEquals(
            arrayOf(
                "TLS_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_EMPTY_RENEGOTIATION_INFO_SCSV",
            ),
            filtered,
        )
    }

    /** `ECDHE` contains `DH` but is elliptic-curve, modern, and the one thing left to offer. */
    @Test
    fun keepsEveryEllipticCurveSuite() {
        val ecdhe = arrayOf(
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
        )
        assertArrayEquals(ecdhe, Tls.filter(ecdhe))
    }

    /** Nothing to remove: the caller gets its own array back, so it can skip touching the socket. */
    @Test
    fun returnsTheInputWhenThereIsNothingToRemove() {
        val modern = arrayOf("TLS_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")
        assertSame(modern, Tls.filter(modern))
    }

    /** A platform offering nothing else keeps working rather than being left with no suite at all. */
    @Test
    fun refusesToEmptyTheList() {
        val onlyLegacy = arrayOf("TLS_DHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_DHE_DSS_WITH_AES_128_GCM_SHA256")
        assertSame(onlyLegacy, Tls.filter(onlyLegacy))
    }

    /**
     * The factory applies the filter for real. On a platform whose defaults are already clean
     * (Android's Conscrypt) there is nothing to assert but the absence of the legacy families,
     * which is exactly what this checks: after the wrapper, no socket offers one.
     */
    @Test
    fun theFactoryNarrowsTheSocketItHandsBack() {
        val socket = Tls.socketFactory.createSocket() as SSLSocket
        socket.use {
            val offered = it.enabledCipherSuites.toList()
            assertTrue("the socket must still offer something", offered.isNotEmpty())
            assertFalse(
                "no finite-field DH or DSS suite may be offered: $offered",
                offered.any { s -> s.removePrefix("TLS_").removePrefix("SSL_").startsWith("DH") },
            )
            assertFalse(
                "no DSS suite may be offered: $offered",
                offered.any { s -> s.contains("_DSS_") },
            )
        }
    }

    /** The wrapper hides nothing the platform supports; it only narrows what is OFFERED. */
    @Test
    fun theFactoryStillReportsEverythingThePlatformSupports() {
        val platform = SSLSocketFactory.getDefault() as SSLSocketFactory
        assertArrayEquals(platform.supportedCipherSuites, Tls.socketFactory.supportedCipherSuites)
    }
}
