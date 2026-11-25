# Maintainability inventory

The priorities are future Android compatibility, little recurring work, few dependencies, no
deprecated application API, and a clear path from a fresh laptop to a working build. This file
records current dependencies and procedures; upgrade rationale also lives in commit messages.
Review upstream guidance during the yearly wave in README section 6.

## 1. Dependency scorecard

| Dependency | Maintainer | Responsibility | Decision |
|---|---|---|---|
| AGP and Gradle wrapper | Google, Gradle | APK build | Keep exact pins and the wrapper checksum |
| Kotlin and Compose compiler | JetBrains, Google | Language and Compose compilation | Keep compiler plugins on one version |
| kotlinx-serialization-json | JetBrains | Typed JSON | Keep generated serializers |
| kotlinx-coroutines-android | JetBrains | Android dispatcher and structured concurrency | Keep |
| Compose UI and Material3 | AndroidX | Screens and widgets | Keep, opt-ins in section 12 |
| Activity, Lifecycle, Core | AndroidX | Activity, ViewModel and platform compatibility | Keep |
| Navigation 3 runtime and UI | AndroidX | Destination state and predictive back | Keep, section 3 |
| Biometric | AndroidX | Authentication down to minSdk 26 | Keep stable; watch 1.4, section 4 |
| Bouncy Castle | Legion of the Bouncy Castle | Argon2id only | Keep, section 5 |
| JUnit 4, AndroidX test runner | JUnit, AndroidX | Host tests and device probe | Test-only, section 7 |

Bouncy Castle supplies application cryptography outside the Android/AndroidX and Kotlin ecosystems.
The application adds no HTTP library, dependency-injection framework, database or telemetry.

## 2. Toolchain and first-party libraries

`gradle/libs.versions.toml` pins every direct dependency. The wrapper pins its distribution URL and
SHA-256. No dynamic versions, ranges or snapshots are permitted. Gradle 9.9.1 and AGP 9.5.1 meet
AGP's Gradle 9.6 minimum and API 37 support. JDK 21 is the compilation/test toolchain; bytecode stays
Java 17 for D8. A language-version pin does not pin the JDK vendor/patch or independently select
the Gradle daemon runtime. Make, setup and doctor share host JDK/SDK discovery.

Sources: [AGP compatibility](https://developer.android.com/build/releases/agp-9-5-0-release-notes),
[Gradle release](https://docs.gradle.org/9.9.1/release-notes.html),
[checksums](https://gradle.org/release-checksums/).

The Compose and serialization compiler plugins share Kotlin's version. The serialization runtime
has a separate version; upgrades must preserve malformed-ledger errors and format vectors.
Replacing typed generated serializers with hand-written org.json parsers adds risk to storage.
Coroutines already underpin Compose/Lifecycle; the explicit Android artifact supplies Dispatchers.Main.
Core is declared explicitly for its executor, preference and window helpers. The ViewModel factory
uses stable `viewModelFactory`/`initializer`, without a generic unchecked cast or suppression.

## 3. Navigation 3

[Navigation 2 is maintenance-only](https://developer.android.com/jetpack/androidx/releases/navigation).
Navigation 3 runtime and UI 1.2.1 replace navigation-compose and fit the existing SDK requirements.
Both are AndroidX; the serialization plugin was already required by the ledger.

Eleven Compose destinations include three identifier-bearing routes. NavigationState owns the
small forward/back policy and three tab stacks. A concrete sealed serializer saves route identity,
not ledger contents. Inactive tabs retain saveable-state decorators; NavDisplay supplies lifecycle,
rendering and predictive back. One activity-scoped ViewModel is passed to every screen, so the
Navigation 3 ViewModel and adaptive-layout add-ons are unnecessary.

On upgrades, run policy/serialization tests and smoke tab scroll retention, detail/editor back,
back from Gains/Settings to Portfolio, rotation/process recreation, and predictive-back cancellation
and completion. A replacement renderer must preserve these guarantees; a hand-written navigator
would add maintenance. Route identity and policy remain separate from rendering.

Sources: [releases](https://developer.android.com/jetpack/androidx/releases/navigation3),
[migration](https://developer.android.com/guide/navigation/navigation-3/migration-guide),
[saved state](https://developer.android.com/guide/navigation/navigation-3/save-state).

## 4. Biometrics and unlock policy

Biometric 1.1.0 is the latest stable release, but development is active. The 1.4 line adds Activity
Result/Compose APIs and ComponentActivity support. Revisit it when stable; removing FragmentActivity
alone does not justify a beta dependency.

Current Core's FingerprintManagerCompat is a no-op, and Biometric 1.1.0 still uses it. On API 26/27,
the app falls back to the system PIN, pattern or password because it allows device credentials.
Do not downgrade Core or import deprecated fingerprint APIs. The 1.4 line has its own compatibility
implementation. Raising minSdk to 30 to use the platform prompt alone is a separate product decision;
the future AndroidX API offers a path that retains older-phone support.

Direct unlock requires positive evidence of an unsecured device (`isDeviceSecure` false): with no
screen lock there is nothing to authenticate against, a prompt can only fail, and refusing would
lock the holder out of their own ledger. Errors and unknown device-lock state never become direct
access. Every attempt checks again; on a secured device unknown biometric status may try the
system prompt, and errors remain retryable.
API 30+ can use credentials alone when the combined check fails. Prompt callbacks are rebound after
activity recreation without restarting an existing prompt.

Sources: [Biometric releases](https://developer.android.com/jetpack/androidx/releases/biometric),
[Core releases](https://developer.android.com/jetpack/androidx/releases/core),
[availability results](https://developer.android.com/reference/androidx/biometric/BiometricManager).

## 5. Bouncy Castle and storage

Only crypto/Argon2.kt imports Bouncy Castle, using Argon2BytesGenerator and Argon2Parameters.
Argon2id is mandated by the shared format and has no Android platform implementation. AES-GCM,
SHA-256 and the HMAC used by HKDF already use platform APIs. Keep the pure-Java dependency:
writing a crypto primitive adds correctness risk; JNI adds ABI/page-size work and complicates tests.
R8 traces direct calls, so no blanket Bouncy Castle keep rule is needed. Review security releases
quarterly; upgrades require Argon2 vectors and `make crossimpl`.

SecretStore uses Android Keystore AES-GCM with fresh generated IVs. Keys are device-bound, so both
backup-rule formats exclude application data. Ledger, sync-state, configuration and market-cache
files use the project's atomic-file mechanism. Preserve those guarantees when changing storage.

## 6. Platform HTTP

net/Http.kt uses HttpURLConnection/HttpsURLConnection for bodies, headers, timeouts and the single
retry. The platform supplies pooling, certificate validation and gzip handling. No custom trust
manager or hostname verifier is used. java.net.http.HttpClient is a desktop JDK API, not an Android
replacement. HTTP errors use errorStream; a retry creates a fresh connection.

okhttp/okio were removed because their additional facilities were unused. Host tests use the JDK's
HttpServer through FakeHttpServer instead of MockWebServer. Transport changes require hermetic tests
and the live device probe; a host-only result cannot describe Android's TLS stack.

## 7. Test dependencies and gates

JUnit 4 suffices for pure Kotlin tests and the Android instrumentation runner. AndroidX test runner
is instrumentation-only and reaches neither application APK. No mocking framework or Robolectric
is needed; keep Android types out of crypto/domain/format/market/valuation.

`make test` compiles main and tests; `make build` checks APK packaging. Kotlin warnings fail the
compile, and lint warnings fail `make lint`. "A newer version is available" checks are
informational (`app/lint.xml`): they depend on what upstream published, not on this code, so they
never turn the gate red; `make outdated` lists them for the yearly wave. Crypto/format
changes require `make crossimpl`; use it for serializer/KDF dependency upgrades too. Pure navigation
and unlock tests protect policy; device smokes protect rendering, authentication and lifecycle.

## 8. Platform and deprecation ledger

The only lint exclusions are documented in app/lint.xml: adaptive-icon resource resolution, the
predictive-back attribute on older API levels, and the newer-version checks kept informational.
There are no Kotlin deprecation suppressions. Fix new
warnings instead of extending exclusions or adding a baseline.

| Platform surface | Handling |
|---|---|
| Storage | Private application files; no storage permission |
| Background work | No application service, scheduler, alarm or background launch |
| Edge-to-edge | Scaffold insets and navigation-bar padding; no opt-out or deprecated bar colors |
| Predictive back | Navigation 3 and manifest opt-in; smoke cancellation and completion |
| Backup/device transfer | Explicit exclusions and allowBackup=false; Keystore secrets are device-bound |
| Cleartext traffic | HTTPS only; no usesCleartextTraffic exception |
| Exported components | Application launcher is explicitly exported |
| Native code | Compose graphics-path library only; separate ELF and ZIP alignment checks |

The source manifest declares INTERNET and one activity. The merged APK also includes AndroidX
biometric permissions, a signature-protected receiver permission, Startup initializers and the
ProfileInstaller receiver protected by android.permission.DUMP. Inspect the merged manifest when
dependencies change; distinguish application-owned features from dependency components.

### Android 17 / API 37

Review [all-app changes](https://developer.android.com/about/versions/17/behavior-changes-all) and
[target-37 changes](https://developer.android.com/about/versions/17/behavior-changes-17).

| Change | Applicability |
|---|---|
| Memory limits | Argon2, Compose and price history allocate memory; measure a synthetic portfolio when diagnosing a limit exit |
| SMS/OTP, contacts, Bluetooth, audio, pointer capture | Not used |
| URI grants and cross-profile loopback | No corresponding intents or targets |
| Keystore limit | One reusable application key |
| IME after rotation / physical keyboards | Compose text entry; smoke form retention and keyboard behavior |
| RemoteViews limits | No widgets or RemoteViews |
| MessageQueue / static-final reflection | No application reflection into those internals |
| ECH and Certificate Transparency | Relevant to HTTPS providers; verify on the device's TLS stack |
| Local-network permission | No local-network endpoint |
| Background activity starts | None in application code; authentication delegates to AndroidX/platform |
| Native dynamic loading | No application System.load or downloaded executable code |
| Content Capture deprecation | Deprecated API unused; screenshot blocking is a separate product policy |
| Large-screen resizing/orientation | No manifest restriction; smoke a resized window |

`release-apk` runs `zipalign -c -P 16 4` before staging. This checks ZIP placement, not ELF layout.
After native-library upgrades, also inspect arm64-v8a/x86_64 LOAD alignment and RELRO with APK
Analyzer or the documented LLVM tools, and test a 16 KB device/emulator. Current graphics-path
LOAD segments are 16 KB aligned; Bouncy Castle has no native library.
[Android page-size guidance](https://developer.android.com/guide/practices/page-sizes) distinguishes
the static checks from runtime verification.

## 9. Recurring maintenance

1. Upgrade in order: wrapper, AGP, Kotlin, compileSdk, AndroidX, targetSdk. Run gates between steps.
2. Review both platform behavior-change pages and update section 8 with actual applicability.
3. Smoke the new API on an emulator. Run `make probe-device` for transport/provider changes and
   before releases; compiling against an SDK is not runtime proof.
4. Review Bouncy Castle security releases quarterly and provider failures when observed.
5. Review distribution policy. GitHub APKs avoid Google Play target-SDK submission deadlines,
   but Android developer verification is expanding outside Play. Consult the
   [current rollout and distribution paths](https://developer.android.com/developer-verification)
   before changing distribution. A regional store rollout does not imply all GitHub installs
   are currently blocked.

## 10. Maintenance boundaries

Android still needs a JDK, Gradle, SDK/build-tools and licenses. Setup reduces the work without
removing those upstream dependencies. Platform behavior, UI libraries and unofficial providers
change independently. Pins make upgrades deliberate; tests/lint/runtime smokes prove compatibility.
Do not add a backend, telemetry, automatic import, periodic work, notification or custom service
without explicitly accounting for its recurring cost.

## 11. Replacements and upgrade decisions

Deprecated security-crypto became platform Keystore AES-GCM. Frozen material-icons-extended became
the few Apache-licensed paths the app draws. okhttp and MockWebServer became platform HTTP and the
JDK test server. Maintenance-only Navigation 2 became the stable Compose-first successor.
These changes remove obsolete surfaces; they do not justify replacing required Argon2 with custom
crypto or adopting biometric beta APIs while a stable system-credential fallback works.

## 12. Experimental APIs

Nine function-scoped ExperimentalMaterial3Api opt-ins cover TopAppBar and exposed dropdowns.
There are no compiler-wide opt-ins or experimental coroutine APIs. The fallback is stable Compose
primitives: Surface/Row/Text/IconButton for a bar, OutlinedTextField plus DropdownMenu for selection.
Do not reimplement working widgets just to remove annotations. Recount the call sites when updating
Material3, because APIs can become stable. Source errors at a deliberate bump remain visible gates.

## 13. Environment and dependency verification

scripts/environment.sh shares JDK/SDK discovery across make, setup and doctor. Explicit overrides
win, local.properties supplies the SDK when present, and Java discovery selects a full JDK of
the required major version through macOS metadata or java on PATH. Doctor and setup check for
android.jar in the base platform requested by compileSdk. Doctor installs nothing. Setup installs
missing macOS prerequisites or explains the Linux package-manager prerequisites, then equips the
SDK. It never creates a signing key.

Emulator setup checks the requested flavor and host ABI, with modern/legacy package spellings.
Startup selects the named AVD, reuses it if running, and bounds boot waiting. Failure prints its
log and stops only a process this command started. Use ANDROID_SERIAL for subsequent commands when
more than one device is attached.

sdkmanager is deprecated, but `android sdk` still lacks a documented unattended license-acceptance
command. Official documentation still prescribes `sdkmanager --licenses`. Keep the narrow wrapper
until [the replacement workflow](https://developer.android.com/tools/sdkmanager) covers setup.
Do not exchange working unattended installation for an undocumented interactive assumption.

JDK auto-download remains disabled: the package manager avoids adding a resolver plugin and API.
Gradle configuration/build caches are enabled. Artifact-verification metadata remains deliberately
unadopted: the evaluation produced 2,577 lines for 370 artifacts, covered only exercised resolution
paths and expanded every upgrade's review burden. Exact versions and the wrapper checksum remain
pinned. Revisit that tradeoff if the supply-chain threat model changes.

## 14. TLS fingerprinting and device probes

Uniform provider HTTP 429 responses are not proof of an IP throttle. The host JVM uses JSSE;
Android uses Conscrypt, and anti-bot systems can fingerprint offered ClientHello suites. In the
observed Yahoo failure, Go and Android worked from the same address while JSSE failed. Removing
legacy DHE/DSS suites restored the host request.

net/Tls.kt delegates to the platform and removes only those families. A deny-list preserves new
platform algorithms; a pinned allow-list would age. Never weaken certificate or hostname checks.
The filter was a no-op on the measured Android stack.

LiveProbe prints reachability and TLS evidence from the same body on host and device. Diagnose
with curl, Go, the host TLS block, then the device probe. A green device with a red host is not a
product failure. A release requires a device pass; the host TLS implementation cannot substitute.

## 15. Provider maintenance

Unofficial providers can change payloads or hosts independently of Android. Keep errors isolated
behind Source and preserve fallbacks. Value/parsing changes start in the Go reference and are
mirrored with parity tests.

Morningstar uses lt.morningstar.com after its former regional host stopped resolving. Its public
view token serves screener and timeseries requests. The security-id suffix is part of the API
tuple; omitting it can produce an empty successful response. Preserve padded currency/sub-unit
spelling until Units normalizes values. Search both fund universes and prefer exact ISIN matches.
Concrete protocol details belong in the provider source and its hermetic tests.
