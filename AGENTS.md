# AGENTS.md - guide for AI coding agents

Read this first, all of it, before touching anything. It says what the project is FOR, the rules
that decide every trade-off, how to build and verify cheaply, where things live, and how people get
this code wrong. If a request and this file disagree, this file wins until a human says otherwise.
Keep it up to date when you change architecture or invariants.

## 1. What this is, and what it is for

`finador-android` is a native Android app (Kotlin + Jetpack Compose) for **tracking a household's
whole net worth on a phone**: what it is worth today gross / after tax / net, what it gained over
each period, what each asset contributed, and entering a transaction (a buy, a deposit, a fee) in
the seconds you actually have to enter it.

It is the mobile half of a two-program system:

| Repo | What it is | Direction |
|---|---|---|
| `../finador` | Go CLI + web, the DESKTOP program and the **reference implementation** of the file format and of every number | this app is a port OF it |
| `finador-android` (here) | the phone client | reads and writes the SAME file |
| a private GitHub repo (the user's, not this one) | where the encrypted ledger lives | both programs sync through it |

The two programs share one encrypted `.fin` ledger file, pushed and pulled through the GitHub
Contents API. There is no server, no account, no backend: GitHub is the transport, the passphrase is
the only key, and a phone that is offline still reads and writes locally.

How a change propagates: a change to the FORMAT or to a computed number starts in `../finador`
(spec + Go code + Go test), then lands here as a mirror with the same test numbers, and
`make crossimpl` proves the two still read each other's files. Never the other way round.

**In scope**: full read (value, gains, per-asset detail), quick transaction entry, account and asset
management, quote fetching, GitHub sync, biometric unlock.

**Deliberately NOT in scope**: brokerage connectivity or any automatic import; trading; a backend of
our own; analytics, telemetry or crash reporting; ads; any network call to anything but GitHub and
the four quote providers; notifications, widgets, background work; anything that needs a permission
beyond `INTERNET`.

## 2. Engineering principles (the ones that decide trade-offs)

The app must keep working, on future Android versions and future phones, with **little or no
maintenance**. Android has no compatibility promise: every year an API level changes behaviour,
deprecates APIs and eventually removes them. Everything below follows from that one requirement,
and it outranks features. When in doubt, do less.

### (a) The platform first. A third-party library is a liability that must be argued for.

- DO use the platform and first-party AndroidX / Kotlin APIs, even when it costs a hundred lines.
  `net/Http.kt` is the worked example: the whole HTTP stack is `HttpURLConnection`, and okhttp left
  because of it (`docs/maintainability.md` §6). `EncryptedSharedPreferences` and
  `material-icons-extended` left the same way.
- DO NOT add a dependency to save a morning. A new library needs a written entry in
  `docs/maintainability.md` (what it does, who maintains it, what breaks if it dies, what it costs
  per year) BEFORE the code that uses it, and it is removed as soon as the platform can do the job.
- Bouncy Castle is the one library kept purely for an algorithm the platform lacks: Argon2id
  (`docs/maintainability.md` §5). That is the shape of an acceptable justification.
- There is NO HTTP library in this app. Adding one is a regression, not a fix.

### (b) No deprecated API, and every warning is a bug.

- DO fix, not silence. A clean recompile prints **zero** `w:` deprecation warnings and `make lint`
  prints `No issues found`. Both are gates, not aspirations.
- DO NOT add `@Suppress`, `@SuppressLint` or a lint baseline to get green. The only allowed
  suppressions live in `app/lint.xml`, each carrying its rationale: read them before "fixing" what
  they cover, and add one only with the same kind of written reason.
- DO NOT use an API that is deprecated, or announced for deprecation, in new code. How to read a
  deprecation notice (does it hit every app or only the new `targetSdk`? what is the removal
  version? is the replacement available at `minSdk` 26?) is README §6.
- Experimental / `@OptIn` APIs only when the fallback if they are withdrawn is written down:
  `docs/maintainability.md` §12 lists every current opt-in and its reason.

### (c) Stay current, in the documented order. A deferred upgrade wave freezes everything behind it.

- DO run the yearly wave in README §6's order: Gradle wrapper -> AGP -> Kotlin -> `compileSdk` ->
  AndroidX libraries -> `targetSdk`, `make test` after each step, one commit each. The order is not
  negotiable: each tool gates the next.
- DO read both behaviour-change pages for the new API level and answer them row by row into
  `docs/maintainability.md` §8 before moving `targetSdk`.
- DO NOT bump one version out of order, and DO NOT bump a dependency "back" because it demands a
  newer `compileSdk`: that message is the truth, bump the platform. A deferred wave is what once
  froze three libraries behind an SDK level.
- DO NOT skip the emulator smoke. Compiling against a new platform proves nothing about running on
  it (`make setup-emulator && make emulator && make run`).

### (d) The build is reproducible and the environment is one command.

- DO keep every version exact in `gradle/libs.versions.toml` (no `+`, no range, no snapshot), the
  Gradle distribution pinned by URL **and** SHA-256, and the JDK pinned by the Gradle toolchain in
  `app/build.gradle.kts` rather than inherited from the machine.
- DO keep `make setup` able to equip a bare laptop and `make doctor` able to say what is missing
  and print the exact fix. Every build target depends on `preflight` so a missing environment reads
  as "run make doctor", not as a Gradle stack trace.
- DO NOT add a step that only works on one machine, a tool installed by hand, an absolute path, or
  an environment variable a fresh clone does not get from the Makefile.

### (e) Byte compatibility with the Go reference is law.

- DO treat `../finador/docs/FORMAT.md` as the spec and `../finador/internal/store` as the reference.
  Any change under `crypto/` or `format/` must keep reading and writing byte-identical files.
- DO run `make crossimpl` (Go reads an Android-written file and vice versa) for any such change, and
  keep `format/SampleLedgerTest` and `crypto/KdfTest` green.
- DO NOT change a computed number here alone: `valuation/` and `market/` are faithful ports of
  `../finador/internal/{portfolio,perf,market}` and the tests assert the SAME numbers. Change the Go
  reference first, mirror it here, update both tests.

### (f) A feature that adds yearly upkeep must earn it.

- DO prefer the version of a feature that adds no permission, no background work, no service, no
  reflection and no library. Today the app declares one permission (`INTERNET`), one activity, no
  service, no receiver, no background work and no reflection; that is why most behaviour-change rows
  answer "no".
- DO NOT ship a feature whose upkeep is not written down. If it needs a yearly check, it belongs in
  `docs/maintainability.md` §9 with the others, or it does not ship.

If you were asked for "a quick fix" and the shortest path is to add a library, silence a warning,
bump one version out of order, or skip a gate: that is not the quick path here. Do the slower,
smaller thing, or stop and say what the trade-off is.

## 3. The other non-negotiables

1. **Don't weaken security.** Secrets are encrypted under an Android Keystore key
   (`data/SecretStore.kt`); the remote repo holds only the *encrypted* `.fin`. Never log a secret,
   a passphrase, a token or a derived key, and never write one to disk in clear.
2. **This repo is public: every fixture is fictitious.** Tests, sample data and docs use invented
   accounts (PEA Zephyr, CTO Meridia, AV Borealis, PEE Halcyon) and house tickers (CW8.PA, GTWR).
   Never a real bank or broker as somebody's, never a real holding, never a real amount. Public
   tickers and ISINs are fine as market-data vectors. `app/src/test/resources/sample.ledger` is the
   Go reference's file, copied byte for byte.
3. **Nothing personal in this repository.** No real name, no employer, no home path, no server
   name, no amount, no screenshot of real data, in code, tests, docs or commit messages.
4. **English everywhere** - code, comments, docs, commit messages.
5. **Never a typographic dash.** No em-dash, no en-dash, anywhere in any file. Use a comma, a colon,
   parentheses or a plain hyphen.
6. **Keep the suite green.** The full unit suite must pass before anything is called done.

## 4. How to work

The `Makefile` is the entry point: it works out `JAVA_HOME` and `ANDROID_HOME` and exports them
itself, so every target works from a bare shell. Run from the repo root.

| Command | What success looks like | Cost |
|---|---|---|
| `make doctor` | read-only; prints OK / MISSING per tool with the exact fix command | instant |
| `make setup` | idempotent install of what is missing (macOS/Homebrew); never touches a signing key | minutes, once |
| `make test` | **the main loop**; prints `summary: N tests, 0 failures, 0 errors` | ~1 s warm, ~1 min cold |
| `make test-class T=Gains` | one class, when you know where you broke it | seconds |
| `make build` | the debug APK; catches Compose/Android errors `make test` cannot | ~1 min |
| `make lint` | must print `No issues found`; report in `app/build/reports/lint-results-debug.txt` | ~1 min |
| `make crossimpl` | byte-compat gate vs the Go reference (builds finador first, needs `../finador`) | ~1 min |
| `make probe` | hits the REAL quote providers from the HOST JVM; opt-in, never part of `make test` | seconds |
| `make probe-device` | the same probe on a booted emulator or a phone; **the one live check a release owes** | ~1 min |
| `make help` | every other target (install, run, release, emulator up/down, clean) | instant |

`make test` compiles the whole `main` source set as well as the tests, so it catches engine AND UI
compile errors without a device. Prefer it. The per-test detail is in
`app/build/test-results/testDebugUnitTest/*.xml`; `--console=plain` only prints failures, which is
why the Makefile prints the summary line itself.

**Verifying a change cheaply**

1. Engine / format / valuation / market change: `make test-class T=<Area>` first, then full
   `make test`. For anything under `crypto/` or `format/`, also `make crossimpl`.
2. UI change: `make build`, plus an emulator smoke if a screen's behaviour changed.
3. Suspecting the live internet rather than the code: `make probe` prints a `tls` block (what the
   platform offers, what this app offers, and the status each gets) and a reachability line per
   provider host before it tries to parse anything. A host-JVM probe cannot speak for a phone,
   though: run `make probe-device` before believing a red one (§7, "Every provider answers 429").
4. Never claim a result you did not run.

**Emulator.** `make setup-emulator` creates the AVD named after `compileSdk` (`test37` today);
`make emulator` boots it headless and waits, `make run` installs and launches, `make emulator-kill`
stops it. The AVDs hold no configured state, so the app lands on **Onboarding**: the smoke test
verifies boot plus first screen, not an unlocked portfolio. Crashes: `adb logcat -d -s
AndroidRuntime:E`.

**Commits and releases.** Commit to `master` and push; there are no branches and no PR flow. Design
rationale lives in commit messages: there is no separate decision log, so write the why there.
Do NOT tag by hand: a `v*` tag is created by `make gh-release`, which is a real publication (it runs
`make test` and `make crossimpl`, builds the R8-minified release APK, **verifies its signature with
`apksigner`**, tags, pushes and creates the GitHub release with the APK attached). It refuses to
publish a debug-signed APK, since that key is public and cannot upgrade an installed app
(`DEBUG_APK=1` does it deliberately, named `-debug`). Two cheap probes first: `make check-signing`
(is a real key configured here? prints no secret) and `make gh-release-dry-run` (everything except
the tag, the push and the release). The release key and its passwords live only OUTSIDE this repo
and are never committed, printed or uploaded.

## 5. Map

Package root `fin.android` under `app/src/main/kotlin/fin/android/`. The `crypto` / `domain` /
`format` / `market` / `valuation` layers are **pure Kotlin, no Android imports**, which is what
makes them testable on the host JVM in a second.

| Area | Key files | Role | Pure? |
|---|---|---|---|
| `crypto/` | Argon2, Hkdf, AesGcm, Hashes, Bytes, Ids | KDF, AEAD, base64, Crockford ids | yes |
| `domain/` | Models, Money, MarketData | data model (BigDecimal money, enums, Book) | yes |
| `format/` | Header, Kdf, Wire, Log, Replay, Writer, Merge, Ledger | read/write the `.fin` (AAD-chained records, fold, diff-on-save, union + LWW merge) | yes |
| `market/` | Yahoo, Ft, Morningstar, Airfund, Nowcast, Session, Units, VenueDay, MultiSource, Converter, FxRates, CacheSidecar, Quotes, Source | fetch quotes (JSON everywhere), FX via USD, FINCACHE2 cache. See the sub-table below | yes |
| `valuation/` | Valuator, Perf, Gains | gross/tax/net, TWR/XIRR, period and per-asset gains, asset detail | yes |
| `storage/` | AtomicFile | the one way a file is replaced: tmp + fsync + rename (+ `.bak`) | yes |
| `net/` | Http, Tls (+ `FakeHttpServer` in tests) | the whole HTTP stack: `url`/`escape`, `send` (headers, JSON body, timeouts, ONE retry on 429/5xx/no answer), `Response`. Platform `HttpURLConnection` only; `Tls` narrows the offered cipher suites so a provider's anti-bot edge does not read the handshake as a robot's | yes |
| `remote/` | Backend, GitHubBackend, RemoteConfig, Sync | GitHub Contents API, pull/mutate/push, conflict -> merge, offline-dirty | Android-light |
| `data/` | AppContainer, AppRepository, AppState, SecretStore | manual DI, the single facade, Keystore-encrypted secrets | Android |
| `ui/` | AppRoot, AppViewModel, *Screen, Theme, Format, FinIcons | Compose screens, MVVM, theme, inlined icons | Android |

Inside `market/`, the pieces that are not obvious:

| File | What it is |
|---|---|
| `Airfund` | the official NAV feed of the employee-savings funds (FCPE) no quote site covers, with a bundled offline baseline per fund |
| `Nowcast` | the estimated tail such a fund needs between its last published NAV and today, read off a listed proxy and anchored on the print that NAV was struck on (`AirfundFund.navAnchor`: the proxy's close by default, its OPEN for a fund valued at its holding's opening price, as `ERES_DATADOG` is) |
| `Session` + `Quotes.refreshExtended` | the extended-hours opt-in (Settings -> Display, off by default), parity with the Go `value --extended`: a US pre/post-market print prices the line, the total and every figure shown beside them when it is fresher than the regular one, labelled `pre 08:14` / `post 19:59`, never stored, and EXPIRING (`Session.stillCurrent`, New York clock, weekends skipped) against `AppRepository`'s injectable clock |
| `Units` | the venue SUB-UNITS (`GBp`/`GBX` pence, `ZAc`, `ILA`, `USX`) a provider reports where a currency is expected; removed where the numbers enter the app, folded by `Units.same` in every currency comparison |
| `VenueDay` | which calendar day a bar or print belongs to, read in the venue's own zone (`exchangeTimezoneName`), forward only, a currency cross exempt |
| `FxRates` | display-only read-back of the rates a valuation crossed at (`AppState.Ready.fxRates`, a caption under the total) |

`valuation/` detail worth knowing: `priceOverrides` (an off-hours print) reaches `Valuator.value`,
`Gains.report` and `Gains.assetDetail` so that a valuation and every figure shown with it stand on
one price, and NEVER `Perf` or the period/history figures (Go decisions D36/D38: no override in
`perf` or `chart`).

**Data flow.** `MainActivity` -> `AppRoot` renders `AppViewModel.state: StateFlow<AppState>`
(Loading / Onboarding / Locked / Ready). `AppRepository` is the only mutator: it opens the ledger via
`Sync` (working copy in `filesDir/checkout`), values it (`Valuator` / `Gains` / `Perf`) and emits
`AppState.Ready(valuation, perf, gains, book, sync, message, refreshing, assetDetails)`. Per-asset
detail pages are precomputed into `Ready.assetDetails` so they open instantly.

**Source sets.** `src/main` is the app; `src/test` the host-JVM suite; `src/probe` holds the live
provider probe ONLY (`market/LiveProbe.kt`), compiled into both test source sets and shipped in no
APK; `src/androidTest` holds the repository's single instrumented test, which runs that probe on a
device (`make probe-device`). `src/probe` exists so the host and the device run the same body: the
two platforms do not share a TLS stack, so neither run can stand in for the other.

**Outside `app/`**: `scripts/doctor.sh` + `setup.sh` (the dev environment), `scripts/crossimpl.sh`
(the byte-compat gate), `gradle/libs.versions.toml` (every dependency at an exact version),
`docs/maintainability.md` (the dependency ledger and deprecation audit), `README.md` (human setup,
release, and the yearly upgrade checklist in §6 "WHEN ANDROID MOVES").

## 6. Where to change X

| You want to change | Go to |
|---|---|
| a record kind or format field | `format/Wire.kt` (DTO) + `format/Replay.kt` (fold) + `FORMAT.md` + a test; bump the version only per `FORMAT.md` §8 |
| a valuation / gain / perf number | `valuation/{Valuator,Perf,Gains}.kt`, mirroring the Go change and the parity test |
| a quote source or its parsing | `market/{Yahoo,Ft,Morningstar,Airfund}.kt`; fixtures in the market tests |
| what the live probe checks, on the host JVM AND on a device | `app/src/probe/kotlin/fin/android/market/LiveProbe.kt`, compiled into both test source sets |
| anything about the HTTP call itself (header, timeout, retry, escaping, a status to treat specially) | `net/Http.kt`, once, for every caller; fake it with `net/FakeHttpServer.kt` |
| the TLS handshake this app sends | `net/Tls.kt`, and read its doc first: it is a measurement, not a preference |
| support for another employee-savings fund (FCPE) | one entry in `market/Airfund.kt`'s `AirfundFunds.ALL` (share code + nowcast proxy, plus `navAnchor = NavAnchor.OPEN` when the fund's rules name its holding's OPENING price, mirroring the Go catalog's `nowcast_anchor`) plus its NAV baseline in `app/src/main/resources/fin/android/market/<TICKER>-NAV.csv`, copied from the Go reference's `refdata/`. The ledger asset carries only the ticker |
| sync behaviour (conflict, offline, pull cadence) | `remote/Sync.kt` |
| a screen or its styling | `ui/<Screen>.kt`; colours and typography in `ui/Theme.kt` (accent terracotta `#C2613C`, gain/loss via `gainLossColor`); number formatting in `ui/Format.kt` |
| app state or orchestration | `data/AppRepository.kt` (+ `AppState.kt`, `AppViewModel.kt`) |
| an icon | `ui/FinIcons.kt`: copy the `materialPath { ... }` body from the androidx material-icons sources (Apache 2.0, version recorded in the file), `autoMirror = true` for direction-carrying icons. Do NOT re-add `material-icons-extended`, which is frozen upstream |

## 7. Traps

Each of these cost real debugging once. Symptom, cause, what to do.

- **The app deadlocks on a mutation.** `AppRepository` serializes mutations with a `Mutex`
  (`exclusive { }`) and the Mutex is NOT reentrant. A locked public method that calls another public
  (locked) method hangs for ever. Call the `*Locked` private helpers instead; `refreshQuotesLocked`
  is the pattern.
- **An estimated price got cached, or shipped to another device.** Estimates must never persist. A
  fund published with a lag gets a nowcast tail (`market/Nowcast.kt`) flagged by
  `PriceSeries.estimatedFrom` / `estimateProxy`, recomputed at every refresh: `Quotes.refresh` strips
  the previous run's tail before merging, and `CacheSidecar.write` strips it again on the way to disk
  (which also keeps the FINCACHE2 JSON byte-compatible with Go, whose DTO has no such field).
  Anything DISPLAYING an estimate must say it is one, as `AssetDetailScreen` does. Proxies are
  fetched even when the user holds none of them, cached under `proxy:<SYMBOL>` in
  `MarketData.prices`; no ledger id can collide, those are Crockford base32.
- **A nowcast for a fund is off by about a day's move.** A NAV is struck on a particular print, not
  always on a close. A fund carrying `NavAnchor.OPEN` divides by the proxy's OPEN of the last NAV's
  day, which reaches the estimate as the session's open-to-close RATIO (`DailyData.openFactors`, read
  off the Yahoo chart payload's `open` column, held for one pass, stored nowhere). Every failure mode
  falls back on the close and none is an error: no `open` column, a day the proxy did not trade, a
  failed fetch. An ESTIMATED anchor day keeps the close it was built from, and a nowcast never
  overwrites a published NAV.
- **A permanent cliff appears in the chart, the TWR or the valuation.** A source restated its
  history (a share split, a currency redenomination, a class merge) and the incremental fetch
  (`Quotes.fetchFrom` resumes at the last cached close) glued the old scale in front of the new one.
  The overlap day is the canary: more than 2 % from the cached close and the series is dropped and
  refetched from the floor, with a warning (`Refresh.warnings` -> snackbar) asking for the ledger
  quantities to be checked, since a split moves the position too. When the measured factor matches a
  plain ratio (`Quotes.splitRatioFor`: 2:1, 3:1, 4:1, 3:2, their reverses) the warning NAMES the
  split and lists the quantity each pre-split trade owes: multiply the quantity, leave the amount
  alone. A factor matching no ratio claims nothing. Mirrors Go D40 and D47; estimates are stripped
  before the comparison so a nowcast tail never triggers it. The ledger has no way to restate a
  quantity and adding a transaction kind would make an older reader reject the file, so a native
  `split` record is a version-bump decision, not a bugfix.
- **A historical amount lands as 0 and the total looks wrong.** A currency reaches the book three
  ways: the account is denominated in one, the asset quotes in one, and a RECORD may be written in a
  fourth (a fee in JPY, a deposit in CHF). `Quotes` collects all three and the FX window reaches a
  week before the OLDEST record (`fxHistoryFloor`), because a historical deposit is crossed at the
  rate of its own day. When a rate is still missing, `Valuator` counts the amount as 0 (a phone
  screen has to render, where the Go CLI refuses the total) and NAMES the record in
  `Valuation.taxNote`: kind, amount, currency, date, asset, envelope, id. `Perf` stays silent on
  purpose, the note beside the curve already names it. Mirrors Go D43.
- **The Kotlin and Go totals differ in the last digits.** Someone summed `Double` in a map's own
  order. Floating-point addition is not associative, and the Go reference sums that exact list by
  sorted account id. Do the same. Mirrors Go D46.
- **A London holding is valued 100x too high, or refused entirely.** A currency code is never
  compared with `==` or `equalsIgnoreCase`. A venue quotes in a SUB-UNIT and the provider reports it
  where a currency is expected: Yahoo answers `GBp` for a London line and prices it in PENCE, FT
  spells it `GBX`, Johannesburg is `ZAc`, Tel Aviv `ILA`, some US futures `USX`. A case-INSENSITIVE
  compare books pence as pounds (a 100x error no plausibility check can see, since rescaling a series
  leaves every return untouched); a case-SENSITIVE one refuses the series and leaves the holding at
  its cost basis for ever. `market/Units.kt` removes the sub-unit where each provider's numbers enter
  the app, and `Units.same` answers every "is this the declared currency?" question. A RATIO
  (`DailyData.openFactors`) carries no currency and is never rescaled.
- **Sunday closes appear and Fridays go missing.** A daily bar carries an instant of the session, not
  a date. Truncated in UTC, a Sydney bar lands a day early (10:00 local = 23:00 UTC the day before in
  summer), and every date-matched join quietly misses: the previous close a day change reads, the FX
  rate of the day, the canary's overlap day. `market/VenueDay.kt` (Go's `sessionDay`) reads the day in
  the venue's own zone, FORWARD ONLY (a UTC reading is never late, only early); a currency cross is
  exempt, its Yahoo dating carries a separate known weekend anomaly a time zone would hide.
- **The app can never open its ledger again.** A file was replaced non-atomically. `File.writeBytes`
  truncates first and Android kills backgrounded processes whenever it wants memory, so the plain
  call leaves a short file often enough to matter, and a short `.fin` authenticates as nothing while
  the dirty guard forbids pulling over it: a permanent brick. Every persisted file goes through
  `storage/AtomicFile.kt` (tmp + fsync + rename + `.bak`, mirroring Go's `atomicWrite`): the working
  copy, the sync state, the FINCACHE2 sidecar. Two consequences in `remote/Sync.kt`: an unreadable
  state file reads as `dirty = true` (never as "nothing to push", which would let a pull overwrite
  unpushed records), and `openForRead` falls back to the `.bak`, promotes it and marks it dirty so
  the next sync MERGES it.
- **A fetch overwrote local records.** It must not: a `dirty` working copy holds records the remote
  has never seen. `Sync.mutate` merges the fetched remote INTO it rather than writing the remote
  bytes over it, and `pullIfStale` skips the pull entirely while dirty, since that path has no
  passphrase to merge with. Asserted by `remote/SyncTest`'s two "unpushed local change" tests.
- **Period gains read ~0 on a freshly synced device.** The market cache is per-device and NOT synced
  (it is regenerable). There are no prices until `refreshQuotes` runs. Statement-valued assets
  (property, cash) have no market performance at all, by design.
- **A property-heavy portfolio shows ~0 gains.** Gains are flow-neutralized market performance:
  revaluations and deposits are flows, not gains. This is correct. Do not "fix" it.
- **An unquoted security shows as worth 0.** It must not. The fallback chain (mirrors Go, asserted by
  `valuation/UnquotedTest`) is: market close -> last statement of the (account, asset) pair, a NAV
  observation scaled per share when the quantity changed since -> cost basis. The first statement of
  a position BOUGHT in the ledger (basis > 0) is a NAV observation (performance), not an adoption
  flow; only a declared holding (basis == 0) adopts.
- **A merge or save rewrote lines it should not have.** `Ledger.toBytes()` is diff-on-save: existing
  record lines are re-emitted verbatim, only new records are sealed and the trailer re-sealed.
  `merge` re-seals the whole chain, matching Go.
- **A record loses to the wrong one in a merge.** Timestamps must be formatted with `Locale.ROOT`
  (`format/Timestamps.kt`): the `ts` is the sealed LWW key.
- **A rejected GitHub token appears to have locked the user out.** It must not. `Sync` records it as
  `SyncState.authError` (a persistent "re-login" banner), local reads and writes keep working (writes
  stay `dirty`), and the next successful fetch or push clears it. Only an unlock with NO local copy
  surfaces the error.
- **Every provider answers 429 from a network that plainly works.** Not necessarily a throttle. An
  anti-bot edge can fingerprint the TLS ClientHello (JA3/JA4) and refuse a stack it does not
  recognise: on 2026-09-20 Yahoo answered 429 to the host JVM and 200 to the same code on an
  emulator, same IP, same minute, because Android's Conscrypt and the JDK's JSSE offer different
  cipher suites. `net/Tls.kt` removes the two obsolete families that gave the host JVM away, and
  its doc holds the measurement. Diagnosis order: `curl` (a LibreSSL build refused too is a second
  vote for the edge), then a Go program or `../finador` (accepted rules the IP out), then
  `make probe`'s `tls` block, then `make probe-device`. If the device is green there is no product
  bug, and the fix belongs to the probe. Do NOT add an HTTP or TLS library back (§2a).
- **`make build` warns "Unable to strip".** Benign, on Compose's `libandroidx.graphics.path.so`, the
  single native library.

## 8. Known deferred work (intentional, with rationale)

- **Holdings replay is implemented twice**: `valuation/Valuator.kt` (full fold) and
  `valuation/Perf.kt`'s `SeriesBuilder` (day-walk). Extracting the shared per-transaction transition
  would remove drift risk but touches parity-tested numbers: do it under the full suite. Until then
  `valuation/EndpointFuzzTest` is the net (random ledgers, several records on the same few days, the
  last point of the series must equal the valuation; Go D39 and D41 were both found by its Go twin).
- **`Gains.periodGain` rebuilds a full series per window** (8 windows). Building one series over the
  widest window and slicing, as Go's `report.go` does, is a pure speedup: verify TWR-per-window
  parity.
- **Dependency verification** (`gradle/verification-metadata.xml`) is deliberately NOT adopted;
  the reasoning, with the measured size of the file it would add, is `docs/maintainability.md` §13.
  What IS pinned: the Gradle distribution's SHA-256 and every dependency version exactly.
- **`androidx.biometric` cannot be dropped** without raising `minSdk` to 30, a product decision
  (`docs/maintainability.md` §4). Same for the `@ExperimentalMaterial3Api` opt-ins (§12) and
  navigation-compose (§3): all three examined, all three kept, with reasons.
- The *data* lives in a separate private repository; this code repository is public.

## 9. Definition of done

Tick every line before saying the work is done.

- [ ] `make test` green: `summary: N tests, 0 failures, 0 errors`.
- [ ] `make build` green (any change that touches `main`, not only tests).
- [ ] `make lint` prints `No issues found`, and the compile printed no `w:` deprecation warning.
- [ ] `make crossimpl` green if anything under `crypto/` or `format/` moved.
- [ ] No new dependency; or one, with its `docs/maintainability.md` entry written first.
- [ ] Docs updated in the same commit: this file if an invariant or the architecture moved,
      `README.md` if a command or the environment moved, `docs/maintainability.md` if a dependency,
      an opt-in or a deprecation moved.
- [ ] No secret, no real personal data, no real account or amount added anywhere, including in the
      commit message.
- [ ] No typographic dash anywhere in the diff.
- [ ] Committed to `master` with a message that carries the WHY, and pushed.
- [ ] `make probe-device` green if a release is in view, or if anything under `net/` or `market/`
      moved. `make probe` is fast feedback, not the gate: the host JVM and a phone do not share a
      TLS stack (`docs/maintainability.md` §14).
- [ ] No tag created by hand. If a release is wanted, say so and let a human run `make gh-release`
      (it needs the signing key, which is not in this repo).

## 10. Pointers

- Human setup, running on a phone or emulator, release, and the yearly **WHEN ANDROID MOVES**
  checklist: `README.md`.
- Dependency ledger, deprecation audit, opt-in inventory, what upkeep really costs:
  `docs/maintainability.md`.
- Format spec, authoritative: `../finador/docs/FORMAT.md`.
- Go reference implementation: `../finador/internal/{store,domain,portfolio,perf,market}`.
- Design rationale: commit messages. There is no separate decision log here.
