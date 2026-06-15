package fin.android.format

/**
 * A wrong passphrase and a tampered/corrupt file are indistinguishable by design - both surface
 * as this single error.
 */
class BadPasswordOrCorruptException(cause: Throwable? = null) :
    Exception("bad password / corrupt file", cause)

/**
 * The file is well-formed but the reader cannot safely interpret it: an unknown format version
 * or an unknown record kind. A financial ledger must never silently skip what it does not
 * understand, so this is a hard error, distinct from a bad password.
 */
class UnsupportedFormatException(message: String) : Exception(message)
