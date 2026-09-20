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
   pass (count them from `app/build/test-results/testDebugUnitTest/*.xml`, 304 today).
5. **Don't weaken security.** Secrets are encrypted under an Android Keystore key
   (`data/SecretStore.kt`); the repo holds only the *encrypted* `.fin`; never log secrets or write
   them to disk in clear.
6. **This repo is public: fixtures are fictitious.** Tests, sample data and docs name invented
   accounts (PEA Zephyr, CTO Meridia, AV Borealis, PEE Halcyon) and the house tickers
   (CW8.PA, GTWR) - never a real bank or broker the author holds an account at, never a real
   holding or amount. Public tickers and ISINs are fine as market-data vectors. The committed
   `app/src/test/resources/sample.ledger` is the Go reference's file, copied byte-for-byte.

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
- Emulator (AVD named `test`, API 36, screen 320x640): `make emulator` boots it headless and
  waits; `make run` installs + launches; check `adb logcat -d -s AndroidRuntime:E`;
  `make emulator-kill` stops it. The AVD holds NO configured state (wiped during the v0.1.7
  release smoke), so the app lands on **Onboarding** - smoke tests verify boot + first screen,
  not an unlocked portfolio. (If someone re-onboards it with real credentials, a configured
  install lands on **Unlock**; no enrolled biometric, so it falls back to a direct button at
  `adb shell input tap 160 324`.)
- Full repo workflow doc for humans: `README.md`.

## Architecture map

Package root `fin.android` under `app/src/main/kotlin/fin/android/`. The `crypto/domain/format/
market/valuation` layers are **pure Kotlin (no Android imports)** → fast to unit-test on the JVM.

| Area | Key files | Role | Pure? |
|---|---|---|---|
| `crypto/` | Argon2, Hkdf, AesGcm, Hashes, Bytes, Ids | KDF, AEAD, base64, Crockford ids | ✅ |
| `domain/` | Models, Money, MarketData | data model (BigDecimal money, enums, Book) | ✅ |
| `format/` | Header, Kdf, Wire, Log, Replay, Writer, Merge, Ledger | read/write the `.fin` (AAD-chained records, fold, diff-on-save, union+LWW merge) | ✅ |
| `market/` | Yahoo, Ft, Morningstar, Airfund, Nowcast, Session, Units, VenueDay, MultiSource, Converter, FxRates, CacheSidecar, Quotes, Source | fetch quotes (JSON + a Boursorama regex), FX via USD, FINCACHE2 cache; `Airfund` = the NAV feed of the employee-savings funds (FCPE) no quote site covers, with a bundled offline baseline; `Nowcast` = the estimated tail those funds need between their last published NAV and now, anchored on the print that NAV was struck on (`AirfundFund.navAnchor`: the proxy's close by default, its OPEN for a fund valued at its holding's opening price, as `ERES_DATADOG` is); `FxRates` = the display-only read-back of the rates a valuation crossed at (`AppState.Ready.fxRates`, a caption under the total); `Session` + `Quotes.refreshExtended` = the extended-hours opt-in (Settings → Display, off by default), parity with the Go `value --extended`: a US pre/post-market print prices the line, the total AND every figure shown beside them (the gains table's value column, the detail page's price/value) when it is fresher than the regular one, labelled "pre 08:14" / "post 19:59" and NEVER stored (the same pass stores exactly what the plain one does); it also EXPIRES - `Session.stillCurrent` / `Quotes.current` drop a print once its session is over (pre at the regular open, post at the next pre-market open, New York clock, weekends skipped), re-read against `AppRepository`'s injectable clock on every emit, so the screen falls back to the closes by itself; `Units` = the venue SUB-UNITS (`GBp`/`GBX` pence, `ZAc`, `ILA`, `USX`) a provider reports where a currency is expected, removed where its numbers enter the app and folded by `Units.same` in every currency comparison; `VenueDay` = the calendar a bar or print belongs to, read in the venue's own zone (`exchangeTimezoneName`), forward only, a currency cross exempt | ✅ |
| `valuation/` | Valuator, Perf, Gains | gross/tax/net, TWR/XIRR/etc., period & per-asset gains, asset detail; `priceOverrides` (an off-hours print) reaches `Valuator.value`, `Gains.report` and `Gains.assetDetail` - a valuation and every figure shown with it stand on one price - and NEVER `Perf` or the period/history figures (Go D36/D38: no override in `perf` or `chart`) | ✅ |
| `storage/` | AtomicFile | the one way a file is replaced: tmp + fsync + rename (+ `.bak`), so a killed process never leaves a short ledger or a short cache | ✅ |
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
- **A quote source / parsing** → `market/{Yahoo,Ft,Morningstar,Airfund}.kt`; fixtures in the market tests.
- **Another employee-savings fund (FCPE)** → one entry in `market/Airfund.kt`'s `AirfundFunds.ALL`
  (share code + nowcast proxy, plus `navAnchor = NavAnchor.OPEN` when the fund's valuation rules
  name its holding's OPENING price, mirroring the Go catalog's `nowcast_anchor`) plus its NAV
  baseline in `app/src/main/resources/fin/android/market/<TICKER>-NAV.csv`, copied from the Go
  reference's `refdata/`. The ledger asset just carries the ticker; nothing else changes.
- **Sync behaviour** (conflict, offline, pull cadence) → `remote/Sync.kt`.
- **A screen / styling** → `ui/<Screen>.kt`; colors/typography in `ui/Theme.kt`
  (accent = terracotta `#C2613C`; gain/loss via `gainLossColor(...)`); number formatting in `ui/Format.kt`.
- **App state / orchestration** → `data/AppRepository.kt` (+ `AppState.kt`, `AppViewModel.kt`).

## Gotchas & non-obvious things

- **`AppRepository` mutations are serialized by a `Mutex`** (`exclusive { }`). The Mutex is **not
  reentrant** - a locked public method must call the `*Locked` private helpers, never another public
  (locked) method (else deadlock). See `refreshQuotesLocked`.
- **Estimates are never cached.** A fund published with a lag (`market/Airfund.kt`) gets a nowcast
  tail read off a listed proxy (`market/Nowcast.kt`), flagged by `PriceSeries.estimatedFrom` /
  `estimateProxy`. It is recomputed at every refresh, never stored: `Quotes.refresh` strips the
  previous run's tail before merging anything, and `CacheSidecar.write` strips it again on the way
  to disk (which also keeps the FINCACHE2 JSON byte-compatible with Go, whose DTO has no such
  field). Anything showing an estimated price must SAY it is one, as `AssetDetailScreen` does.
  The proxies are fetched even when the user holds none of them, cached under `proxy:<SYMBOL>` in
  `MarketData.prices` (no ledger id can collide: those are Crockford base32).
- **A nowcast anchors on the print the NAV was struck on**, not always on a close. A fund carrying
  `NavAnchor.OPEN` (`ERES_DATADOG`, valued at the NASDAQ opening price) divides by the proxy's OPEN
  of the last NAV's day, which reaches the estimate as the session's open-to-close RATIO
  (`DailyData.openFactors`, read off the Yahoo chart payload's `open` column, held for one pass and
  stored nowhere, so no asset gains a cached field). Every failure mode falls back on the close and
  none is an error: no `open` column, a day the proxy did not trade, a failed fetch. An ESTIMATED
  anchor day keeps the close it was built from, and a nowcast still never overwrites a published NAV.
- **A source that restates its history makes the series be rebuilt.** The daily fetch is
  incremental (`Quotes.fetchFrom` resumes at the last cached close), but a share split, a currency
  redenomination or a class merge rewrites the whole served history, so merging would glue the old
  scale in front of the new one and leave a permanent cliff the valuation, the chart and the TWR
  read as a session that never happened. The overlap day is the canary: more than 2 % away from the
  cached close and the series is dropped and refetched from the floor, with a warning (`Refresh.warnings`
  → snackbar) asking for the ledger quantities to be checked, since a split moves the position too.
  Mirrors Go D40; estimates are stripped before the comparison, so a nowcast tail never triggers it.
  When the measured factor matches a plain split ratio (`Quotes.splitRatioFor`: 2:1, 3:1, 4:1, 3:2,
  their reverses...), the warning NAMES the split and lists the quantities each pre-split trade
  owes - multiply the quantity, leave the amount alone, which is the faithful correction once the
  whole price history has been re-scaled. A factor matching no ratio (a currency redenomination)
  claims none. Mirrors Go D47, which also holds the proposal for a native `split` record: the
  ledger has no way to restate a quantity, and adding a transaction kind would make an older
  reader reject the file, so it is a version-bump decision, not a bugfix.
- **A currency reaches the book three ways**: an account is denominated in one, an asset quotes in
  one, and a RECORD may be written in a fourth (a fee in JPY, a deposit in CHF). `Quotes` collects
  all three, and the FX window reaches a week before the OLDEST record (`fxHistoryFloor`), because
  a historical deposit is crossed at the rate of its own day. When a rate is still missing,
  `Valuator` counts the amount as 0 - a phone screen has to render, where the Go CLI refuses the
  total - and NAMES the record in `Valuation.taxNote`: its kind, amount, currency, date, asset,
  envelope and id. `Perf` stays silent on purpose: it reads the same ledger, so the note beside the
  curve already names what it could not convert. Mirrors Go D43.
- **Never sum `Double` in a map's own order when the order is not the ledger's.** The addition is
  not associative, so the same per-envelope taxes added in two orders differ in their last digits,
  and the Go reference sums that exact list by sorted account id. `Valuer` does the same, which is
  what keeps the two implementations comparable figure for figure. Mirrors Go D46.
- **A currency code is never compared with `==` or `equalsIgnoreCase`.** A venue quotes in a
  SUB-UNIT and the provider reports it where a currency is expected: Yahoo answers `GBp` for a
  London line and prices it in PENCE, FT spells it `GBX`, Johannesburg is `ZAc`, Tel Aviv `ILA`,
  some US futures `USX`. `GBp` and `GBP` differ by case alone, so a case-INSENSITIVE compare books
  pence as pounds (a 100x valuation error no plausibility check can see, since rescaling a series
  leaves every return untouched) and a case-SENSITIVE one refuses the series instead, leaving the
  holding at its cost basis for ever. `market/Units.kt` removes the sub-unit where each provider's
  numbers enter the app and `Units.same` answers every "is this the declared currency?" question.
  A RATIO (`DailyData.openFactors`) carries no currency and is never rescaled. Mirrors Go
  `pkg/marketdata/units.go`.
- **A daily bar carries an instant of the session, not a date** (`market/VenueDay.kt`, Go's
  `sessionDay`). Truncated in UTC, an ASX bar lands one day early (Sydney opens 10:00 = 23:00 UTC
  the day before in summer), so Sunday closes appear and Fridays go missing, and every date-matched
  join - the previous close a day change reads, the FX rate of the day, the canary's overlap day -
  quietly misses. The day is read in the venue's own zone (`exchangeTimezoneName`), FORWARD ONLY (a
  UTC reading is never late, only early), and a currency cross is exempt: its Yahoo dating carries
  a separate, known weekend anomaly that a time zone would hide rather than fix.
- **Every persisted file is replaced atomically** (`storage/AtomicFile.kt`, mirroring the Go
  reference's `atomicWrite`): the working copy (with a `.bak`), the sync state and the FINCACHE2
  sidecar. `File.writeBytes` truncates first, and Android kills backgrounded processes whenever it
  wants memory, so the plain call leaves a short file often enough to matter - and a short `.fin`
  authenticates as nothing while the dirty guard forbids pulling over it, which is a permanent
  brick. Two consequences in `remote/Sync.kt`: an unreadable state file reads as `dirty = true`
  (never as "nothing to push", which would let a pull overwrite unpushed records), and
  `openForRead` falls back to the `.bak`, promotes it and marks it dirty so the next sync MERGES it.
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
- **A `dirty` working copy is never overwritten by a fetch.** It holds records the remote has never
  seen. `Sync.mutate` merges the fetched remote into it (rather than writing the remote bytes over
  it) and `pullIfStale` skips the pull entirely while dirty, since that path has no passphrase to
  merge with. Asserted by `remote/SyncTest`'s two "unpushed local change" tests.
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
  validated end-to-end. It's signed with the **real release key** when `FINADOR_STORE_FILE`,
  `FINADOR_STORE_PASSWORD`, `FINADOR_KEY_ALIAS` and `FINADOR_KEY_PASSWORD` are set - in
  `~/.gradle/gradle.properties` (never committed) or, failing that, in the **environment**, so a CI
  runner can inject them as secrets. It **falls back to debug signing** when they're absent
  (contributors / CI) - see `app/build.gradle.kts` `signingConfigs`. The key and its passwords live
  ONLY outside this repo (`~/finador-release.jks` + `~/.gradle/gradle.properties`); nothing about
  them is ever committed, printed or uploaded.
- **Releasing**: `make gh-release` runs the gates (`test`, `crossimpl`), builds the release APK,
  **verifies its signature with `apksigner`**, tags, pushes and creates the GitHub release **with the
  APK attached** as `finador-android-v<version>.apk`. It REFUSES to publish a debug-signed APK: that
  key is public and cannot upgrade an installed app. `DEBUG_APK=1` publishes one deliberately, named
  `-debug`. The target is re-runnable (an existing release gets `gh release upload --clobber`, an
  existing tag at HEAD is reused). Two cheap probes before releasing: `make check-signing` (is a real
  key configured here? prints no secret) and `make gh-release-dry-run` (everything except the tag,
  the push and the release).
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
  would remove drift risk, but it touches parity-tested numbers - do it under the full suite. Until
  then `valuation/EndpointFuzzTest` is the net: 20000 random ledgers, several records on the same
  few days, and the last point of the series must equal the valuation (Go D39/D41 were both found
  by its Go twin, `internal/portfolio/endpoint_fuzz_test.go`).
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
