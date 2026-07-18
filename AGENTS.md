# AGENTS.md - guide for AI coding agents

Read this first. It tells you what the project is, the rules you must not break, where things
live, and how to change/verify code **cheaply** (few tokens, fast feedback). Keep it up to date
when you change architecture or invariants.

## What this is

`finador-android` is a native Android app (Kotlin + Jetpack Compose) - the mobile companion to
**finador** (a Go CLI+web personal wealth tracker, at `../finador`). It reads/writes the same
**encrypted `.fin` ledger** and syncs it through a **private GitHub repo**. Scope: full read
(value, gains, per-asset detail), quick transaction entry, and account/asset management
(Settings → Manage accounts / Manage assets) - everyday parity with the desktop CLI and web.

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

The `Makefile` is the entry point - it exports `JAVA_HOME`/`ANDROID_HOME` itself, so targets work
from a fresh shell (run from the repo root):

```sh
make test                 # full unit suite (host JVM, no device) - your main loop
make test-class T=Gains   # one test class (cheap)
make build                # compile the debug APK (catches Compose/Android errors)
make lint                 # Android Lint (report: app/build/reports/lint-results-debug.txt)
make crossimpl            # byte-compat gate vs the Go reference (builds /tmp/finador first)
make help                 # everything else (install, run, release, emulator up/down, clean)
```

(The raw `./gradlew` tasks behind these work too, with `JAVA_HOME`/`ANDROID_HOME` exported:
`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`,
`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.)

- **Cheap feedback**: `make test` compiles the whole `main` (so it catches engine/UI compile
  errors) AND runs the pure-Kotlin tests, without a device. Prefer it.
- Test counts come from `app/build/test-results/testDebugUnitTest/*.xml` (grep `failures=`), since
  `--console=plain` only prints failures.
- Emulator (AVD named `test`, API 36): `make emulator` boots it headless and waits; `make run`
  installs + launches; check `adb logcat -d -s AndroidRuntime:E`; `make emulator-kill` stops it.
  A fresh install lands on **Onboarding** (no config); a configured emulator lands on **Unlock**
  (tap "Unlock" at `adb shell input tap 160 324`, screen 320x640; it has no enrolled biometric so
  it falls back to a direct button).
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
| `data/` | AppContainer, AppRepository, AppState, SecretStore | manual DI, the single facade, Keystore-encrypted secrets | Android |
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
- **A rejected GitHub token never blocks local data.** `Sync` records it as `SyncState.authError`
  (persistent "re-login" banner in the UI), reads/writes keep working locally (writes stay `dirty`),
  and the next successful fetch/push clears it. Only an unlock with NO local copy surfaces the error.
- **Argon2id is Bouncy Castle** (pure-JVM, so host unit tests run); not `argon2kt`.
- **Unquoted securities are never worth 0.** Valuation fallback chain (mirrors Go, asserted by
  `valuation/UnquotedTest`): market close → last statement of the (account, asset) pair (a NAV
  observation, scaled per share when the quantity changed since) → cost basis. The first statement
  of a position *bought* in the ledger (basis > 0) is a NAV observation (performance), not an
  adoption flow; only a declared holding (basis == 0) adopts.
- **Secrets**: `KeystoreSecretStore` encrypts values with an Android Keystore AES-GCM key into
  plain SharedPreferences. (The deprecated Jetpack `EncryptedSharedPreferences` and its one-shot
  migration shim were removed after v0.1.6 - the whole fleet had migrated.)
- **The 11 Material icons the app draws are inlined** in `ui/FinIcons.kt` (as `FinIcons.<Name>`
  `ImageVector`s, path data copied verbatim from androidx material-icons 1.7.8, Apache 2.0). There
  is no `material-icons-extended` dependency anymore - the old one was frozen upstream. Need another
  icon? Copy its `materialPath { ... }` body from the 1.7.8 sources into `FinIcons` (set
  `autoMirror = true` for direction-carrying icons); don't re-add the dependency.
- **Build types**: `debug` = dev (slow, debuggable). `release` = R8-minified, non-debuggable, ~6 MB,
  validated end-to-end. It's signed with the **real release key** when `FINADOR_STORE_FILE` & co. are
  set in `~/.gradle/gradle.properties` (never committed), and **falls back to debug signing** when
  they're absent (contributors / CI) - see `app/build.gradle.kts` `signingConfigs` and the out-of-repo
  notes file. The key/passwords live only in `~/.gradle/gradle.properties` + `~/finador-release.jks`.
- The single native lib is Compose's `libandroidx.graphics.path.so`; "Unable to strip" is a benign warning.

## Verifying a change cheaply

1. Engine/format/valuation/market change → `make test-class T=<Area>` first, then the full
   `make test`. For format edits (and toolchain/serialization upgrades) also run `make crossimpl`.
2. UI change → `make build` (compile) + optional emulator smoke (no crash on the relevant screen).
3. Always end on green tests + green build before claiming done. Don't trust a change you didn't run.
4. Deprecation/obsolescence sweep (occasional): a clean recompile prints zero `w:` deprecation
   warnings, and `lintDebug` (report in `app/build/reports/lint-results-debug.txt`) reports ONLY
   version-bump notices - anything else is a regression. Deliberate suppressions live in
   `app/lint.xml`, each with its rationale (read them before "fixing" what they cover).

## Known deferred work (intentional, with rationale)

- **Holdings replay is implemented twice** - `valuation/Valuator.kt` (full fold) and
  `valuation/Perf.kt`'s `SeriesBuilder` (day-walk). Extracting the shared per-tx transition logic
  would remove drift risk, but it touches parity-tested numbers - do it under the full suite.
- **`Gains.periodGain` rebuilds a full series per window** (8 windows). Building one series over the
  widest window and slicing (as Go's `report.go` does) is a pure speedup - verify TWR-per-window parity.
- **The SDK 37 wave is deliberately deferred** (user decision, July 2026): compileSdk/targetSdk
  36 → 37, lifecycle 2.10 → 2.11 (it hard-requires compileSdk 37) and Gradle 9.5 → 9.6 wait until
  the Android 17 platform settles and an API 37 emulator image is available for the smoke test.
  They are the ONLY remaining `lintDebug` notices - do not "fix" them piecemeal; do the wave in one
  pass under the full gates.
- The *data* lives in the user's separate private GitHub repo; this code repo is public at
  `github.com/bpineau/finador-android`.

## Pointers

- Format spec (authoritative): `../finador/docs/FORMAT.md`.
- Go reference: `../finador/internal/{store,domain,portfolio,perf,market}`.
- Design rationale lives in commit messages (no separate decision log - write commit messages
  that carry the why).
- Human setup + run: `README.md`. Common commands: `Makefile` (`make help`).
