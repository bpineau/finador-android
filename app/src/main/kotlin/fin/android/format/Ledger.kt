package fin.android.format

import fin.android.domain.Book

/**
 * An opened finador ledger: the validated header, the materialized [book], and (internally) the
 * derived keys and verbatim record entries needed later for diff-on-save and merge (Phase 2).
 */
class Ledger internal constructor(
    val header: Header,
    internal val keys: Keys,
    internal val entries: List<Entry>,
    val book: Book,
) {
    companion object {
        /**
         * Opens and fully verifies a `.fin` byte stream under [passphrase].
         * @throws BadPasswordOrCorruptException wrong passphrase or tampered/corrupt file.
         * @throws UnsupportedFormatException unknown format version or record kind.
         */
        fun open(bytes: ByteArray, passphrase: String): Ledger {
            val raw = Log.open(bytes, passphrase)
            val book = Replay.fold(raw.entries)
            return Ledger(raw.header, raw.keys, raw.entries, book)
        }
    }
}
