package fin.android.data

import fin.android.crypto.Bytes
import fin.android.crypto.Hashes
import fin.android.remote.GithubConfig
import fin.android.storage.AtomicFile
import java.io.File
import java.io.IOException

/** Branch-specific checkout names and migration of the saved configuration's legacy checkout. */
internal class CheckoutPaths(private val dir: File) {
    fun workingCopy(gh: GithubConfig): File = File(dir, "${slug(gh)}.fin")
    fun state(gh: GithubConfig): File = File(dir, "${slug(gh)}.state.json")

    private fun slug(gh: GithubConfig): String = hash("${gh.owner}/${gh.repo}/${gh.path}\u0000${gh.branch}")
    private fun hash(identity: String): String = Bytes.toHex(Hashes.sha256(identity.toByteArray(Charsets.UTF_8))).take(16)

    /**
     * Only call with the PREVIOUSLY SAVED branch: a legacy name cannot identify its branch itself.
     * Move backup/state first and the ledger last. An interrupted run resumes without overwriting
     * already moved files, and an existing branch-specific ledger always takes precedence.
     */
    fun migrateLegacy(known: GithubConfig) {
        val legacy = hash("${known.owner}/${known.repo}/${known.path}")
        val oldCopy = File(dir, "$legacy.fin")
        val target = workingCopy(known)
        if (!oldCopy.exists() || target.exists()) return
        moveIfMissing(AtomicFile.backupOf(oldCopy), AtomicFile.backupOf(target))
        val oldState = File(dir, "$legacy.state.json")
        moveIfMissing(AtomicFile.backupOf(oldState), AtomicFile.backupOf(state(known)))
        moveIfMissing(oldState, state(known))
        moveIfMissing(oldCopy, target)
    }

    private fun moveIfMissing(source: File, target: File) {
        if (!source.exists() || target.exists()) return
        if (!source.renameTo(target)) throw IOException("could not migrate checkout ${source.name}")
    }
}
