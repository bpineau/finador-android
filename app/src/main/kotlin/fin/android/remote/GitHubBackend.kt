package fin.android.remote

import fin.android.crypto.B64
import fin.android.net.Http
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
) : Backend {

    override fun fetch(): Fetched {
        val url = Http.url("$baseUrl/repos/$owner/$repo/contents/$path", "ref" to branch)
        val (code, body) = call(url, "GET", null)
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
        val (code, body) = call(url, "PUT", json.encodeToString(PutRequest.serializer(), payload))
        when (code) {
            200, 201 -> return json.decodeFromString(PutResponse.serializer(), body).content.sha
            409, 422 -> throw RemoteError.Conflict()
            401, 403 -> throw RemoteError.Auth(authMessage(code))
            else -> throw RemoteError.Offline("GitHub push failed (HTTP $code)")
        }
    }

    override fun describe(): String = "github:$owner/$repo/$path@$branch"

    /** Executes with one retry on 5xx/429 or IO error; surfaces transport failures as Offline. */
    private fun call(url: String, method: String, body: String?): Pair<Int, String> {
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
        )
        val resp = Http.send(
            url,
            method = method,
            headers = headers,
            body = body,
            // GitHub is the sync path: a phone with no signal must say so quickly rather than
            // sit on a 15 s connect, which is why this one timeout differs from the providers'.
            connectTimeoutMs = 8_000,
        )
        if (!resp.answered) throw RemoteError.Offline("network error talking to GitHub", resp.failure)
        return resp.code to resp.body
    }

    private fun authMessage(code: Int): String =
        "GitHub token invalid or lacks Contents permission (HTTP $code) - re-login"

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
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
