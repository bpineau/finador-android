# Decisions - finador-android autonomous run

Log of decisions taken autonomously (the user asked for an A→Z build without
questions or confirmations). Each non-trivial decision is tracked here.

## 2026-06-14 - framing

- **Stack**: Kotlin + Jetpack Compose (Material 3), no KMP (pure Android, iOS not requested).
- **Target**: compileSdk/targetSdk **36**, build-tools **36.1.0**, minSdk **26** - to match
  the env installed by the user (android-commandlinetools, `platforms;android-36`).
- **SDK**: `ANDROID_HOME=$(brew --prefix)/share/android-commandlinetools`; the project writes
  `local.properties` and the build commands export `ANDROID_HOME`/`JAVA_HOME` explicitly.
- **DI**: manual (`AppContainer`), no Hilt (minimalism, no annotation-processing).
- **Crypto**: JDK everywhere except **Argon2id** → lib `com.lambdapioneer.argon2kt:argon2kt`.
  HKDF-SHA256 hand-rolled (RFC 5869) to avoid a dependency. Validated against
  `FORMAT.md §9.1`.
- **Network**: OkHttp + kotlinx.serialization; **Jsoup** for FT/Morningstar (Yahoo = JSON).
- **Charts**: hand-rolled Compose Canvas (no heavy lib).
- **Secrets/auth** (explicit requirement): GitHub PAT **pasted then stored** in the Keystore;
  passphrase entered **once** then **unlock by biometrics alone** (BiometricPrompt) -
  integrated from **phase 3** (foundation), not deferred.
- **Perf** (TWR/XIRR/Sharpe…): **good-to-have**, phase 6; shippable v1 = phases 1–5.
- **Storage**: **GitHub only** in v1 (no local file / SAF).
- **Correctness prerequisite**: `FORMAT.md` ↔ Go code audit run before writing the format layer.

## Progress (autonomous run)

- **FORMAT.md ↔ Go code audit**: VERDICT "up to date" (12/12 zones, KDF vectors recomputed exact,
  sample.ledger decrypted). Only point raised: the §9 vectors are not asserted by any Go test.
  → *To do (optional, defense-in-depth on the finador side)*: add a Go golden test. Deferred to the end
  of the run; on the Android side the vectors are already asserted + the cross-impl gate protects the contract.
- **Phase 0 (scaffold)**: OK. Gradle 8.13 wrapper, AGP 8.13.2, Kotlin 2.2.20, Compose BOM 2025.12.01.
  `assembleDebug` green. `argon2kt` → **Bouncy Castle** (pure-JVM, testable on host).
- **Phase 1 (crypto + read)**: OK. 9 tests, KDF vector + sample.ledger exact.
- **Phase 2 (writer + merge)**: OK. 20 tests. **Bidirectional cross-impl gate green**
  (`scripts/crossimpl.sh`): Go reads a .fin mutated by Android (tx visible); Android reads a .fin created
  by Go. Sync backend made **blocking** (no coroutines in the lib; the UI calls on IO).

- **Phase 3 (remote + secrets)**: OK. 36 tests. GitHub Contents API, Sync (pull/mutate/push,
  offline-dirty, conflict→merge), SecretStore (EncryptedSharedPreferences). Blocking backend.
- **Phase 4 (market)**: OK (subagent). 70 tests. Yahoo/FT/Morningstar (JSON + regex, no Jsoup),
  MultiSource, USD Converter, FINCACHE2 cache. Dates in UTC on mobile (documented).
- **Phase 5 (valuation + UI)**: OK (subagents). 82 tests + running app. Valuation = finador
  parity (value_test.go ported). Compose UI: onboarding (paste PAT + passphrase), biometric unlock,
  overview (gross/tax/net + lines + positions), tx entry, settings. **Boots on the AVD `test`,
  lands on the onboarding without crash** (screenshot validated).
  Note: material-icons absent from the classpath → actions in text (cosmetic, to enrich).
- **Phase 6 (perf, good-to-have)**: OK (subagent). 105 tests. TWR/XIRR/CAGR/vol/Sharpe/Sortino/maxDD
  (perf_test/series_test parity), "Performance" card in the overview.

## Remaining optional (non-blocking)
- Go golden test on the finador side (assertion of the §9 vectors + opening of sample.ledger) - defense-in-depth;
  on the Android side the vectors are already asserted + the cross-impl gate protects the contract.
- Visual enrichments: icons (material-icons-extended), curves (Canvas), pull-to-refresh.
- Real GitHub E2E: requires a private repo + the user's PAT (to do on first use).
