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
 * Above 1 MB, Contents returns metadata only. Fetching the blob by its SHA keeps the bytes and
 * the version used for the next conditional push together if the branch moves during the read.
 */
class GitHubBackend(
    private val owner: String,
    private val repo: String,
    private val path: String,
    private val branch: String,
    private val token: String,
    private val baseUrl: String = "https://api.github.com",
) : Backend {

    private val repoUrl = "$baseUrl/repos/${Http.escapePath(owner)}/${Http.escapePath(repo)}"
    private val contentsUrl = "$repoUrl/contents/" + path.split('/').joinToString("/") { Http.escapePath(it) }

    override fun fetch(): Fetched {
        val url = Http.url(contentsUrl, "ref" to branch)
        val (code, body) = call(url, "GET", null, accept = "application/vnd.github.object+json")
        when (code) {
            200 -> {
                var response = json.decodeFromString(ContentsResponse.serializer(), body)
                if (response.encoding == "none") {
                    val sha = response.sha
                    val (blobCode, blob) = call("$repoUrl/git/blobs/${Http.escapePath(sha)}", "GET", null)
                    when (blobCode) {
                        200 -> response = json.decodeFromString(ContentsResponse.serializer(), blob)
                        401, 403 -> throw RemoteError.Auth(authMessage(blobCode))
                        else -> throw RemoteError.Offline("GitHub blob unavailable (HTTP $blobCode)")
                    }
                    require(response.sha == sha) { "GitHub returned a different blob version" }
                }
                require(response.encoding == "base64") { "Unsupported GitHub content encoding" }
                return Fetched(B64.decode(response.content), response.sha)
            }
            404 -> throw RemoteError.Missing()
            401, 403 -> throw RemoteError.Auth(authMessage(code))
            else -> throw RemoteError.Offline("GitHub fetch failed (HTTP $code)")
        }
    }

    override fun push(data: ByteArray, base: Version?, message: String): Version {
        val payload = PutRequest(message = message, content = B64.encode(data), sha = base, branch = branch)
        val (code, body) = call(contentsUrl, "PUT", json.encodeToString(PutRequest.serializer(), payload))
        when (code) {
            200, 201 -> return json.decodeFromString(PutResponse.serializer(), body).content.sha
            409, 422 -> throw RemoteError.Conflict()
            401, 403 -> throw RemoteError.Auth(authMessage(code))
            // Not transient: a missing repository or branch, or one the token cannot see. Read as
            // offline, the write would wait for a retry that can never succeed.
            404 -> throw RemoteError.Auth("GitHub repository or branch not found, or the token cannot see it (HTTP 404)")
            else -> throw RemoteError.Offline("GitHub push failed (HTTP $code)")
        }
    }

    /** The repository metadata, which any token able to read its contents may read (Go `CheckAccess`). */
    override fun checkAccess() {
        val (code, _) = call(repoUrl, "GET", null)
        when (code) {
            200 -> return
            // GitHub answers 404, not 403, for a private repository the token cannot see.
            401, 403, 404 -> throw RemoteError.Auth("GitHub repository not found, or the token cannot see it (HTTP $code)")
            else -> throw RemoteError.Offline("GitHub repository check failed (HTTP $code)")
        }
    }

    override fun describe(): String = "github:$owner/$repo/$path@$branch"

    /** Executes with one retry on 5xx/429 or IO error; surfaces transport failures as Offline. */
    private fun call(
        url: String, method: String, body: String?, accept: String = "application/vnd.github+json",
    ): Pair<Int, String> {
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            // GitHub moves unversioned clients to its oldest supported API on retirement.
            // An installed APK with a pinned, retired version gets HTTP 410 on every request.
            "Accept" to accept,
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
private data class ContentsResponse(val content: String, val sha: String, val encoding: String = "base64")

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
