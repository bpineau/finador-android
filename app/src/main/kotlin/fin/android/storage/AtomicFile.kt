// Package storage holds the one file primitive every persisted artefact of the app is written
// through. It is pure Kotlin (java.io only), so the host unit tests exercise the real thing.
package fin.android.storage

import java.io.File
import java.io.FileOutputStream

/**
 * Replaces a file's contents in one step: write a temporary sibling, flush it to the storage, then
 * rename it over the target.
 *
 * `File.writeBytes` TRUNCATES the target and then writes into it, so a process that dies in between
 * leaves a short file where a whole one was. On Android that is not a remote possibility: the system
 * kills a backgrounded process whenever it wants memory, and the kill can land between the truncate
 * and the write of an encrypted ledger. What is left then authenticates as nothing, and the app
 * cannot open its own data any more - a permanent brick, since the working copy is exactly what the
 * unlock path reads and (while dirty) refuses to pull over.
 *
 * rename(2) is atomic within a filesystem, so a reader sees either the old contents or the new ones
 * and never a mixture. The temporary file is a sibling of the target for that reason: the app's
 * files directory and its cache directory are different mounts on some devices.
 *
 * Mirrors the Go reference's `atomicWrite` (`internal/store/store.go`: tmp + fsync + rename, and a
 * `.bak` for the ledger). The one thing it cannot do from the JVM is fsync the DIRECTORY after the
 * rename, so a power cut - as opposed to a process death, which is what this defends against - can
 * still lose the rename itself. The previous contents survive it either way.
 */
object AtomicFile {

    /** The suffix of the sibling a write goes through; never left behind by a completed write. */
    private const val TMP = ".tmp"

    /** The suffix [write] keeps the replaced contents under when asked for a backup. */
    const val BAK = ".bak"

    /**
     * Writes [data] over [file], atomically. With [backup], the contents being replaced are kept
     * beside it under [BAK] first, which is what gives a corrupted (or half-written by an older
     * build) ledger somewhere to be recovered from.
     */
    fun write(file: File, data: ByteArray, backup: Boolean = false) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TMP)
        FileOutputStream(tmp).use {
            it.write(data)
            it.flush()
            it.fd.sync() // the bytes must be on the storage BEFORE the rename publishes them
        }
        if (backup && file.exists()) {
            val bak = File(file.parentFile, file.name + BAK)
            bak.delete()
            file.copyTo(bak, overwrite = true)
        }
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse to rename over an existing file. Falling back to a
            // delete-then-rename reopens the window this exists to close, so it is the last resort
            // and the backup above is what covers it.
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw java.io.IOException("could not replace ${file.name}")
            }
        }
    }

    /** [write] for text, UTF-8. */
    fun writeText(file: File, text: String, backup: Boolean = false) =
        write(file, text.toByteArray(Charsets.UTF_8), backup)

    /** The backup sibling of [file], whether or not it exists. */
    fun backupOf(file: File): File = File(file.parentFile, file.name + BAK)
}
