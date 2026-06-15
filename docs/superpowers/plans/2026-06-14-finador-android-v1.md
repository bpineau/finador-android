# finador-android v1 - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline, batch
> with checkpoints) - this is a solo autonomous run. Steps use checkbox (`- [ ]`) tracking.
> Each phase ends with a **hard gate** (compile + tests green) before the next begins.

**Goal:** A native Android client (Kotlin/Compose) that reads/writes the finador `.fin` format
v3 bit-compatibly and syncs it through a private GitHub repo, with read-all + quick transaction
entry.

**Architecture:** Pure-Kotlin core (`crypto`/`domain`/`format`/`market`/`remote`/`valuation`,
no Android deps → JVM-unit-testable, frozen by the FORMAT.md §9 golden vectors) under a thin
Compose UI. Biometric-gated SecretStore holds the GitHub PAT and the ledger passphrase.

**Tech Stack:** Kotlin 2.x, Jetpack Compose (Material 3), Gradle Kotlin DSL + wrapper,
compileSdk/targetSdk 36, minSdk 26; argon2kt (Argon2id), OkHttp, Jsoup, kotlinx.serialization,
BigDecimal, AndroidX security-crypto + biometric, Coroutines.

**Normative references:** `../finador/docs/FORMAT.md` (format v3) and
`../finador/docs/superpowers/specs/2026-06-13-github-remote-data-design.md` (sync). Spec:
`docs/superpowers/specs/2026-06-14-finador-android-design.md`. Fold the FORMAT.md audit findings
before writing Phase 1 code.

**Toolchain (verified present):** `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`,
`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` (android-36, build-tools 36.1.0, adb,
emulator, AVD `test`). Build commands must export both explicitly.

---

## Conventions used by every task

- **Build env prefix** (every gradle command): `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew ...`
- **Package root:** `fin.android` (`app/src/main/kotlin/fin/android/...`).
- **JVM unit tests** (pure core): `app/src/test/kotlin/...` → `./gradlew testDebugUnitTest`.
- **Instrumented tests** (Keystore/biometric/Compose): `app/src/androidTest/kotlin/...`.
- **TDD:** for the core (Phases 1–4 + 6) write the failing test first, then implement. Commit per task.
- **Commit messages** end with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

## Shared interfaces (defined once, referenced by tasks)

```kotlin
// crypto
object Argon2 { fun hash(pw: ByteArray, salt: ByteArray, t: Int, m: Int, p: Int, len: Int = 32): ByteArray }
object Hkdf  { fun sha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, len: Int): ByteArray }
object AesGcm { fun seal(key: ByteArray, nonce: ByteArray, pt: ByteArray, aad: ByteArray): ByteArray
                fun open(key: ByteArray, nonce: ByteArray, ctTag: ByteArray, aad: ByteArray): ByteArray }
object Ids   { fun newId(nowMillis: Long, rnd: ByteArray): String }   // 23-char crockford
data class Keys(val log: ByteArray, val cache: ByteArray)
fun deriveKeys(passphrase: String, hdr: Header): Keys

// domain (BigDecimal money; enums as strings)
data class Money(val amount: BigDecimal, val ccy: String)
enum class TxKind { buy, sell, dividend, fee, deposit, withdraw, statement }
sealed class TaxRule { object None; data class Gains(val rate: BigDecimal); data class Value(val rate: BigDecimal) }
data class Book(val accounts: Map<String,Account>, val assets: Map<String,Asset>,
                val txs: Map<String,Tx>, val labels: Map<String,Label>, val config: Map<String,String>)

// format
data class Header(val v: Int, val kdf: String, val t: Int, val m: Int, val p: Int, val salt: ByteArray, val id: ByteArray, val rawLine: String)
class Ledger { val header: Header; val book: Book
    companion object { fun open(bytes: ByteArray, passphrase: String): Ledger }
    fun toBytes(): ByteArray                     // diff-on-save: verbatim prefix + appended records
    fun apply(mutation: Mutation): Ledger        // returns a ledger with the new record appended
    fun merge(other: Ledger, resolve: (Conflict)->Int): Ledger }

// remote
sealed class RemoteError : Exception() { object Conflict; object Missing; data class Auth(...); data class Offline(...) }
typealias Version = String
interface Backend { suspend fun fetch(): Pair<ByteArray, Version>      // throws RemoteError.Missing
                    suspend fun push(data: ByteArray, base: Version?, msg: String): Version  // throws Conflict
                    fun describe(): String }

// market
interface Source { suspend fun quote(asset: Asset): PriceSeries?; suspend fun fx(ccy: String): PriceSeries? }
```

---

## Phase 0 - Scaffold a buildable empty app (gate: APK builds)

**Files (create):** `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
`gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`,
`local.properties`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`,
`app/src/main/kotlin/fin/android/App.kt`, `app/src/main/kotlin/fin/android/ui/MainActivity.kt`,
`app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`.

- [ ] Generate the Gradle wrapper for a current Gradle (use the cmdline-tools or a one-shot
  `gradle wrapper` if available; otherwise write `gradle-wrapper.properties` for a pinned
  distribution and fetch the wrapper jar). Pin AGP + Kotlin + Compose BOM in `libs.versions.toml`.
- [ ] `local.properties`: `sdk.dir=/opt/homebrew/share/android-commandlinetools`.
- [ ] `app/build.gradle.kts`: android { compileSdk 36; defaultConfig { minSdk 26; targetSdk 36 }
  buildFeatures { compose = true } }, Kotlin/JVM target 17, dependencies for Compose BOM +
  Material3 + activity-compose only (more added per phase).
- [ ] `MainActivity` shows a `Text("finador")` Composable; `App : Application`.
- [ ] **Gate:** `…/gradlew assembleDebug` → `BUILD SUCCESSFUL`, APK at
  `app/build/outputs/apk/debug/app-debug.apk`. Commit.
- [ ] **Gate:** boot AVD headless (`emulator -avd test -no-window -no-audio -no-boot-anim &`,
  `adb wait-for-device`), `…/gradlew installDebug`, `adb shell am start -n <pkg>/.ui.MainActivity`,
  confirm no crash in `adb logcat` (then kill emulator). Commit.

## Phase 1 - Crypto + format reader (gate: KDF vector + sample.ledger decrypt)

Pure Kotlin. **TDD against FORMAT.md §9.** Add deps: `argon2kt`, kotlinx-serialization-json.

- [ ] **Test first - KDF vector** (`crypto/KdfTest.kt`): pw `correct horse battery staple`,
  salt hex `000102…0f`, t3 m65536 p4 ⇒ master `853b27…b49e`, keyLog `156457…167b`,
  keyCache `7c39dd…0b3a`. Implement `Argon2` (argon2kt), `Hkdf.sha256`, `deriveKeys` until green.
- [ ] **Test first - Crockford ID** (`crypto/IdsTest.kt`): fixed millis+rnd ⇒ 23-char id over
  alphabet `0123456789abcdefghjkmnpqrstvwxyz`, time-prefix monotonic. Implement `Ids.newId`.
- [ ] **Test first - AES-GCM round-trip** (`crypto/AesGcmTest.kt`): seal then open under AAD;
  tamper → AEADBadTagException. Implement `AesGcm` (Cipher AES/GCM/NoPadding, tag appended).
- [ ] **Header parse** (`format/Header.kt` + test): parse line-1 JSON, enforce bounds
  (`fmt=="finador-ledger"`, `v==3`, `kdf=="argon2id"`, 1≤t≤16, 8≤m≤1048576, 1≤p≤16,
  len(salt)==16, len(id)==16) **before** derivation; `hdrHash = SHA-256(raw line bytes)`.
- [ ] **Record + log read** (`format/Record.kt`,`format/Log.kt` + tests): split lines, for each
  record verify `AAD = hdrHash ‖ uint64_be(seq) ‖ prevTag` (seq 1-based, first prevTag = 16×0),
  chain tag = last 16 bytes of GCM output; trailer verify `count` and `head`.
- [ ] **Envelope + payload decode** (`format/Envelope.kt`, `domain/*` + tests): decode `{k,ts,d}`;
  all 10 kinds; decimals/Money as strings → BigDecimal; tax/asset-kind/tx-kind enum strings.
  **Unknown `k` = hard error.** Unknown fields tolerated.
- [ ] **Replay/fold** (`format/Replay.kt` + test): fold in file order (upsert/tombstone by id,
  config by key) → `Book`.
- [ ] **Gate - sample.ledger** (`format/SampleLedgerTest.kt`): copy
  `../finador/docs/format-testdata/sample.ledger` + passphrase from its README into test
  resources; `Ledger.open(...)` reproduces the documented accounts/assets/txs. `testDebugUnitTest`
  green. Commit.

## Phase 2 - Writer (diff-on-save) + merge (gate: round-trip + cross-impl with Go CLI)

- [ ] **Verbatim prefix + seal** (`format/Writer.kt` + test): keep each read record's verbatim
  base64 line; `toBytes()` re-emits them byte-for-byte, appends newly-sealed records continuing
  the chain (seq continues, prevTag running), re-seals the trailer for the new count.
- [ ] **Mutation/diff for tx** (`format/Mutation.kt` + test): `AddTx/EditTx/DelTx` → one record
  (`tx`/`tx-edit`/`tx-del`), `ts` = now UTC RFC3339Nano, fresh `Ids.newId` for adds. (v1 mobile
  only mutates transactions.)
- [ ] **Round-trip test:** open sample.ledger → AddTx → toBytes → re-open → folded Book contains
  the new tx; existing lines unchanged byte-for-byte.
- [ ] **Merge** (`format/Merge.kt` + test mirroring `internal/store/merge_test.go`): refuse on
  header-id mismatch; group by (class,id)/config-key; LWW by `ts`; identical (k,d) not a conflict;
  true tie → resolver; tombstone winner omitted; reseal sorted by ts.
- [ ] **Gate - cross-impl** (`scripts/crossimpl.sh`, documented): build the Go `finador`; create a
  ledger via CLI; open+AddTx via a tiny JVM harness (`./gradlew :app:run`-style main or a unit
  test writing to a temp file); re-open with `finador value` → no error, new tx visible; and
  vice-versa (Android opens a CLI-produced file). Record result in DECISIONS.md. Commit.

## Phase 3 - Remote GitHub + SecretStore + biometric (gate: sync against MockWebServer)

Add deps: OkHttp + MockWebServer (test), androidx.security:security-crypto, androidx.biometric.

- [ ] **SecretStore** (`data/SecretStore.kt` + instrumented test): EncryptedSharedPreferences
  (MasterKey AES256-GCM, Keystore-backed). API: `putPat/getPat`, `putPassphrase/getPassphrase`
  (passphrase entry requires `setUserAuthenticationRequired` → BiometricPrompt unlock), `purge()`.
- [ ] **GitHubBackend** (`remote/GitHubBackend.kt` + MockWebServer test): `fetch` GET contents
  (strip whitespace from base64 `content`; 404→Missing), `push` PUT (sha omitted on create;
  409/422→Conflict; 401/403→Auth); 1 retry on 5xx/429; network error→Offline.
- [ ] **RemoteConfig** (`remote/RemoteConfig.kt` + test): read/write `filesDir/config.json`
  (`source`, `github{owner,repo,path,branch}`, `readPullAfter`).
- [ ] **Sync** (`remote/Sync.kt` + test with fake Backend): working copy `filesDir/checkout/<hash>.fin`
  + `state.json{sha,lastPull,dirty}`; `openForRead` (pull if >readPullAfter or dirty);
  `mutate` (fetch→apply→toBytes→push; Conflict→fetch+merge+repush bounded; Offline→dirty);
  `sync()` (force pull + push-if-dirty).
- [ ] **Gate:** `testDebugUnitTest` (Sync/GitHub) + `connectedAndroidTest` (SecretStore) green.
  Commit.

## Phase 4 - Market data + sidecar cache (gate: source tests with fixtures)

Add dep: Jsoup. Fixtures captured into test resources.

- [ ] **CacheSidecar** (`market/CacheSidecar.kt` + test): path `cacheDir/finador/<id base64url-nopad>.cache`;
  `FINCACHE2 ‖ nonce ‖ AES-GCM(gzip(JSON(MarketData)), AAD=FINCACHE2)` under keyCache; missing/bad
  → null (regenerate).
- [ ] **Yahoo** (`market/Yahoo.kt` + MockWebServer test, JSON fixture): quote + dividends; FX via USD.
- [ ] **FT** (`market/Ft.kt` + Jsoup HTML fixture test) and **Morningstar**
  (`market/Morningstar.kt` + fixture test): parse last price by ISIN.
- [ ] **MultiSource** (`market/MultiSource.kt` + test): Yahoo→FT→Morningstar fallback by ISIN;
  merge into MarketData; persist cache.
- [ ] **Gate:** `testDebugUnitTest` market green. Commit.

## Phase 5 - Valuation + Compose UI (gate: app runs full flow on emulator)

Add deps: navigation-compose, lifecycle-viewmodel-compose, coil (icons optional).

- [ ] **Valuation** (`valuation/Value.kt` + test): positions per (account×asset), cost basis,
  current value via quote×qty + FX; tax per account rule (gains/value/none) → gross/tax/net;
  value series.
- [ ] **AppContainer** (`data/AppContainer.kt`): wires SecretStore, Backend, Sync, MultiSource,
  repositories; exposes coroutine-scoped flows. Manual DI.
- [ ] **Onboarding screen** (`ui/onboarding/*`): repo fields; **paste PAT** (button) → SecretStore;
  enter passphrase once → SecretStore behind biometric; first pull → unlock.
- [ ] **Unlock** (`ui/UnlockScreen.kt`): BiometricPrompt on launch → derive keys from stored
  passphrase; device-credential fallback.
- [ ] **Overview** (`ui/overview/*`): gross/tax/net totals; lines equities/property/cash; group
  tree; global sparkline (Canvas); sync-status banner.
- [ ] **Position detail** (`ui/position/*`): value/cost/PnL, labels (read), line chart (Canvas),
  tx list.
- [ ] **Tx entry** (`ui/txentry/*`): account picker → kind → asset (if applicable) → date/qty/
  amount/note → `Sync.mutate` → feedback (incl. offline `dirty`).
- [ ] **Sync + Settings** (`ui/sync/*`,`ui/settings/*`): sync-now, last pull, dirty/conflicts;
  repo/branch/path, readPullAfter, re-login, "forget" (purge).
- [ ] **Charts** (`ui/charts/*` + JVM tests for scaling math): line + sparkline on Canvas.
- [ ] **Gate:** `assembleDebug` green; boot AVD `test`, `installDebug`, drive onboarding→overview
  →tx entry against a test repo (or fake), confirm no crash in logcat. Commit.

## Phase 6 - Performance metrics (good-to-have)

- [ ] **Perf** (`valuation/Perf.kt` + tests mirroring `internal/perf`): TWR, XIRR, CAGR, vol,
  Sharpe, Sortino, maxDD per period/scope; surface on overview/detail. Commit.

---

## Self-review

- **Spec coverage:** format read (P1) / write+merge (P2) / GitHub sync+secrets+biometric (P3) /
  market multi-source+cache (P4) / valuation+UI+auth UX (P5) / perf (P6) - every spec §4–§9
  requirement maps to a phase. Storage-GitHub-only, PAT-paste-store, passphrase-once-biometric:
  P3+P5. Cross-impl compatibility criterion: P2 gate.
- **Placeholders:** none - each phase names exact files, the hard parts carry the algorithm, and
  every phase ends with a runnable gate. (UI tasks are component-level by design for a solo
  autonomous run; each still has a compile+run gate.)
- **Type consistency:** interfaces fixed in "Shared interfaces" and reused verbatim downstream
  (`Ledger.open/toBytes/apply/merge`, `Backend.fetch/push`, `Sync.openForRead/mutate/sync`,
  `SecretStore.get/put/purge`).
- **Audit dependency:** fold FORMAT.md audit findings into P1 before coding (noted in header).
