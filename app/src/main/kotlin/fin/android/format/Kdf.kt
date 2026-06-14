package fin.android.format

import fin.android.crypto.Argon2
import fin.android.crypto.Hkdf

/** The two 32-byte subkeys derived from the passphrase. */
class Keys(val log: ByteArray, val cache: ByteArray)

private val INFO_LOG = "finador-ledger-v2".toByteArray(Charsets.US_ASCII)
private val INFO_CACHE = "finador-cache-v2".toByteArray(Charsets.US_ASCII)

/**
 * Derives the ledger and cache subkeys from the passphrase and the header's Argon2id parameters:
 * `master = Argon2id(pw, salt, t, m, p, 32)`, then HKDF-SHA256 with a nil salt and fixed info
 * strings. The `-v2` suffix is historical and unchanged by the v3 record schema.
 */
fun deriveKeys(passphrase: String, header: Header): Keys {
    val master = Argon2.hash(passphrase.toByteArray(Charsets.UTF_8), header.salt, header.t, header.m, header.p, 32)
    val keyLog = Hkdf.sha256(master, null, INFO_LOG, 32)
    val keyCache = Hkdf.sha256(master, null, INFO_CACHE, 32)
    return Keys(keyLog, keyCache)
}
