package fin.android.remote

import fin.android.crypto.B64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GitHub Contents API transport (pure HTTPS, no `git` binary). Each push is one commit, so the
 * repo history shows the append-log deltas. The repo holds only the encrypted `.fin`.
 *
 * Caveat: the Contents endpoint caps blobs at ~1 MB; the ledger (no market cache) stays well under.
 */
class GitHubBackend(
    private val owner: String,
    private val repo: String,
    private val path: String,
    private val branch: String,
    private val token: String,
    private val baseUrl: String = "https://api.github.com",
    private val http: OkHttpClient = defaultClient(),
) : Backend {

    override fun fetch(): Fetched {
        val url = "$baseUrl/repos/$owner/$repo/contents/$path".toHttpUrl().newBuilder()
            .addQueryParameter("ref", branch).build()
        val req = base(Request.Builder().url(url)).get().build()
        val (code, body) = call(req)
        when (code) {
            200 -> {
                val r = json.decodeFromString(ContentsResponse.serializer(), body)
                val raw = B64.decode(r.content) // GitHub wraps base64 at 60 cols; B64.decode strips whitespace
                return Fetched(raw, r.sha)
            }
            404 -> throw RemoteError.Missing()
            401, 403 -> throw RemoteError.Auth(authMessage(code))
            else -> throw RemoteError.Offline("GitHub fetch failed (HTTP $code)")
        }
    }

    override fun push(data: ByteArray, base: Version?, message: String): Version {
        val url = "$baseUrl/repos/$owner/$repo/contents/$path"
        val payload = PutRequest(message = message, content = B64.encode(data), sha = base, branch = branch)
        val req = base(Request.Builder().url(url))
            .put(json.encodeToString(PutRequest.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()
        val (code, body) = call(req)
        when (code) {
            200, 201 -> return json.decodeFromString(PutResponse.serializer(), body).content.sha
            409, 422 -> throw RemoteError.Conflict()
            401, 403 -> throw RemoteError.Auth(authMessage(code))
            else -> throw RemoteError.Offline("GitHub push failed (HTTP $code)")
        }
    }

    override fun describe(): String = "github:$owner/$repo/$path@$branch"

    private fun base(b: Request.Builder): Request.Builder = b
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    /** Executes with one retry on 5xx/429 or IO error; surfaces transport failures as Offline. */
    private fun call(req: Request): Pair<Int, String> {
        var last: IOException? = null
        repeat(2) { attempt ->
            try {
                http.newCall(req).execute().use { resp ->
                    val code = resp.code
                    if ((code >= 500 || code == 429) && attempt == 0) return@repeat
                    return code to (resp.body?.string() ?: "")
                }
            } catch (e: IOException) {
                last = e
                if (attempt == 0) return@repeat
            }
        }
        throw RemoteError.Offline("network error talking to GitHub", last)
    }

    private fun authMessage(code: Int): String =
        "GitHub token invalid or lacks Contents permission (HTTP $code) — re-login"

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

@Serializable
private data class ContentsResponse(val content: String, val sha: String)

@Serializable
private data class PutRequest(
    val message: String,
    val content: String,
    val sha: String? = null,
    val branch: String,
)

@Serializable
private data class PutResponse(val content: ShaHolder) {
    @Serializable
    data class ShaHolder(@SerialName("sha") val sha: String)
}
