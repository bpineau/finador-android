package fin.android.market

import android.util.Log
import org.junit.Test

/**
 * [LiveProbe] on a real Android device or emulator, run with `make probe-device`.
 *
 * This is the ONE live check a release owes, and the host-JVM `make probe` cannot stand in for it:
 * Android's TLS is Conscrypt (BoringSSL) and the host JVM's is the JDK's JSSE, so the two send
 * different ClientHellos and a provider that fingerprints handshakes can answer one and refuse the
 * other. That asymmetry is not hypothetical, it is what `net/Tls.kt` exists for.
 *
 * It is the only instrumented test in the repository and it is deliberately not wired into any
 * other target: `make test` stays hermetic, and this one needs a device and the live internet.
 *
 * Every line is written to logcat under the `probe` tag as well as to stdout, because instrumented
 * stdout is easy to lose between Gradle and `am instrument`; `make probe-device` reads logcat.
 */
class LiveProviderDeviceProbe {

    @Test
    fun probeLiveProviders() {
        val failures = LiveProbe.run { line ->
            Log.i(TAG, line)
            println(line)
        }
        check(failures.isEmpty()) { "live providers that did not answer plausibly: $failures" }
    }

    private companion object {
        const val TAG = "probe"
    }
}
