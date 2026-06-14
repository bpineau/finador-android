package fin.android.market

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared HTTP plumbing for the market providers: a browser-looking User-Agent and a default client. */
internal object Http {
    /** Copied from the Go implementation (yahoo.go) so the providers look like a real browser. */
    const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}
