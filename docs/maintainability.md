# Maintainability inventory

This app is maintained by Go developers, not Android specialists. The standing priorities, in
order, are: it must keep working on future Android versions and future phones; it must need as
little upkeep as possible; it must depend on as few third-party libraries as possible, because
every one of them is a future breakage; it must use no deprecated API and nothing on a path to
deprecation; and a fresh laptop must reach a working build with one obvious command.

This file is the honest ledger behind those priorities: what the app depends on, who keeps it
alive, what would break if it died, and what was done about it. Re-read it once a year, when the
new Android version ships (see "The yearly wave" in `README.md`).

Reviewed 2026-09-20 against the state of the repository on that date.

---

## 1. The scorecard

| Dependency | Kind | Used for | Verdict |
|---|---|---|---|
| Android Gradle Plugin | Google, first-party | the build itself | KEEP |
| Gradle (wrapper) | Gradle Inc., de facto standard | the build itself | KEEP |
| Kotlin + Compose compiler plugin | JetBrains/Google, first-party | the language, `@Composable` | KEEP |
| kotlinx-serialization | JetBrains, first-party | all JSON: the `.fin` wire records, the market payloads, the GitHub API | KEEP |
| kotlinx-coroutines-android | JetBrains, first-party | the one background dispatcher | KEEP |
| Compose BOM / ui / material3 | Google AndroidX | every screen | KEEP |
| activity-compose | Google AndroidX | `setContent` | KEEP |
| lifecycle-viewmodel-compose, lifecycle-runtime-compose | Google AndroidX | `ViewModel`, `collectAsStateWithLifecycle` | KEEP |
| navigation-compose | Google AndroidX | the 12-destination back stack, system back, tab state | KEEP (see §3) |
| androidx.core | Google AndroidX | `ContextCompat`, `WindowCompat`, `SharedPreferences.edit` | KEEP, now declared |
| androidx.biometric | Google AndroidX, frozen since 2021 | the unlock prompt | WATCH (see §4) |
| Bouncy Castle (`bcprov-jdk18on`) | third-party, Legion of the Bouncy Castle | Argon2id **only** | KEEP (see §5) |
| ~~okhttp~~ | third-party, Square | was every HTTP call | **REPLACED BY PLATFORM** (see §6) |
| ~~okhttp mockwebserver~~ | third-party, Square | was the fake server in 7 test classes | **REPLACED BY PLATFORM** (see §6) |
| JUnit 4 | third-party, de facto standard | the test runner | KEEP (see §7) |

Third-party code shipped inside the APK after this pass: **Bouncy Castle, and nothing else.**
Everything else is Google (AndroidX), JetBrains (Kotlin) or the Android platform itself.

---

## 2. First-party, irreplaceable: the short entries

**Android Gradle Plugin** and **Gradle**. The build system. There is no alternative that builds an
APK. Both are on a fixed cadence (AGP follows Android Studio, roughly four minor versions a year);
the wrapper pins Gradle and `libs.versions.toml` pins AGP, so nothing moves without a commit.
AGP's compatibility table (`https://developer.android.com/build/releases/gradle-plugin`) is the one
page to read before bumping either.

**Kotlin**, the **Compose compiler plugin** and the **serialization plugin** all come from the same
`kotlin` version in `libs.versions.toml`: bump the three together or the build fails with a plugin
mismatch. Since Kotlin 2.0 the Compose compiler ships with Kotlin itself, so there is no separate
`composeCompiler` version to keep in step any more.

**kotlinx-serialization-json** is JetBrains first-party and it does something the platform cannot:
`@Serializable` data classes with compile-time-generated parsers, no reflection, no proguard rules.
The platform alternative is `org.json` (untyped, lenient, `JSONObject.getDouble` throws) which
would mean hand-writing a parser for every one of the fifteen files that use it
(`format/{Wire,Replay,Header,Merge,Ledger}.kt`, `market/{Yahoo,Ft,Morningstar,Airfund,CacheSidecar}.kt`,
`remote/{RemoteConfig,Sync,GitHubBackend}.kt`, plus two tests). More code, more bugs, on the exact
path where a parsing mistake corrupts a ledger. KEEP.

**kotlinx-coroutines-android** supplies `Dispatchers.Main` on Android. Used in exactly two files
(`data/AppRepository.kt`, `ui/AppViewModel.kt`); the rest of the coroutine machinery arrives
transitively with Compose and lifecycle, which are themselves built on coroutines. Removing the
explicit dependency would not remove coroutines from the APK. KEEP.

**Compose, activity-compose, lifecycle-\***. The UI toolkit and its plumbing, all AndroidX, all on
Google's own release train, all 17 files under `ui/`. The alternative is the View system, which is
what Compose replaced. KEEP.

**androidx.core** was being used without being declared (three call sites:
`ui/Theme.kt` `WindowCompat`, `ui/UnlockScreen.kt` `ContextCompat.getMainExecutor`,
`data/SecretStore.kt` `SharedPreferences.edit`), arriving only because Compose happens to depend
on it. It is now declared explicitly, so an upstream change cannot silently take it away. Its two
compat shims exist because `minSdk` is 26: `Context.getMainExecutor()` is API 28 and
`Window.getInsetsController()` is API 30.

---

## 3. navigation-compose: examined, kept

The brief asked whether a sealed-class state in a ViewModel could replace it for "an app with a
handful of screens". Counted honestly, `ui/AppRoot.kt` declares **twelve** destinations, three of
which take an argument, and the `NavHost` supplies four things a hand-rolled stack would have to
re-implement:

1. the hardware/gesture **back button** wired to the stack (and, on API 33+, predictive back, which
   the manifest opts into with `android:enableOnBackInvokedCallback="true"`);
2. `popUpTo(startDestination) { saveState = true }` + `restoreState = true`, which is what makes
   the three bottom-bar tabs keep their scroll position without growing the back stack;
3. per-destination `SavedStateHandle`/`rememberSaveable` scoping, so a screen survives a process
   death and a rotation;
4. the four directional transitions.

A replacement would be perhaps 150 lines of subtle state machine on the path where a mistake means
"the back button leaves the app instead of going back". navigation-compose is first-party AndroidX,
has kept its `NavHost`/`composable` API source-compatible since 2021, and its route-string API is
still current (the newer type-safe-route API is additive, not a replacement). **KEEP.**

Risk to watch: AndroidX is pushing Navigation 3, a separate artifact with a different API. That is
an opt-in rewrite, not a migration: navigation-compose 2.x is not deprecated and is what ships with
the Compose BOM's own samples. Nothing to do until Google says otherwise.

---

## 4. androidx.biometric: WATCH

`androidx.biometric:biometric` has been at **1.1.0 since February 2021**. It is not deprecated, it
still works, and it is first-party AndroidX, but five years without a release is the profile of a
library that will one day be superseded rather than updated. It is used in one file
(`ui/UnlockScreen.kt`: `BiometricManager.canAuthenticate`, `BiometricPrompt`, `PromptInfo`) and it
is the reason `ui/MainActivity.kt` extends `FragmentActivity` instead of `ComponentActivity`,
which is why `androidx.fragment` is on the classpath at all.

The platform equivalent is `android.hardware.biometrics.BiometricPrompt`, which exists from
**API 28** and only gained `setAllowedAuthenticators` (what the app uses to accept a device PIN as
well as a fingerprint) in **API 30**. `minSdk` here is 26, so the platform class cannot be used
without dropping Android 8.0 and 8.1 devices.

The trade, written down so the next reader does not have to rediscover it:

- raise `minSdk` to 30 (Android 11, September 2020) and you can delete `androidx.biometric`,
  delete `androidx.fragment`, and make `MainActivity` a `ComponentActivity`;
- the cost is that phones older than Android 11 can no longer install the app.

That is a product decision, not a maintenance one, so it is not taken here. Revisit it the next
time `minSdk` is raised for any other reason.

---

## 5. Bouncy Castle: KEEP, for one algorithm

`crypto/Argon2.kt` is the **only** file that imports Bouncy Castle, and it imports exactly two
classes: `Argon2BytesGenerator` and `Argon2Parameters`. Everything else cryptographic already runs
on the platform:

- AES-256-GCM (`crypto/AesGcm.kt`) is `javax.crypto.Cipher("AES/GCM/NoPadding")`, i.e. Conscrypt,
  i.e. BoringSSL. No third-party AEAD anywhere.
- HKDF-SHA256 (`crypto/Hkdf.kt`) is hand-written over `javax.crypto.Mac`, 30 lines, RFC 5869.
- SHA-256 (`crypto/Hashes.kt`) is `java.security.MessageDigest`.

**Argon2 has no platform implementation at any API level.** `javax.crypto.SecretKeyFactory` offers
PBKDF2 and nothing memory-hard; Conscrypt does not expose Argon2; there is no AndroidX wrapper.
And Argon2id is not negotiable here: the `.fin` format specifies it (`../finador/docs/FORMAT.md`),
the Go reference derives keys with `golang.org/x/crypto/argon2.IDKey`, and `scripts/crossimpl.sh`
proves the two agree byte for byte. Changing the KDF would change the file format.

Can the footprint be narrowed? The published `bcprov-jdk18on` jar is 7.0 MB and there is no smaller
official artifact carrying Argon2 alone (it lives in the lightweight-crypto core of `bcprov`; the
`bcutil`/`bcpkix` artifacts are additions, not subsets). The footprint is narrowed by R8 instead:
the release build is minified, only the reachable class graph survives, and the whole release APK
is about 6 MB. The two options that would remove the dependency outright are both refused:

- hand-write Argon2id (Blake2b + the fill/mix core, ~300 lines): a from-scratch crypto primitive
  guarding the only copy of somebody's finances is a worse risk than a dependency;
- JNI-backed Argon2 (`argon2kt` and friends): adds a native library, adds a 16 KB-page-size and an
  ABI-per-architecture problem, and breaks the host JVM unit tests, which today run the *real* KDF.

**KEEP**, with the scope written into `crypto/Argon2.kt`'s doc comment. Bouncy Castle is pure Java
(no `.so`), has shipped continuously since 2000, and releases on a predictable quarterly cadence.

---

## 6. okhttp: REPLACED BY THE PLATFORM

**What it was used for.** Five files in `main` (`market/Http.kt`, `market/Yahoo.kt`,
`market/Ft.kt`, `market/Morningstar.kt`, `market/Airfund.kt`, `remote/GitHubBackend.kt`) and seven
test classes through `mockwebserver`.

**What of okhttp was actually used**, counted rather than assumed:

| Feature | Used? | Platform equivalent |
|---|---|---|
| call/connect/read timeouts | yes, 15 s each | `HttpURLConnection.setConnectTimeout` / `setReadTimeout` |
| query-string building with escaping | yes, 5 call sites | ~15 lines of percent-encoding (`market/Http.kt`) |
| request headers | yes (User-Agent, Cookie, Authorization, Accept) | `setRequestProperty` |
| reading multi-valued response headers | yes, one place (`Set-Cookie`, Yahoo) | `headerFields` |
| POST/PUT with a JSON body | yes (FT, Airfund, GitHub) | `doOutput` + `outputStream` |
| status code + body as a String | yes, everywhere | `responseCode`, `inputStream`/`errorStream` |
| transparent gzip | relied on implicitly | identical: `HttpURLConnection` sends `Accept-Encoding: gzip` and decodes the response itself, as long as the caller does not set that header by hand |
| redirect following | never exercised (all endpoints answer 200 directly) | on by default, same-protocol only; every base URL here is `https` |
| connection pooling / keep-alive | implicitly | on by default |
| HTTP/2, interceptors, caching, WebSocket, cookie jar, TLS pinning, async calls | **no** | n/a |

Nothing in the "no" row was in use. The entire library was paying for a GET and a POST.

**Why it went.** It is the only third-party library that was ever in the request path, it was the
dependency that froze the SDK wave (its 5.5.0 release refuses to build below `compileSdk` 37), and
the replacement is one 120-line file with no API of its own to learn.
`java.net.HttpURLConnection`/`HttpsURLConnection` has been in the platform since API 1, is what
Android's own components use, and has no release cadence to track. Note that
`java.net.http.HttpClient` (the JDK 11 one) is **not** part of the Android API and is not an
option.

**How behaviour was proved unchanged.**

1. The seven test classes kept their assertions verbatim. Only the fake server changed: a
   `FakeHttpServer` test helper over `com.sun.net.httpserver.HttpServer` (in the JDK, so no
   dependency) exposing the same `start`/`shutdown`/`url`/`enqueue`/`takeRequest`/`dispatcher`
   shape MockWebServer had. Same requests asserted, same paths, same bodies, same headers, same
   retry-on-429 and 401-crumb-renewal scenarios.
2. A live probe (`app/src/test/kotlin/fin/android/market/LiveProviderProbe.kt`, new, opt-in,
   run with `make probe`, never part of `make test`) fetched real series from the providers on
   the old stack and then on the new one. Result, stated exactly:
   - **FT** (`LU0171310443`): identical, 42 closes 2026-07-22..2026-09-17, last 127.17 EUR.
   - **Airfund** (both FCPE share classes): identical, 631 and 311 NAVs, same last values
     (70.68 and 206.8 on 2026-09-16). This is the POST-with-a-JSON-body path.
   - **Morningstar**: failed on BOTH runs, and `curl` cannot reach `tools.morningstar.fr` from
     this machine either. A provider outage, not a regression; it is the last link of the
     fallback chain and the app degrades to the others.
   - **Yahoo**: verified GREEN on the old stack (daily, FX, and the cookie+crumb quote call, all
     plausible). It could NOT be re-verified on the new stack within the session: repeating the
     probe tripped Yahoo's per-IP rate limit and every request - including plain `curl` with the
     same User-Agent - answered `429 Too Many Requests` for the rest of the session. **Re-run
     `make probe` from a fresh IP before the next release and check the four Yahoo lines.**
     The hermetic `YahooTest`/`QuotesTest` do cover the same paths against the fake server,
     including the 429 retry and the 401-crumb-renewal.

**What a reader should still know.** Two differences exist and are handled in `market/Http.kt`:
a `HttpURLConnection` throws `IOException` on a 4xx/5xx instead of returning the body, so the code
reads `errorStream` in that case; and a connection object is single-use, so the retry loop builds a
fresh one per attempt (okhttp reused the client). Both are covered by tests.

---

## 7. Test-only dependencies

**JUnit 4** (`junit:junit:4.13.2`) is the runner the Android Gradle Plugin wires up by default and
what `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` expects. It has been
API-frozen for a decade, which for a test runner is the desired property rather than a warning
sign. JUnit 5 on Android needs a third-party Gradle plugin, which is the opposite of what this
project wants. **KEEP.**

There are no other test dependencies: no mocking framework, no assertion library, no Robolectric.
The engine layers (`crypto/ domain/ format/ market/ valuation/`) are pure Kotlin with no Android
imports, so they run on the host JVM with plain `assertEquals`. That is the single biggest reason
this project is cheap to keep alive, and it is worth defending: **do not let an Android type leak
into those packages.**

---

## 8. The deprecation and obsolescence ledger

Method: a clean recompile with warnings visible
(`./gradlew assembleDebug lintDebug --warning-mode all --rerun-tasks`), the Lint report
(`app/build/reports/lint-results-debug.txt`), a grep for `@Suppress`, and a read of the Android 17
(API 37) behaviour-change pages.

### Compiler

**Zero** `w:` deprecation warnings in `main` and in `test`, before and after this pass. The one
`@Suppress` in the codebase is `@Suppress("UNCHECKED_CAST")` in `ui/AppViewModel.kt`'s
`ViewModelProvider.Factory`, which is the cast every such factory has to make; it is not a
deprecation. There is no `@Suppress("DEPRECATION")` anywhere.

### Lint

Deliberate suppressions live in `app/lint.xml`, two of them, each with its reason written in place
(`ObsoleteSdkInt` on `mipmap-anydpi-v26`, because merging the folder breaks AAPT's manifest
resolution; `UnusedAttribute` on `enableOnBackInvokedCallback`, which is required from API 33 and
harmlessly ignored below). Read them before "fixing" what they cover.

Findings acted on in this pass:

- `AutoboxingStateCreation` (`ui/AssetDetailScreen.kt`): `mutableStateOf(Int)` boxes on every
  write. Fixed with `mutableIntStateOf`.
- `android:statusBarColor` / `android:navigationBarColor` in `res/values/themes.xml`: **deprecated
  in API 35 and no-ops for any app targeting 35 or above**, which this one does. Removed; the bars
  were already transparent by edge-to-edge enforcement, and `ui/Theme.kt` keeps setting the icon
  appearance through `WindowCompat`, which is not deprecated.

After the pass, `lintDebug` reports nothing but the version-bump notices that reappear whenever an
upstream release lands. **Any other Lint output is a regression.**

### Platform API surface: what could bite, and whether it does

Gone through one by one because "it builds today" is not the question:

| Android policy / behaviour change | Does it touch this app? |
|---|---|
| Scoped storage / `READ_EXTERNAL_STORAGE` | No. Everything lives in `filesDir`; no storage permission is declared, no `MANAGE_EXTERNAL_STORAGE`, no SAF. |
| Background work (`WorkManager`, `JobScheduler`, background start restrictions) | No. The app has no service, no receiver, no scheduled work, no background start. Refreshes happen while the user is looking at the screen. |
| `POST_NOTIFICATIONS` (API 33+) | No. The app posts no notification. |
| Edge-to-edge enforcement (API 35+, no opt-out from API 36+) | Already handled: the layout uses `navigationBarsPadding()` and `Scaffold` insets, and there is no `windowOptOutEdgeToEdgeEnforcement`. |
| Predictive back (default from API 36 for targeting apps) | Already opted in: `android:enableOnBackInvokedCallback="true"`, and navigation-compose implements the callback. |
| 16 KB page size (required for API 35+ uploads) | The only native library in the APK is Compose's `libandroidx.graphics.path.so`, shipped by AndroidX and 16 KB-aligned. Verified with `zipalign -c -P 16 -v 4 <apk>`: all four ABIs OK. No dependency here ships its own `.so`; Bouncy Castle is pure Java. |
| Foreground service types (API 34+) | No. No foreground service. |
| Exact alarms (`SCHEDULE_EXACT_ALARM`, API 31+/33+) | No alarms at all. |
| Package visibility (`<queries>`, API 30+) | No. The app queries no other package and launches no external intent. |
| Cleartext traffic | Already safe: no `usesCleartextTraffic`, and every base URL is `https`. Android 17 flags `usesCleartextTraffic` for future deprecation; nothing to migrate. |
| Backup / `dataExtractionRules` | `allowBackup="false"` plus explicit exclude-everything rules in both `res/xml/backup_rules.xml` (pre-12) and `res/xml/data_extraction_rules.xml` (12+). Correct for an app holding Keystore-encrypted secrets that could not be restored elsewhere anyway. |
| `android:exported` (required since API 31) | Declared on the single activity. |
| Per-app Keystore key limit (50 000 keys, API 37 targeting) | The app creates exactly one Keystore key (`data/SecretStore.kt`). |

### Android 17 (API 37) behaviour changes, one by one

**All apps, whatever they target.** App memory limits (this app holds a ledger and a price cache in
memory, kilobytes, nowhere near a RAM cap); SMS OTP protection (no SMS); implicit URI grants (no
`ACTION_SEND`); per-app Keystore limits (one key); cross-profile loopback block (no loopback); IME
visibility after rotation (no IME state assumption); touchpad pointer capture (none); background
audio (none); Bluetooth re-pairing (none). **Nothing to change.**

**Apps targeting 37.** RemoteViews memory limit (no widget); lock-free `MessageQueue` (no
reflection on it); `static final` fields unmodifiable by reflection (no reflection at all); CJKV
IME composition (no custom IME, no custom edit field); **ECH on by default** and **Certificate
Transparency on by default** (both touch the four HTTPS providers: all of them are large public
endpoints served behind CT-logged certificates, and the live probe in §6 confirms the calls still
succeed under `targetSdk` 37 — this is the one change worth re-checking if a provider ever starts
failing only on new phones); `ACCESS_LOCAL_NETWORK` (no LAN access); password fields with physical
keyboards (none); standard-SMS OTP delay (no SMS); background activity launch hardening (the app
launches no activity from the background); safer native DCL (no `System.load`); Contacts Provider
restrictions (no contacts); Content Capture deprecation (not used); background audio (none);
**orientation and resizability constraints ignored on large screens** (the manifest declares no
`screenOrientation` and no `resizeableActivity`, so there is nothing to be ignored — the app was
already free-form); `BluetoothSocket` read (no Bluetooth).

**Net: `targetSdk` 37 required no code change in this app.** That is the dividend of having no
services, no receivers, no permissions beyond `INTERNET`, and no reflection.

---

## 9. What is honestly left to maintain, every year

Nothing in this repository makes the yearly Android release free. What it does is make it small and
mechanical. Expect, once a year, roughly half a day:

1. **The SDK wave**: Gradle wrapper, AGP, Kotlin, `compileSdk`, `targetSdk`, then the AndroidX
   libraries that gate on `compileSdk`. The order matters and is written down in `README.md`
   ("When Android moves").
2. **Reading two pages**: the "all apps" and "apps targeting N" behaviour-change lists for the new
   API level. Section 8 above is the template for that pass; copy its table and answer each row.
3. **A smoke test** on an emulator image of the new API level, because compiling against a new
   platform proves nothing about running on it.
4. **Play policy**, if the app is ever distributed there: Google requires `targetSdk` to be within
   one year of the newest API level for new uploads. Distributed as a GitHub-release APK, as it is
   today, that deadline does not apply, but the behaviour changes still do the moment a user
   installs a new phone.

And, less often:

- **Bouncy Castle** security releases (quarterly cadence; a KDF bump would be format-visible, so
  read the release notes rather than bumping blind).
- **androidx.biometric**, the one library that could be retired rather than updated (§4).
- **The Yahoo/FT/Morningstar/Airfund endpoints**, which are unofficial and change without notice.
  That is a data risk, not an Android risk, and the live probe of §6 is how it is checked.

## 10. Where this project cannot reach the Go ideal

Said plainly, because the gap is real:

- **There is no `go build`.** Building an APK needs a JDK, a Gradle distribution, the Android SDK
  platform and build-tools, and accepted SDK licences. `make setup` reduces that to one command on
  a fresh macOS laptop with Homebrew, and `make doctor` tells you which piece is missing, but the
  pieces are still there.
- **Android has no Go 1 compatibility promise.** Every API level can change behaviour for apps that
  target it, and Google does deprecate and remove. The defence used here is to depend on as little
  of the platform as possible: one permission, one activity, no services, no background work.
- **The UI toolkit moves.** Compose is stable but its ecosystem (navigation, material3) ships
  breaking-ish minor releases. The defence is the same as for the rest: pin every version in
  `gradle/libs.versions.toml`, never use a dynamic version, and move deliberately once a year.
- **Unofficial market endpoints will break.** Nothing about Android can prevent that. They are
  isolated behind `market/Source.kt` so a failing provider degrades instead of crashing.

---

## 11. The churn record: which libraries actually cost work

The question behind this section is "which of these are habitual breakers?". It is answered with
facts, not impressions: upstream release notes, and this repository's own git history (102
commits, `git log -p -- gradle/libs.versions.toml app/build.gradle.kts`).

| Dependency | Bumps in this repo | Bumps that forced a code change | Upstream break record |
|---|---|---|---|
| **okhttp** | 2 (4.12.0 -> 5.4.0, then 5.4.0 -> 5.5.0) | **2 of 2** | 4.0.0 (2019-06) = whole-library Kotlin rewrite with an official upgrade guide. 5.0.0 (2025-07) = artifacts split into JVM and Android variants, AND MockWebServer moved to a new coordinate *and* package (`mockwebserver3`), the old one labelled **"Obsolete"** in Square's own table - which is the artifact this repo was using. Every release pins a new Okio and a new kotlin-stdlib. 5.3.0's notes record a ZSTD-KMP fix "that caused APKs to fail 16 KB ELF alignment checks". 5.5.0 requires compileSdk 37 (its ECH support is Android-17-only). Project moved to the Commonhaus Foundation and changed its signing key in 5.5.0. |
| **AGP** | 4 (8.13.2 -> 9.3.0 -> 9.3.3 -> 9.4.1) | 1 of 4 | AGP 9 ships built-in Kotlin support and *rejects* the `org.jetbrains.kotlin.android` plugin: both build files had to drop it. AGP 10 will make the new Variant API mandatory. Majors break by design; this is the price of the platform. |
| **Kotlin** | 3 (2.2.20 -> 2.4.10 -> 2.4.20) | 1 of 3 | 2.4's sharper nullability analysis turned redundant `!!` into warnings in three files. Warnings, not errors, and the project treats warnings as bugs. |
| **Compose BOM** | 2 | 0 | No source change in this repo. But 2026.09.00 refused to build below compileSdk 37, and the BOM has dropped an artifact before (see material-icons-extended below). Its APIs are partly `@ExperimentalMaterial3Api` (§12). |
| **navigation-compose** | 1 (2.9.8 -> 2.10.1) | 0 | Blocked once by the compileSdk-37 gate. API stable since 2021. Navigation 3 exists as a separate, opt-in artifact. |
| **lifecycle** | 2 (2.9.4 -> 2.10.0 -> 2.11.0) | 0 | 2.11.0 hard-requires compileSdk 37; that is the whole of its record here. |
| **kotlinx-serialization** | 1 (1.9.0 -> 1.11.0) | 0 | Nothing. |
| **kotlinx-coroutines** | 1 (1.10.2 -> 1.11.0) | 0 | Nothing. |
| **Bouncy Castle** | 2 (1.84 -> 1.85 -> 1.86) | **0 of 2** | Nothing, twice, with `make crossimpl` green against the Go reference each time. Quarterly cadence, pure Java, no `.so`. The best-behaved dependency in the project. |
| **androidx.biometric** | 0 | 0 | Has not moved since the app was written, or since 2021. Frozen, not broken (§4). |
| **JUnit 4** | 0 | 0 | API-frozen for a decade. For a test runner that is the desired property. |
| **activity-compose** | 1 | 0 | Nothing. |

**Two libraries in this repo have already died**, and both were Google's own, which is the reason
"first-party" is a KEEP argument but not a guarantee:

- `androidx.security:security-crypto` (`EncryptedSharedPreferences`) was **deprecated by Google**.
  Commit `dd1281d` replaced it with ~100 lines of hand-written Android Keystore AES-GCM in
  `data/SecretStore.kt`; `1e07ec0` deleted the migration shim and the dependency.
- `androidx.compose.material:material-icons-extended` was **frozen upstream at 1.7.8 and dropped
  out of the Compose BOM**. Commit `1a0a791` inlined the eleven icons the app actually draws into
  `ui/FinIcons.kt` and deleted the library.

So the owner's impression is correct and now documented: **okhttp is the one dependency this
project has a bad record with** (two bumps, two incidents, and the artifact it used is marked
obsolete by its own maintainers), which is why §6 removed it rather than bumping it again.
Bouncy Castle, by the same measure, is the one that has never cost anything.

---

## 12. Experimental and opt-in APIs

An `@OptIn` on an experimental API is a promise the library has NOT made. It is worth listing
because such an API can change or vanish in a *minor* release.

Inventory (`grep -rn "@OptIn\|Experimental" app/src`), after this pass:

- **10 `@OptIn(ExperimentalMaterial3Api::class)`** sites, in 9 files under `ui/`. They cover
  exactly two APIs:
  - `TopAppBar` (`ui/OverviewScreen.kt`'s `FinTopBar` plus the seven screens that place their own
    top bar);
  - `ExposedDropdownMenuBox` / `ExposedDropdownMenuDefaults` / `ExposedDropdownMenuAnchorType`
    (`ui/DropdownField.kt`).
- **Nothing else.** No experimental coroutines API, no experimental serialization API, no
  `@RequiresOptIn` of our own, and no compiler-wide `optIn(...)` in the build file - each opt-in is
  annotated at the function that needs it, which is what keeps this list short and auditable.

**Is there a stable replacement?** No, and this was tested rather than assumed: removing every
`@OptIn` and recompiling against Compose BOM 2026.09.00 (material3 1.4.0) produced 13 errors, all
of the form "This material API is experimental". Material3 has kept `TopAppBar` and the exposed
dropdown experimental since 1.0. The only alternatives are to hand-build both out of stable
primitives (`Surface` + `Row` + `Text` + `IconButton`; `OutlinedTextField` + a plain
`DropdownMenu`) - which the project has already done once, for a different reason: `AppRoot.kt`'s
`CompactBottomBar` is hand-built because Material3's `NavigationBar` clips icons below 80dp.

**Two of the twelve opt-ins were redundant** (`PortfolioScreen` and `GainsScreen` only *call*
`FinTopBar`, which carries its own opt-in) and were removed in this pass.

**Why the remaining ten are tolerated.** An experimental API that changes breaks the **compile**,
loudly, at a bump the maintainer chose to make, and `make build` is a gate. It cannot make the
app stop working on a user's phone after an OS update, which is the failure this project is
actually afraid of. If Material3 ever does break them, the fallback is written above and is
maybe 60 lines.

---

## 13. Status and next steps

This section is the resume point. It says what has been done and verified, and what has not.

### Done, all four gates green (`make test` 305 tests / `make build` / `make lint` "No issues
### found" / `make crossimpl` OK), committed and pushed to master

| Step | Commit | What |
|---|---|---|
| Deprecation sweep (part) | `4d68175` | Removed `android:statusBarColor` / `android:navigationBarColor` (deprecated in API 35, no-ops for a targetSdk-35+ app); `mutableStateOf(Int)` -> `mutableIntStateOf`; declared `androidx.core` explicitly. |
| SDK wave 1/5 | `cfa200b` | Gradle wrapper **9.5.0 -> 9.7.1**, plus `distributionSha256Sum` (new). |
| SDK wave 2/5 | `464d112` | **AGP 9.3.3 -> 9.4.1** (needs Gradle >= 9.6, supports API 37). |
| SDK wave 3/5 | `ae43e55` | **compileSdk 36 -> 37**. |
| SDK wave 4/5 | `f1347ab` | Compose BOM **2026.06.01 -> 2026.09.00**, navigation-compose **2.9.8 -> 2.10.1**, lifecycle **2.10.0 -> 2.11.0**, androidx.core **1.18.0 -> 1.19.0**. Kotlin (2.4.20) and Bouncy Castle (1.86) were already current. |
| SDK wave 5/5 | `65f1e42` | **targetSdk 36 -> 37**. Both Android 17 behaviour-change lists audited row by row (§8): **no code change was required**. `lintDebug` went to "No issues found" - not one version notice left. |
| okhttp removal | `f5c7e69` | `net/Http.kt` over `HttpURLConnection`; `net/FakeHttpServer.kt` over `com.sun.net.httpserver` replaces MockWebServer; okhttp + okio gone from the APK; two redundant `@OptIn`s and a dead `kotlin-android` plugin alias removed; `make probe` added. |
| R8 | `33ef5af` | Dropped the blanket Bouncy Castle keep rule and the dead Tink rules: **release APK 4.21 MB -> 2.12 MB**, 9245 -> 3498 classes. |

Also verified by hand, beyond the gates:

- The **API 37 platform IS installable** on this machine (`platforms;android-37.0`,
  `build-tools;37.0.0`, `system-images;android-37.0;google_apis;arm64-v8a`), so the wave went all
  the way; nothing was stopped short.
- The **R8-minified release APK runs on an Android 17 (API 37) emulator**: installed, launched,
  onboarding form renders edge-to-edge, no `AndroidRuntime` error.
- **16 KB page size**: `zipalign -c -P 16 -v 4` passes for all four ABIs of the one native library
  (Compose's, shipped by AndroidX).
- The live provider probe: see §6 for exactly what was and was not re-verified. **Yahoo still owes
  a green `make probe` run from a non-rate-limited IP.**

### Not done, for a later session

1. **`make setup` and `make doctor`** (brief part 3). Nothing of this exists yet. The requirements
   were established while working and are: Homebrew; `temurin@21`; `android-commandlinetools`;
   `JAVA_HOME` + `ANDROID_HOME`; accepted SDK licences (`sdkmanager --licenses`); the packages
   `platform-tools`, `platforms;android-37.0`, `build-tools;37.0.0`, `emulator`,
   `system-images;android-37.0;google_apis;arm64-v8a`; an AVD; and, for a release only, the
   keystore outside the repo. `make doctor` should be read-only and print OK/MISSING plus the
   exact fix command; `make setup` idempotent; every other target should fail early with "run
   make doctor".
2. **Reproducible build** (brief part 2d), partly done. The wrapper now carries its
   `distributionSha256Sum` and every version in `gradle/libs.versions.toml` is exact (no `+`, no
   dynamic range) - verified. Still open: pin the JDK through a Gradle **toolchain** block so the
   build does not depend on the laptop's default JDK (weigh the foojay resolver plugin against
   the "fewest plugins" rule - it is a third-party plugin and probably fails that test, in which
   case pin the toolchain without auto-provisioning and let `make doctor` install the JDK);
   review `gradle.properties` (`org.gradle.jvmargs`, `parallel`, `caching` - all still
   justified); and turn the **configuration cache** on if it is green (Gradle prints the
   suggestion on every build today).
3. **README and AGENTS.md rewrite for a Go developer landing cold** (brief part 4). Not started.
   Numbers that MUST be updated wherever they appear: compileSdk/targetSdk **36 -> 37**, Gradle
   **9.5.0 -> 9.7.1**, AGP **9.3.0 -> 9.4.1**, test count **304 -> 305** (the extra one is the
   opt-in `LiveProviderProbe`, which is skipped unless `-Dprobe=1`), and the emulator AVD (a
   `test37` AVD on API 37 now exists beside the old API 36 `test`). AGENTS.md's "Known deferred
   work" section still says the SDK 37 wave is deferred - **that entry is now obsolete and must
   be deleted**. AGENTS.md also needs the owner's priorities as its first golden rule, phrased
   impersonally ("this app is maintained by Go developers, not Android specialists"), and a
   "when Android moves" yearly checklist. The architecture map needs a row for the new `net/`
   package.
4. **The `market/` and `remote/` doc comments** were left as they were; `net/Http.kt` and
   `net/FakeHttpServer.kt` are documented, but AGENTS.md's table does not mention them yet.
5. **Not attempted, deliberately**: raising `minSdk` to drop `androidx.biometric` (§4, a product
   decision); hand-building the top bar and the exposed dropdown to drop the last ten `@OptIn`s
   (§12, no functional gain); replacing navigation-compose (§3, refused with reasons).
