package fin.android.market

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [LiveProbe] on the HOST JVM, opt-in, run with `make probe`. Never part of `make test`.
 *
 * A green run here is NOT a release gate and cannot be: the host JVM's TLS is the JDK's JSSE and a
 * phone's is Conscrypt, a different handshake that providers may judge differently (`net/Tls.kt`
 * has the measurement). What this run is good for is fast feedback while changing the network layer
 * or a parser. The gate is `make probe-device`, which runs the same body through the stack users
 * actually have.
 */
class LiveProviderProbe {

    @Test
    fun probeLiveProviders() {
        assumeTrue("set -Dprobe=1 (or run `make probe`) to hit the live providers", enabled())
        val failures = LiveProbe.run(::println)
        check(failures.isEmpty()) { "live providers that did not answer plausibly: $failures" }
    }

    private fun enabled(): Boolean = System.getProperty("probe").isNullOrBlank().not()
}
