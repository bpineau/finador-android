# finador-android — design (v1)

*2026-06-14 — spec resulting from a brainstorming. Native Android client, **bit-for-bit
compatible** with the finador file format and the GitHub sync. The same encrypted `.fin`, in a
private GitHub repository, is usable interchangeably from the desktop (CLI/web) and from
Android.*

Normative reference for the format: `../finador/docs/FORMAT.md` (spec v3, "implementation-grade",
written explicitly to allow a native reader/writer without reading the Go source). In case of
divergence, **`FORMAT.md` is authoritative**. Sync reference:
`../finador/docs/superpowers/specs/2026-06-13-github-remote-data-design.md`.

---

## 0. Objective & scope

### 0.1 Goal

An Android app that reads and writes **exactly** the same `.fin` file as finador, synced
via the **same private GitHub repository**, so that the user manages their wealth from the desktop
**and** from the mobile on the same ledger, without conversion or format divergence.

### 0.2 v1 scope — *viewer + quick entry*

**Read (everything)**:
- Unlock the `.fin`, decrypt, replay (fold) the journal → `Book`.
- Wealth view: gross / estimated tax / net, by line (equities/property/cash) and by `group`.
- Position detail (account × asset): value, cost, gain/loss, chart.
- Performance: value over time; TWR/XIRR/CAGR/vol/Sharpe/Sortino/maxDD metrics
  (phase 6, "nice-to-have" of v1).
- Display of labels (read-only).

**Write (quick entry on the go)** — only **transactions** on **already existing** accounts /
assets:
- `buy`, `sell`, `dividend`, `fee`, `deposit`, `withdraw`, `statement` (the 7 `tx` kinds).
- Correction / deletion of a recent transaction (`tx-edit`, `tx-del`).
- Each write = pull-before → append a record → push-after (1 GitHub commit).

**Market data**: the app fetches **its own quotes** (the market cache is **not**
synced). Multi-source by ISIN **from v1**: Yahoo → FT → Morningstar, FX cross-rate via USD,
local encrypted regenerable sidecar cache.

### 0.3 v1 out-of-scope (non-goals)

- Creation / editing of **accounts** and **assets**, editing the **config**, managing
  **labels** (creation/deletion) → remain on the desktop (CLI/web).
- **Compaction** of the journal (rare, full-rewrite) → desktop.
- **Local file** storage (SAF/Syncthing) → **GitHub only** in v1.
- iOS, widgets, push notifications, watch.

These excluded operations block nothing: a `.fin` created/structured on the desktop is fully
readable and "transactionable" from the mobile; structural changes are made on the desktop
and then propagate via sync.

---

## 1. Stack decisions

| Topic | Decision | Reason |
|---|---|---|
| Language | **Kotlin** (2.x stable) | native Android, mature crypto/JSON ecosystem |
| UI | **Jetpack Compose + Material 3** | declarative, charts in Canvas, zero WebView |
| Target | **minSdk 26** (Android 8.0), **compileSdk/targetSdk 36** (build-tools 36.1.0, installed) | native `java.time` + `java.util.Base64` without desugaring; ~98% of devices |
| Build | **Gradle Kotlin DSL + wrapper** (`./gradlew`), AGP latest stable | nothing to install beyond JDK + SDK |
| Async | **Coroutines + Flow** | network/disk I/O, reactive state |
| DI | **manual** (`AppContainer`, constructor injection) | minimalist, testable, no annotation-processing (no Hilt) |
| JSON | **kotlinx.serialization** | typed, control over `@SerialName` (`k`/`ts`/`d`) |
| Decimals | **`java.math.BigDecimal`** | exact, equivalent to `shopspring/decimal` |
| Network | **OkHttp** | GitHub Contents API + Yahoo/FT/Morningstar, retry/interceptors |
| HTML parsing | **Jsoup** | FT/Morningstar fallback (Yahoo is JSON) |
| Charts | **Compose Canvas** (hand-rolled drawing) | simple curves, zero heavy dependency (finador ethos) |
| Secrets | **Android Keystore + EncryptedSharedPreferences** (`androidx.security:security-crypto`) | equivalent to macOS Keychain |

### 1.1 Crypto — dependencies

Everything is in the JDK **except Argon2id**:

| Primitive | Implementation |
|---|---|
| Argon2id | **`com.lambdapioneer.argon2kt:argon2kt`** (JNI wrapper of the reference lib, native libs for all ABIs, explicit `t`/`m`/`p` parameters, raw output) — **the only external crypto dependency** |
| HKDF-SHA256 | hand-rolled (RFC 5869) via `javax.crypto.Mac` (HmacSHA256), ~30 lines |
| AES-256-GCM | `javax.crypto.Cipher("AES/GCM/NoPadding")` + `GCMParameterSpec(128, nonce)` — the tag is appended to the ciphertext, **like Go** (compatible convention) |
| SHA-256 | `java.security.MessageDigest` |
| base64 std padded | `java.util.Base64.getEncoder()/getDecoder()` |
| base64 RawURL (cache name) | `java.util.Base64.getUrlEncoder().withoutPadding()` |
| gzip (cache only) | `java.util.zip.GZIP{In,Out}putStream` |

**Validation safeguard**: the crypto layer is frozen by the vectors of `FORMAT.md §9`
(see §10.1). Argon2id must reproduce `master`/`keyLog`/`keyCache` exactly before any
higher-level development.

---

## 2. Dev environment (macOS, Homebrew, free) — **installed**

Environment actually in place (done by the user):
```sh
brew install --cask temurin@21
brew install --cask android-commandlinetools
brew install --cask android-studio
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin"
sdkmanager --licenses
sdkmanager "platform-tools"
sdkmanager "platforms;android-36"
sdkmanager "build-tools;36.1.0"
sdkmanager "emulator" "system-images;android-36;google_apis;arm64-v8a"
echo no | avdmanager create avd -n test -k "system-images;android-36;google_apis;arm64-v8a"
```

Consequences for the project:
- `compileSdk = 36`, `targetSdk = 36`, build-tools `36.1.0`.
- The SDK is under **`$(brew --prefix)/share/android-commandlinetools`** (not `~/Library/Android/sdk`).
  The project writes `local.properties` with `sdk.dir=` pointing there, and the build commands
  export `ANDROID_HOME`/`JAVA_HOME` explicitly (the build shell may not inherit these
  exports if they are not in the profile).
- AVD named **`test`** (API 36, arm64-v8a) for `emulator -avd test` + `installDebug`.

Build & run in CLI (once the SDK is installed):
```sh
./gradlew assembleDebug          # compile the debug APK
./gradlew installDebug           # install on the connected device/emulator
./gradlew test                   # JVM unit tests (crypto/format/merge/market)
./gradlew connectedAndroidTest   # instrumented tests (UI, Keystore)
```

> Note: installing the casks and accepting the SDK licenses requires user
> interaction (password, large download). I scaffold the project so that, once those
> two `brew install` are done, `./gradlew assembleDebug` works directly.

---

## 3. Architecture & modules

A single application module, packages as a **conceptual mirror of finador's `internal/*`** so
that the correspondence is obvious and the "pure" layer (without Android) is testable in JVM alone.

```
app/src/main/kotlin/fin/android/
  crypto/      argon2, hkdf, aesgcm, sha256, base64, ids        (pure, test vectors)
  domain/      Account, Asset, Tx, Label, Config, Money, TaxRule, Book, ID
  format/      ≈ store : header, record (seal/open + AAD), log (read/write),
               replay (fold), diff (diff-on-save), merge (union+LWW), reseal
  market/      Source, Yahoo, FT, Morningstar, MultiSource, FX, CacheSidecar
  remote/      GitHubBackend (Contents API), Sync (copy+state, pull/push), RemoteConfig
  valuation/   positions, cost basis, tax bases, value series, perf metrics
  data/        repositories, AppContainer (manual DI), SecretStore (Keystore)
  ui/          Compose : onboarding, overview, position, txEntry, sync, settings + Canvas charts
```

For each unit, we must be able to answer: *what does it do, how to use it, what does it
depend on*. The `crypto`, `domain`, `format`, `valuation`, `merge` layers are **pure Kotlin**
(no Android API) → testable with `./gradlew test` without a device, and frozen by the reference
vectors.

---

## 4. Format compatibility (the core)

We reimplement the reader/writer described by `FORMAT.md v3`. Key points:

### 4.1 Read
1. **Header** (line 1, plaintext JSON): parse `fmt`/`v`/`kdf`/`t`/`m`/`p`/`salt`/`id`.
   - Reject if `fmt != "finador-ledger"`, if `v` unknown (≠ 3), if `kdf != "argon2id"`.
   - **Check the Argon2 bounds BEFORE derivation**: `1≤t≤16`, `8≤m≤1048576` (KiB), `1≤p≤16`,
     `len(salt)==16`, `len(id)==16` (anti memory-bomb on a forged header).
   - `hdrHash = SHA-256(raw bytes of line 1)` (hash the **on-disk line**, not a
     re-serialization).
2. **Derivation**: `master = Argon2id(pwd, salt, t, m, p, 32)` ;
   `keyLog = HKDF-SHA256(master, nil, "finador-ledger-v2", 32)` ;
   `keyCache = HKDF-SHA256(master, nil, "finador-cache-v2", 32)`.
3. **Records** (lines 2…N+1): `base64( nonce[12] ‖ AES-GCM(plaintext, AAD) )` with
   `AAD = hdrHash[32] ‖ uint64_be(seq) ‖ prevTag[16]` (first record: `prevTag = 16×0x00`).
   The chaining "tag" = last 16 bytes of the previous GCM output.
4. **Trailer** (last line): decrypt under `AAD_head = hdrHash ‖ "finador-head" ‖
   uint64_be(count)`, verify `count == number of records read` **and** `head == tag of the last
   record` (truncation/tampering detection).
5. **Replay (fold)** in file order: upsert/tombstone by `id` (config by `key`).
   `tx-edit == tx` (upsert), tombstones delete. **An unknown `k` = hard error** (never
   ignored — could hide money). **Unknown fields of a known `k` = tolerated** (and
   round-tripped via the verbatim line).

### 4.2 Write (diff-on-save, append-only)
- We keep the **verbatim base64 line** of each already-persisted record.
- On save: compute the **diff** snapshot↔Book → minimal records (created/changed ⇒ record;
  deleted ⇒ `*-del`), order `config` → accounts → assets → tx (definitions before references);
  **re-emit existing lines byte for byte**, append the new sealed records (continuous
  chain: `seq` continues, `prevTag` = current last tag), `ts` = UTC now,
  reseal the trailer. In v1 the mobile only creates `tx`/`tx-edit`/`tx-del`, but the diff/sealing
  engine is generic.
- **Atomic write** in the working copy: `tmp` → `fsync` → `.bak` rotation → `rename`.

### 4.3 Serialization details to respect
- Decimals and quantities = **JSON strings** (`"9000"`, `"42.50"`) → `BigDecimal`. `Money =
  {"amount":"…","ccy":"…"}`.
- `ts` = **RFC 3339 nanoseconds, UTC** (`2026-06-13T13:36:03.896575Z`).
- Enums (`tax`, asset `kind`, tx `kind`) serialized as **strings** (MarshalText on the Go side).
- **`id`** (accounts/assets/tx/labels) = `domain.NewID`: `uint48_be(unixMillis)[6] ‖ rand[8]` →
  **lowercase Crockford base32 without padding** (alphabet `0123456789abcdefghjkmnpqrstvwxyz`,
  without `i l o u`) → **23 characters**. To reimplement (no Crockford in the JDK).
- The **JSON key order is irrelevant** (the reader parses, assumes nothing) → our
  serialization does not have to be byte-identical to Go's; it just has to be **valid and
  semantically correct** (existing lines, for their part, are re-emitted verbatim).

---

## 5. GitHub synchronization (`remote/`)

The `.fin` **lives in a private GitHub repository**; the remote is just a **transport**. Reimplements
the model of finador's remote spec.

### 5.1 Backend — GitHub Contents API (pure HTTPS, no git binary)
- `GET /repos/{owner}/{repo}/contents/{path}?ref={branch}` (`Authorization: Bearer <PAT>`,
  `Accept: application/vnd.github+json`) → `{content: base64, sha}`. **Gotcha**: GitHub `content`
  contains line breaks every 60 chars → **strip whitespace before base64-decode**. 404 →
  `ErrRemoteMissing`.
- `PUT /repos/{owner}/{repo}/contents/{path}` body `{message, content: base64(bytes of the .fin),
  sha (omitted if creating), branch}` → new `sha`. 409/422 on stale `sha` → `ErrRemoteConflict`.
- Each `PUT` = **one commit** → visible history, small deltas (append-log).
- **Caveat**: the Contents API caps at ~1 MB; the `.fin` (ledger only, cache excluded) stays far
  below. If one day it approaches → Git Blobs API (out of v1, noted).
- OkHttp client: timeout + 1 retry on 5xx/429. Network/DNS error → **offline** (distinct from
  the 4xx auth errors).

Interface (seam, modeled on `internal/remote`):
```kotlin
sealed interface RemoteError { object Conflict; object Missing; data class Auth; data class Offline }
interface Backend {
    suspend fun fetch(): Result<Pair<ByteArray, Version>>   // Version = opaque sha
    suspend fun push(data: ByteArray, base: Version?, msg: String): Result<Version>
    fun describe(): String
}
```

### 5.2 Sync layer — working copy + state
- **Working copy**: `filesDir/checkout/<hash(owner/repo/path)>.fin`.
- **Sidecar state**: `…<hash>.state.json` = `{ "sha": "<last known remote sha>",
  "lastPull": "<RFC3339>", "dirty": false }`. `dirty=true` = unpushed changes (offline).
- **Read** (`openForRead`): if online and `now-lastPull > readPullAfter` (default 1h, configurable)
  or `dirty` → `fetch` → write the copy → update `sha`+`lastPull`; otherwise use the copy. Then
  `format.open(copy, passphrase)`.
- **Mutation** (`mutate`, entering a tx): fresh `fetch` first (update copy+`sha`) → `format.open`
  → append the record → `save` (copy) → `push(copy, sha, msg)` → on success: update `sha`, `dirty=false`.
  - `ErrRemoteConflict` → **re-`fetch`** into a temp → **merge** (§5.3) → re-`push` (bounded loop).
  - Offline → `dirty=true`, **local success** + "unpushed" warning, push at the next online
    access.
- **Manual `sync`** (button): force `fetch`; if `dirty`, push (with conflict resolution);
  otherwise refresh. Displays a summary.
- Auto commit message: `finador-android: <action> (<date time>)`.

### 5.3 Conflicts & merge (`format/merge.kt`)
Reimplements `internal/store/merge.go`:
- **Reject** if the header `id` differs (different ledgers).
- Group all records of both copies by `(class, id)` (config by `key`), sort by `ts`
  (lexicographic comparison of RFC3339Nano = chronological), **last-writer-wins** at the max `ts`.
- Identical records (same `k` + same `d` bytes) = not a conflict.
- **True conflict** = same `ts` (to the nanosecond) + different payloads → near-nonexistent case
  for a single user on 2 devices. UI: small dialog "keep this version / the
  remote one"; default documented if non-interactive.
- Result: resealed log (fresh chain, each winner's `ts` preserved), written atomically.

### 5.4 Source configuration (Android)
No `~/.config/finador/` on mobile → **`filesDir/config.json`**:
```json
{ "source": "github",
  "github": { "owner": "bpineau", "repo": "finador-data", "path": "portfolio.fin", "branch": "main" },
  "readPullAfter": "1h" }
```
(Edited by the Settings screen.) The **PAT** never lives here — only in the Keystore.

---

## 6. Market data (`market/`)

The **market cache is not synced**: a freshly synced device has the ledger
but **no prices**. So the app fetches its quotes itself.

- **Multi-source by ISIN, fallback order**: **Yahoo** (JSON, primary source) → **FT** →
  **Morningstar** (HTML via Jsoup). Covers FR/LU funds absent from Yahoo. (AMF FCPE funds
  remain manual on the desktop side; the mobile reads the last known value / `statement`.)
- **FX**: cross-rate via **USD** (like finador).
- **Local encrypted regenerable sidecar cache** (`cacheDir/finador/<id-RawURL>.cache`), format
  `FORMAT.md §7`: `"FINCACHE2" ‖ nonce[12] ‖ AES-GCM( gzip(JSON(MarketData)), AAD="FINCACHE2" )`
  under **`keyCache`**. Compatible with the desktop sidecar (but never pushed).
- A missing/unreadable/stale cache is **not an error**: we refetch. Refresh on
  open (throttled) + manual "refresh" button.

> Reuse: the `MarketData` format and the multi-source logic replicate `internal/market`.
> Each source can be tested with MockWebServer + captured HTML/JSON fixtures.

---

## 7. Valuation & performance (`valuation/`)

Recomputed from the folded transactions (nothing is stored), like finador:
- **Positions** by (account × asset), quantities, cost (cost basis), current value (quote × qty,
  converted into the reference currency via FX).
- **Estimated tax** according to the account rule: `gains:N%` taxes `max(0, value − contribution
  base)`; `value:N%` taxes the entire value; `none` nothing. Display **gross / tax / net**.
- **Value series** over time → curves (Compose Canvas).
- **Metrics** (phase 6): TWR, XIRR, CAGR, volatility, Sharpe, Sortino, maxDD — by period /
  scope. Reimplements `internal/perf`.

---

## 8. UI (Compose) — v1 screens

1. **Onboarding (once only)**: enter `owner/repo` (+ path, branch); **paste the GitHub
   PAT** into a text field ("paste" button, hidden after entry) → **stored in the Keystore**,
   never to be retyped again; enter the **passphrase** of the `.fin` once → **stored in the Keystore
   behind BiometricPrompt**. First pull → unlock. **On subsequent opens: only
   biometric authentication** (fingerprint/face) unlocks — neither PAT nor passphrase to
   retype. Device code/PIN fallback if biometrics fails.
2. **Overview (wealth)**: total gross/tax/net; equities/property/cash lines; tree by
   `group`; global sparkline; sync status banner (up to date / unpushed / offline).
3. **Position detail**: value, cost, gain/loss, labels (read), chart; list of the
   position's transactions.
4. **Transaction entry**: choose account (existing) → kind → asset (if applicable) → date,
   qty, amount, note → confirm = `mutate` (pull→append→push) + feedback.
5. **Sync**: "sync now" button, last pull, `dirty` state, resolved conflicts.
6. **Settings**: repo/branch/path, `readPullAfter`, re-login token, "forget" (purge Keystore),
   refresh quotes, about/format version (refuses unknown `v`).

Charts: hand-rolled **Canvas** drawing (line + sparkline), no WebView or heavy lib (finador ethos:
"zero external resources").

---

## 9. Security & error handling

- **Repo = only the encrypted `.fin`**: even if leaked, opaque. The **PAT** is a *fine-grained
  token scoped to this single repository* (Contents: R/W). We **paste** it into a field at
  onboarding, it is **stored in the Keystore** (EncryptedSharedPreferences; never in clear, never
  logged, never to be retyped again). Re-login from Settings if it is revoked/regenerated.
- **Passphrase**: entered **once only**, **stored in the Keystore behind BiometricPrompt**.
  Thereafter, **unlock by biometrics alone** (device code/PIN fallback) — never re-entered,
  never on disk in clear. The KDF key is derived only after biometric unlock.
  Wrong passphrase and tampered file = **same error** "bad password / corrupt file"
  (indistinguishable by design). "Forget" in Settings purges PAT + passphrase from the Keystore.
- **Strict refusals**: unknown `v` → clean refusal; unknown `k` → hard error; Argon2 bounds before
  derivation; invalid trailer/chain → refusal (truncation/tampering).
- **Network**: 401/403 → "invalid token or insufficient permissions" (≠ offline);
  network/DNS → offline (`dirty` path). Contents API ~1 MB → documented caveat.
- **Android permissions**: `INTERNET` only. No storage permission (GitHub-only;
  working copy + cache in the app's **private** storage: `filesDir`/`cacheDir`).

---

## 10. Tests (TDD)

The pure layer is frozen by **reference vectors** before any UI.

### 10.1 Crypto/format vectors (golden, `FORMAT.md §9`)
- **KDF**: `pwd = "correct horse battery staple"`, `salt = 000102…0f`, `t3 m65536 p4` →
  `master = 853b27…b49e`, `keyLog = 156457…167b`, `keyCache = 7c39dd…0b3a` (exact equality).
- **`sample.ledger`** (`../finador/docs/format-testdata/`): decrypt end-to-end (header →
  records → trailer) and reproduce the documented accounts/assets/tx.

### 10.2 Round-trip & cross-implementation
- Write a ledger on the Android side, re-read it on the Android side (identical replay).
- **Cross-impl**: open with the **finador CLI** a `.fin` mutated by Android (and vice versa) —
  documented manual/CI test (the real criterion of "compatibility").
- **Merge**: synthetic divergences (concurrent adds/deletes/edits, LWW, true conflict) =
  parity with `merge_test.go`.

### 10.3 Network & UI
- GitHub Contents (fetch/push/conflict/missing) and market sources: **MockWebServer** + fixtures.
- Sync (pull-before/push-after, offline `dirty`, conflict→merge): fake backend.
- Screens: Compose UI tests; Keystore: instrumented test.

---

## 11. Phasing

1. **Format core (offline, TDD)**: `crypto/` (Argon2id/HKDF/AES-GCM/SHA/base64) → KDF vector
   green; `domain/` + `format/` read (header/record/log/replay) → `sample.ledger` green. Pure
   Kotlin, zero UI.
2. **Write path**: diff-on-save, sealing, reseal, atomic write on the working copy;
   round-trip; `merge` (union+LWW+`id` guard).
3. **Remote GitHub + secrets**: `GitHubBackend` (Contents API) + `Sync` (copy + `state.json`,
   pull-before/push-after, offline `dirty`, conflict→merge) + **`SecretStore`** (Keystore /
   EncryptedSharedPreferences: PAT **and** passphrase) with **BiometricPrompt unlock** from
   this foundation (requirement: PAT pasted+stored, passphrase stored once then biometrics
   alone). MockWebServer.
4. **Market**: Yahoo + FT + Morningstar (multi-source by ISIN), FX via USD, `FINCACHE2` cache.
5. **Compose UI**: onboarding (paste PAT, enter passphrase once, biometrics opt-in),
   biometric unlock at launch, overview, position detail, tx entry, sync, settings
   (re-login, "forget") + Canvas charts.
6. **Perf + polish** (good-to-have): TWR/XIRR/CAGR/vol/Sharpe/Sortino/maxDD; fine-grained
   offline indicators; visual refinements.

"Shippable" v1 = phases 1–5 (biometrics included from phase 3); 6 enriches.

---

## 12. Success criteria

1. Decrypts `sample.ledger` and reproduces the KDF vector **exactly**.
2. Opens, from the mobile, a real `.fin` created on the desktop; displays consistent gross/tax/net.
3. Entering a `tx` on the mobile → pull-before/push-after → **one** GitHub commit; the **finador CLI**
   reopens the file without error (bidirectional compatibility proven).
4. Remote conflict → reconciled via `merge` without loss; offline → local write + deferred push.
5. The repo contains only the **encrypted** `.fin`; the market cache stays **local** (never pushed).
6. Quotes retrieved multi-source (Yahoo→FT→Morningstar); valuation of FR/LU funds.
7. 401/403 distinct from offline; unknown `v`/`k` refused; PAT never in clear.
8. `./gradlew test` green (pure layer frozen by vectors); debug APK build OK.

---

## 13. Future developments (out of v1)

- Creation/editing of accounts & assets, config, labels (full write parity).
- Local file storage (SAF) / other hosts (`Backend` interface already in place).
- Git Blobs API if the `.fin` exceeds ~1 MB.
- Mobile compaction; widgets; iOS via extraction of a KMP core (the pure layer is already
  isolated there).
