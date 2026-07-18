# AGENTS.md - guide for AI coding agents

Read this first. It tells you what the project is, the rules you must not break, where things
live, and how to change/verify code **cheaply** (few tokens, fast feedback). Keep it up to date
when you change architecture or invariants.

## What this is

`finador-android` is a native Android app (Kotlin + Jetpack Compose) - the mobile companion to
**finador** (a Go CLI+web personal wealth tracker, at `../finador`). It reads/writes the same
**encrypted `.fin` ledger** and syncs it through a **private GitHub repo**. v1 = read everything
(value, gains, per-asset detail) + quick transaction entry. Accounts AND assets are now manageable
on both web and mobile (Settings → Manage accounts / Manage assets), alongside the CLI.

## Golden rules (do not break)

1. **The `.fin` format is law.** `../finador/docs/FORMAT.md` is the authoritative spec; the Go code
   in `../finador/internal/store` is the reference. Any change under `crypto/` or `format/` must keep
   reading/writing byte-compatible files. Proof gate: `scripts/crossimpl.sh` (Go reads an
   Android-written file and vice-versa) **must** stay green, and the golden tests
   (`format/SampleLedgerTest`, `crypto/KdfTest`) must pass.
2. **Valuation/market mirror Go.** `valuation/` and `market/` are faithful ports of
   `../finador/internal/{portfolio,perf,market}`. The unit tests assert the *same numbers* as the Go
   `*_test.go`. Don't change the math without checking parity; if you must, update the Go reference too.
3. **All docs / comments / code in English.** (User convention.)
4. **Keep the suite green.** Run the full `testDebugUnitTest` before claiming done; every test must
   pass (count them from `app/build/test-results/testDebugUnitTest/*.xml`, 156 today).
5. **Don't weaken security.** Secrets are encrypted under an Android Keystore key
   (`data/SecretStore.kt`); the repo holds only the *encrypted* `.fin`; never log secrets or write
   them to disk in clear.

## Build / test / run (env is required)

The Gradle wrapper needs both env vars (the build shell usually doesn't inherit the user's profile):

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
P="$(git rev-parse --show-toplevel)"   # the repo root

"$P/gradlew" --project-dir "$P" testDebugUnitTest            # unit tests (host JVM, no device) - your main loop
"$P/gradlew" --project-dir "$P" assembleDebug               # compile the APK (catches Compose/Android errors)
"$P/gradlew" --project-dir "$P" testDebugUnitTest --tests "*GainsTest*" --rerun-tasks   # one test class (cheap)
```

- **Cheap feedback**: `testDebugUnitTest` compiles the whole `main` (so it catches engine/UI compile
  errors) AND runs the pure-Kotlin tests, without a device. Prefer it. Use `--tests "*Foo*"` to scope.
- Test counts come from `app/build/test-results/testDebugUnitTest/*.xml` (grep `failures=`), since
  `--console=plain` only prints failures.
- Emulator (named `test`, API 36): boot headless
  `"$ANDROID_HOME/emulator/emulator" -avd test -no-window -no-audio -no-boot-anim &`, then
  `adb wait-for-device`, poll `adb shell getprop sys.boot_completed`, `gradlew installDebug`,
  `adb shell am start -n fin.android/.ui.MainActivity`, check `adb logcat -d -s AndroidRuntime:E`.
  A fresh install lands on **Onboarding** (no config); a configured emulator lands on **Unlock**
  (tap "Unlock"; it has no biometric so it falls back to a direct button).
- Full repo workflow doc for humans: `README.md`.

## Architecture map

Package root `fin.android` under `app/src/main/kotlin/fin/android/`. The `crypto/domain/format/
market/valuation` layers are **pure Kotlin (no Android imports)** → fast to unit-test on the JVM.

| Area | Key files | Role | Pure? |
|---|---|---|---|
| `crypto/` | Argon2, Hkdf, AesGcm, Hashes, Bytes, Ids | KDF, AEAD, base64, Crockford ids | ✅ |
| `domain/` | Models, Money, MarketData | data model (BigDecimal money, enums, Book) | ✅ |
| `format/` | Header, Kdf, Wire, Log, Replay, Writer, Merge, Ledger | read/write the `.fin` (AAD-chained records, fold, diff-on-save, union+LWW merge) | ✅ |
| `market/` | Yahoo, Ft, Morningstar, MultiSource, Converter, CacheSidecar, Quotes, Source | fetch quotes (JSON + a Boursorama regex), FX via USD, FINCACHE2 cache | ✅ |
| `valuation/` | Valuator, Perf, Gains | gross/tax/net, TWR/XIRR/etc., period & per-asset gains, asset detail | ✅ |
| `remote/` | Backend, GitHubBackend, RemoteConfig, Sync | GitHub Contents API, pull/mutate/push + conflict→merge + offline-dirty | Android-light |
| `data/` | AppContainer, AppRepository, AppState, SecretStore, LegacySecretMigration | manual DI, the single facade, Keystore-encrypted secrets | Android |
| `ui/` | AppRoot, AppViewModel, *Screen, Theme, Format | Compose screens, MVVM, theme | Android |

Data flow: `MainActivity` → `AppRoot` renders `AppViewModel.state: StateFlow<AppState>`
(Loading/Onboarding/Locked/Ready). `AppRepository` is the only mutator: it opens the ledger via
`Sync` (working copy in `filesDir/checkout`), values it (`Valuator`/`Gains`/`Perf`), and emits
`AppState.Ready(valuation, perf, gains, book, sync, message, refreshing, assetDetails)`. UI reads
that state; per-asset detail pages are **precomputed** into `Ready.assetDetails` for instant opens.

## Where to change X (quick index)

- **New record kind / format field** → `format/Wire.kt` (DTO) + `format/Replay.kt` (fold) +
  `FORMAT.md` + a test; bump version only per `FORMAT.md §8`.
- **A valuation/gain/perf number** → `valuation/{Valuator,Perf,Gains}.kt`; mirror the Go change and
  the parity test.
- **A quote source / parsing** → `market/{Yahoo,Ft,Morningstar}.kt`; fixtures in the market tests.
- **Sync behaviour** (conflict, offline, pull cadence) → `remote/Sync.kt`.
- **A screen / styling** → `ui/<Screen>.kt`; colors/typography in `ui/Theme.kt`
  (accent = terracotta `#C2613C`; gain/loss via `gainLossColor(...)`); number formatting in `ui/Format.kt`.
- **App state / orchestration** → `data/AppRepository.kt` (+ `AppState.kt`, `AppViewModel.kt`).

## Gotchas & non-obvious things

- **`AppRepository` mutations are serialized by a `Mutex`** (`exclusive { }`). The Mutex is **not
  reentrant** - a locked public method must call the `*Locked` private helpers, never another public
  (locked) method (else deadlock). See `refreshQuotesLocked`.
- **The market cache is NOT synced** (per-device, regenerable). A freshly synced device has the
  ledger but no prices until `refreshQuotes` runs → period gains read ~0 until quotes load, and
  statement-valued assets (property, cash) have no market "performance" by design.
- **Gains = flow-neutralized market performance** (user-confirmed). Property revaluations and
  deposits are flows, not gains. Don't "fix" the ~0 on a property-heavy portfolio.
- **`Ledger.toBytes()` is diff-on-save**: existing record lines are re-emitted verbatim; only new
  records are sealed and the trailer re-sealed. `merge` re-seals the whole chain (matches Go).
- **Timestamps must be `Locale.ROOT`** (`format/Timestamps.kt`) - the `ts` is the sealed LWW key.
- **Argon2id is Bouncy Castle** (pure-JVM, so host unit tests run); not `argon2kt`.
- **Unquoted securities are never worth 0.** Valuation fallback chain (mirrors Go, asserted by
  `valuation/UnquotedTest`): market close → last statement of the (account, asset) pair (a NAV
  observation, scaled per share when the quantity changed since) → cost basis. The first statement
  of a position *bought* in the ledger (basis > 0) is a NAV observation (performance), not an
  adoption flow; only a declared holding (basis == 0) adopts.
- **Secrets**: `KeystoreSecretStore` encrypts values with an Android Keystore AES-GCM key into
  plain SharedPreferences (the deprecated Jetpack `EncryptedSharedPreferences` was replaced).
  `data/LegacySecretMigration.kt` + the `androidx.security:security-crypto` dependency exist ONLY
  to migrate pre-v0.1.6 installs - delete both together once installed devices have migrated.
- **material-icons-extended is frozen upstream** (pinned at 1.7.8 in `libs.versions.toml`, no
  longer BOM-managed). Long-term exit: inline the ~11 used icons as ImageVectors and drop it.
- **Build types**: `debug` = dev (slow, debuggable). `release` = R8-minified, non-debuggable, ~6 MB,
  validated end-to-end. It's signed with the **real release key** when `FINADOR_STORE_FILE` & co. are
  set in `~/.gradle/gradle.properties` (never committed), and **falls back to debug signing** when
  they're absent (contributors / CI) - see `app/build.gradle.kts` `signingConfigs` and the out-of-repo
  notes file. The key/passwords live only in `~/.gradle/gradle.properties` + `~/finador-release.jks`.
- The single native lib is Compose's `libandroidx.graphics.path.so`; "Unable to strip" is a benign warning.

## Verifying a change cheaply

1. Engine/format/valuation/market change → `testDebugUnitTest --tests "*<Area>Test*"` first, then the
   full `testDebugUnitTest`. For format edits also run `scripts/crossimpl.sh` (needs a built
   `/tmp/finador`: `cd ../finador && go build -trimpath -o /tmp/finador ./cmd/finador`).
2. UI change → `assembleDebug` (compile) + optional emulator smoke (no crash on the relevant screen).
3. Always end on green tests + green build before claiming done. Don't trust a change you didn't run.

## Known deferred work (intentional, with rationale)

- **Holdings replay is implemented twice** - `valuation/Valuator.kt` (full fold) and
  `valuation/Perf.kt`'s `SeriesBuilder` (day-walk). Extracting the shared per-tx transition logic
  would remove drift risk, but it touches parity-tested numbers - do it under the full suite.
- **`Gains.periodGain` rebuilds a full series per window** (8 windows). Building one series over the
  widest window and slicing (as Go's `report.go` does) is a pure speedup - verify TWR-per-window parity.
- **A rejected GitHub token never blocks local data.** `Sync` records it as `SyncState.authError`
  (persistent "re-login" banner in the UI), reads/writes keep working locally (writes stay `dirty`),
  and the next successful fetch/push clears it. Only an unlock with NO local copy surfaces the error.
- The *data* lives in the user's separate private GitHub repo; this code repo is public at
  `github.com/bpineau/finador-android`.

## Pointers

- Format spec (authoritative): `../finador/docs/FORMAT.md`.
- Go reference: `../finador/internal/{store,domain,portfolio,perf,market}`.
- Design/decisions: `docs/superpowers/{specs,plans,DECISIONS.md}`.
- Human setup + run: `README.md`.
